package com.devcli.workspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期写入屏障：拦截"读了旧版本 → 别人改了 → 仍按旧版本写回"的丢失更新。
 *
 * <p>资源租约只在步骤执行期内防止并发写同一文件，租约随步骤结束释放；
 * 跨步骤的 read-modify-write 过期问题它管不到，这就是本屏障补的缺口。
 *
 * <p>边界：只对非空 scope（Multi-Agent 步骤）生效。单 Agent 路径没有步骤概念，
 * 且"读文件 → 执行命令改文件 → 写回"是正常流程，不能拦。
 */
class StaleWriteBarrierTest {

    private final Path file = Path.of("src", "main", "java", "Order.java").toAbsolutePath();

    @Test
    void allowsWriteWhenScopeNeverReadTheFile() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();

        assertNull(barrier.staleReason("step_1", file, "现有内容"),
                "没读过就写属于整体覆盖，不是 read-modify-write，不该拦");
    }

    @Test
    void allowsWriteWhenFileUnchangedSinceRead() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        barrier.recordRead("step_1", file, "v1");

        assertNull(barrier.staleReason("step_1", file, "v1"));
    }

    @Test
    void blocksWriteWhenAnotherScopeChangedTheFileAfterRead() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        barrier.recordRead("step_1", file, "v1");
        barrier.recordWrite("step_2", file, "v2");

        String reason = barrier.staleReason("step_1", file, "v2");

        assertNotNull(reason, "step_1 按 v1 写回会覆盖 step_2 的改动，必须拦");
        assertTrue(reason.contains("step_2"), "错误信息要指出是谁改的，便于模型决策: " + reason);
        assertTrue(reason.contains("Order.java"), "错误信息要指出哪个文件: " + reason);
    }

    @Test
    void selfWriteRefreshesTheObservedVersion() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        barrier.recordRead("step_1", file, "v1");
        barrier.recordWrite("step_1", file, "v2");

        assertNull(barrier.staleReason("step_1", file, "v2"),
                "自己写完后应认得当前版本，不能自我阻塞");
    }

    @Test
    void reReadClearsStaleness() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        barrier.recordRead("step_1", file, "v1");
        barrier.recordWrite("step_2", file, "v2");
        assertNotNull(barrier.staleReason("step_1", file, "v2"));

        barrier.recordRead("step_1", file, "v2");

        assertNull(barrier.staleReason("step_1", file, "v2"),
                "重读是模型可执行的恢复动作，重读后必须放行");
    }

    @Test
    void ignoresBlankScopeSoSingleAgentPathIsUnaffected() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        barrier.recordRead("", file, "v1");
        barrier.recordWrite("", file, "v2");

        assertNull(barrier.staleReason("", file, "v3"),
                "单 Agent 路径不启用屏障，读完跑命令再写回是正常流程");
    }

    @Test
    void blocksEvenWhenChangeCameFromUntrackedSource() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        barrier.recordRead("step_1", file, "v1");

        String reason = barrier.staleReason("step_1", file, "changed-on-disk");

        assertNotNull(reason, "磁盘内容与读到的不一致就说明过期，不必知道是谁改的");
        assertTrue(reason.contains("Order.java"), reason);
    }

    @Test
    void blocksWriteToAnotherFileWhenObservedJavaSymbolChanged() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        Path service = Path.of("src", "main", "java", "OrderService.java").toAbsolutePath();
        Path caller = Path.of("src", "main", "java", "OrderController.java").toAbsolutePath();
        String original = """
                class OrderService {
                    String find(String id) { return id; }
                }
                """;
        String changed = """
                class OrderService {
                    String find(long id) { return Long.toString(id); }
                }
                """;

        barrier.recordRead("step_1", service, original);
        barrier.recordWrite("step_2", service, original, changed);

        String reason = barrier.staleReason("step_1", caller, "class OrderController {}");

        assertNotNull(reason, "依赖的 Java 符号变化后，写调用方前必须刷新上下文");
        assertTrue(reason.contains("OrderService.find"), reason);
        assertTrue(reason.contains("step_2"), reason);
    }

    @Test
    void doesNotMarkWriterStaleForItsOwnJavaSymbolChange() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        Path service = Path.of("src", "main", "java", "OrderService.java").toAbsolutePath();
        String original = "class OrderService { String find(String id) { return id; } }";
        String changed = "class OrderService { String find(long id) { return Long.toString(id); } }";

        barrier.recordRead("step_1", service, original);
        barrier.recordWrite("step_1", service, original, changed);

        assertNull(barrier.staleReason("step_1", file, "class Order {}"),
                "写入者已拥有最新产物，不应被自己的变更阻断");
    }

    @Test
    void blocksWriteWhenSearchCodeObservedSymbolChanged() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        Path service = Path.of("src", "main", "java", "OrderService.java").toAbsolutePath();
        Path caller = Path.of("src", "main", "java", "OrderController.java").toAbsolutePath();
        String original = "class OrderService {\n"
                + "    String find(String id) { return id; }\n"
                + "}\n";
        String changed = "class OrderService {\n"
                + "    String find(long id) { return Long.toString(id); }\n"
                + "}\n";

        barrier.recordCodeEvidence("step_1", service, "method", "OrderService.find(String id)",
                "sv-old", "String find(String id) { return id; }");
        barrier.recordWrite("step_2", service, original, changed);

        String reason = barrier.staleReason("step_1", caller, "class OrderController {}\n");

        assertNotNull(reason, reason);
        assertTrue(reason.contains("OrderService.find(String id)"), reason);
        assertTrue(reason.contains("sv-old"), reason);
    }

    @Test
    void rereadClearsSearchCodeObservationForThatFile() {
        StaleWriteBarrier barrier = new StaleWriteBarrier();
        Path service = Path.of("OrderService.java").toAbsolutePath();
        Path caller = Path.of("Caller.java").toAbsolutePath();
        String original = "class OrderService {\n"
                + "    String find(String id) { return id; }\n"
                + "}\n";
        String changed = "class OrderService {\n"
                + "    String find(long id) { return Long.toString(id); }\n"
                + "}\n";

        barrier.recordCodeEvidence("step_1", service, "method", "OrderService.find(String id)",
                "sv-old", "String find(String id) { return id; }");
        barrier.recordWrite("step_2", service, original, changed);
        assertNotNull(barrier.staleReason("step_1", caller, "class Caller {}\n"));

        barrier.recordRead("step_1", service, changed);

        assertNull(barrier.staleReason("step_1", caller, "class Caller {}\n"));
    }
}
