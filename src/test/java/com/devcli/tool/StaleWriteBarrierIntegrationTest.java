package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 过期写入屏障经 write_file 工具链的端到端行为。
 *
 * <p>验证屏障真的接进了 ToolRegistry —— {@code ToolContext} 上的两个方法是 default 空实现，
 * 少了 @Override 会静默失效，只靠单元测试看不出来。
 */
class StaleWriteBarrierIntegrationTest {

    private static String writeArgs(Path path, String content) {
        return "{\"path\":\"" + escape(path.toString()) + "\",\"content\":\"" + escape(content) + "\"}";
    }

    private static String readArgs(Path path) {
        return "{\"path\":\"" + escape(path.toString()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void blocksCrossStepStaleWriteAndAllowsRewriteAfterReread(@TempDir Path projectRoot) throws IOException {
        Path target = projectRoot.resolve("Order.java");
        Files.writeString(target, "v1");

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());

        // step_1 读到 v1
        ToolOutput read = registry.runWithResourceLease("step_1",
                () -> registry.executeToolOutput("read_file", readArgs(target)));
        assertTrue(read.text().contains("v1"), read.text());

        // step_2 把文件改成 v2，并释放租约（与 AgentOrchestrator.runStep 的 finally 一致）
        try {
            ToolOutput otherWrite = registry.runWithResourceLease("step_2",
                    () -> registry.executeToolOutput("write_file", writeArgs(target, "v2")));
            assertTrue(otherWrite.text().contains("文件已写入"), otherWrite.text());
        } finally {
            registry.releaseResourceLeases("step_2");
        }
        assertEquals("v2", Files.readString(target));

        // step_1 仍按旧版本写回：必须被屏障拦下，且理由是过期而不是租约冲突
        ToolOutput staleWrite = registry.runWithResourceLease("step_1",
                () -> registry.executeToolOutput("write_file", writeArgs(target, "v1-plus-my-change")));
        assertTrue(staleWrite.text().contains("过期写入被拦截"),
                "应被过期写入屏障拦下，实际: " + staleWrite.text());
        assertTrue(staleWrite.text().contains("step_2"),
                "理由要指出是谁改的: " + staleWrite.text());
        assertEquals("v2", Files.readString(target), "被拦截时不得落盘");
        registry.releaseResourceLeases("step_1");

        // 重读是模型可执行的恢复动作，重读后应放行
        registry.runWithResourceLease("step_1",
                () -> registry.executeToolOutput("read_file", readArgs(target)));
        try {
            ToolOutput retried = registry.runWithResourceLease("step_1",
                    () -> registry.executeToolOutput("write_file", writeArgs(target, "v3")));
            assertTrue(retried.text().contains("文件已写入"),
                    "重读后应放行，实际: " + retried.text());
        } finally {
            registry.releaseResourceLeases("step_1");
        }
        assertEquals("v3", Files.readString(target));
    }

    /**
     * 关键防回归：租约释放发生在<b>每次 Worker 调用</b>结束（一个步骤会调多次——初次 / 修复 / 重试），
     * 不等于步骤结束。它绝不能顺带清掉读取观察，否则被拦后的那次重试就失去屏障保护。
     */
    @Test
    void leaseReleaseWithinStepDoesNotDropStaleness(@TempDir Path projectRoot) throws IOException {
        Path target = projectRoot.resolve("Order.java");
        Files.writeString(target, "v1");

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());

        registry.runWithResourceLease("step_1", () -> registry.executeToolOutput("read_file", readArgs(target)));
        try {
            registry.runWithResourceLease("step_2",
                    () -> registry.executeToolOutput("write_file", writeArgs(target, "v2")));
        } finally {
            registry.releaseResourceLeases("step_2");
        }

        // 模拟 step_1 的一次 Worker 调用结束：只释放租约，步骤本身还在继续
        registry.releaseResourceLeases("step_1");

        ToolOutput retryWithoutReread = registry.runWithResourceLease("step_1",
                () -> registry.executeToolOutput("write_file", writeArgs(target, "still-based-on-v1")));

        assertTrue(retryWithoutReread.text().contains("过期写入被拦截"),
                "租约释放不得清掉读取观察，否则重试时屏障失效: " + retryWithoutReread.text());
        assertEquals("v2", Files.readString(target));
        registry.releaseResourceLeases("step_1");
    }

    @Test
    void stepScopeCleanupDropsObservations(@TempDir Path projectRoot) throws IOException {
        Path target = projectRoot.resolve("Order.java");
        Files.writeString(target, "v1");

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());

        registry.runWithResourceLease("step_1", () -> registry.executeToolOutput("read_file", readArgs(target)));
        try {
            registry.runWithResourceLease("step_2",
                    () -> registry.executeToolOutput("write_file", writeArgs(target, "v2")));
        } finally {
            registry.releaseResourceLeases("step_2");
        }

        // 步骤真正结束：清理其观察记录，避免长会话无界增长
        registry.forgetStaleWriteScope("step_1");

        try {
            ToolOutput laterWrite = registry.runWithResourceLease("step_1",
                    () -> registry.executeToolOutput("write_file", writeArgs(target, "v3")));
            assertTrue(laterWrite.text().contains("文件已写入"),
                    "步骤结束后观察记录应已清理，不应继续阻塞: " + laterWrite.text());
        } finally {
            registry.releaseResourceLeases("step_1");
        }
        assertEquals("v3", Files.readString(target));
    }

    @Test
    void singleAgentPathIsNotAffected(@TempDir Path projectRoot) throws IOException {
        Path target = projectRoot.resolve("Notes.md");
        Files.writeString(target, "v1");

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());

        // 无步骤 id：读 → 文件被外部改动 → 写回，属单 Agent 正常流程，不得拦
        registry.executeToolOutput("read_file", readArgs(target));
        Files.writeString(target, "changed-externally");

        ToolOutput write = registry.executeToolOutput("write_file", writeArgs(target, "v2"));

        assertTrue(write.text().contains("文件已写入"),
                "单 Agent 路径不启用屏障，实际: " + write.text());
        assertEquals("v2", Files.readString(target));
    }

    @Test
    void blocksCallerWriteWhenAnotherStepChangesObservedJavaSignature(@TempDir Path projectRoot) throws IOException {
        Path service = projectRoot.resolve("OrderService.java");
        Path caller = projectRoot.resolve("OrderController.java");
        Files.writeString(service, "class OrderService { String find(String id) { return id; } }");
        Files.writeString(caller, "class OrderController { }");

        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        registry.runWithResourceLease("step_1", () -> registry.executeToolOutput("read_file", readArgs(service)));

        try {
            ToolOutput changed = registry.runWithResourceLease("step_2", () ->
                    registry.executeToolOutput("write_file", writeArgs(service,
                            "class OrderService { String find(long id) { return Long.toString(id); } }")));
            assertTrue(changed.isSuccess(), changed.text());
        } finally {
            registry.releaseResourceLeases("step_2");
        }

        ToolOutput stale = registry.runWithResourceLease("step_1", () ->
                registry.executeToolOutput("write_file", writeArgs(caller,
                        "class OrderController { String load(OrderService service) { return service.find(1L); } }")));

        assertTrue(stale.text().contains("上下文已过期"), stale.text());
        assertTrue(stale.text().contains("OrderService.find"), stale.text());
        assertEquals("class OrderController { }", Files.readString(caller), "过期上下文不得落盘");
        registry.releaseResourceLeases("step_1");
    }
}
