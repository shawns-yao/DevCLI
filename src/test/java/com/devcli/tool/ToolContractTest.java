package com.devcli.tool;

import com.devcli.hitl.ApprovalPolicy;
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具契约（破坏性等级 / 幂等性 / 缓存资格）的声明与消费行为。
 */
class ToolContractTest {

    /**
     * 工具名看起来是查询，实际会改状态：本地只读授权只放宽访问等级，
     * 不能据此认定它没有状态流转，因此必须落到 BENIGN + 非幂等。
     */
    @Test
    void locallyTrustedReadOnlyMcpToolIsDeclaredBenignAndNonIdempotent() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setMcpToolTrustPolicy("mail",
                    McpToolTrustPolicy.untrusted().withReadOnlyTools("get_unread_emails"));
            McpToolDescriptor descriptor = descriptor("mail", "get_unread_emails");
            registry.registerMcpToolOutput(descriptor, arguments -> ToolOutput.success("1 unread"));
            String name = descriptor.namespacedName();

            assertEquals(ToolRegistry.ToolEffect.READ_ONLY, registry.toolEffect(name),
                    "本地只读授权仍应允许在只读范围使用");
            assertEquals(ToolRegistry.Destructiveness.BENIGN, registry.toolDestructiveness(name),
                    "已读标记这类状态流转不可逆但无资损，必须按 BENIGN 声明而不是 NONE");
            assertEquals(ToolRegistry.Idempotency.NON_IDEMPOTENT, registry.toolIdempotency(name),
                    "重复调用返回不同结果，不能并发也不能重试");
            assertFalse(registry.toolCacheable(name), "状态流转工具不得进入短期结果缓存");
        }
    }

    @Test
    void untrustedMcpToolStaysStructuralAndNonIdempotent() {
        try (ToolRegistry registry = new ToolRegistry()) {
            McpToolDescriptor descriptor = descriptor("mail", "send_email");
            registry.registerMcpToolOutput(descriptor, arguments -> ToolOutput.success("sent"));
            String name = descriptor.namespacedName();

            assertEquals(ToolRegistry.ToolEffect.EXTERNAL_MUTATION, registry.toolEffect(name));
            assertEquals(ToolRegistry.Destructiveness.STRUCTURAL, registry.toolDestructiveness(name));
            assertEquals(ToolRegistry.Idempotency.NON_IDEMPOTENT, registry.toolIdempotency(name));
            assertFalse(registry.toolCacheable(name));
        }
    }

    @Test
    void nonIdempotentReadOnlyToolsDoNotRunInParallel() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ConcurrencyProbe probe = new ConcurrencyProbe();
            registerReadTool(registry, "stateful_read_a", probe,
                    ToolRegistry.Destructiveness.BENIGN, ToolRegistry.Idempotency.NON_IDEMPOTENT);
            registerReadTool(registry, "stateful_read_b", probe,
                    ToolRegistry.Destructiveness.BENIGN, ToolRegistry.Idempotency.NON_IDEMPOTENT);

            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("a", "stateful_read_a", "{}"),
                    new ToolRegistry.ToolInvocation("b", "stateful_read_b", "{}")));

            assertTrue(results.stream().allMatch(result -> result.status() == ToolStatus.SUCCESS));
            assertEquals(1, probe.peak.get(), "非幂等只读工具不得同批并行执行");
        }
    }

    @Test
    void idempotentReadOnlyToolsStillRunInParallel() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ConcurrencyProbe probe = new ConcurrencyProbe();
            registerReadTool(registry, "stateless_read_a", probe,
                    ToolRegistry.Destructiveness.NONE, ToolRegistry.Idempotency.IDEMPOTENT);
            registerReadTool(registry, "stateless_read_b", probe,
                    ToolRegistry.Destructiveness.NONE, ToolRegistry.Idempotency.IDEMPOTENT);

            List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("a", "stateless_read_a", "{}"),
                    new ToolRegistry.ToolInvocation("b", "stateless_read_b", "{}")));

            assertTrue(results.stream().allMatch(result -> result.status() == ToolStatus.SUCCESS));
            assertEquals(2, probe.peak.get(), "幂等只读工具应保持既有并行行为");
        }
    }

    /** 缓存资格由契约声明：依赖外部可变状态的内置只读工具显式排除缓存。 */
    @Test
    void cacheEligibilityComesFromDeclaredContractNotToolName() {
        BuiltInToolPolicy.Policy readFile = BuiltInToolPolicy.find("read_file").orElseThrow();
        assertFalse(readFile.cacheable(), "文件读取依赖外部可变状态，必须显式声明不缓存");
        assertTrue(BuiltInToolPolicy.find("read_tool_result").orElseThrow().cacheable());
        assertTrue(BuiltInToolPolicy.find("search_tools").orElseThrow().cacheable());

        try (ToolRegistry registry = new ToolRegistry()) {
            assertFalse(registry.toolCacheable("read_file"));
            assertFalse(registry.toolCacheable("web_fetch"));
            assertTrue(registry.toolCacheable("search_tools"));
            assertFalse(registry.toolCacheable("write_file"));
        }
    }

    /** 未声明的第三方工具保持历史默认：只读即幂等可缓存，但绝不继承"只读=无副作用"的假设。 */
    @Test
    void undeclaredReadOnlyToolKeepsHistoricalDefault() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "custom_lookup", "test", JsonNodeFactory.instance.objectNode(),
                    arguments -> "value", ToolRegistry.ToolEffect.READ_ONLY));

            assertEquals(ToolRegistry.Destructiveness.NONE, registry.toolDestructiveness("custom_lookup"));
            assertEquals(ToolRegistry.Idempotency.IDEMPOTENT, registry.toolIdempotency("custom_lookup"));
            assertTrue(registry.toolCacheable("custom_lookup"));
        }
    }

    @Test
    void unknownToolIsNeverPresentedAsSafe() {
        assertEquals("🟡 中危", ApprovalPolicy.getDangerLevel("unknown_tool"));
        assertFalse(ApprovalPolicy.getRiskDescription("unknown_tool").contains("安全"),
                "没有本地契约的工具不得被描述成安全只读");
        assertTrue(ApprovalPolicy.getRiskDescription("mcp__demo__tool").contains("不可信"),
                "MCP 风险说明必须点明服务端只读注解不可信");
    }

    @Test
    void highDangerSignalsPreservedForExecutionAndBulkRollback() {
        assertEquals("🔴 高危", ApprovalPolicy.getDangerLevel("execute_command"));
        assertEquals("🔴 高危", ApprovalPolicy.getDangerLevel("revert_turn"));
        assertEquals("🟡 中危", ApprovalPolicy.getDangerLevel("write_file"));
        assertEquals("🟢 安全", ApprovalPolicy.getDangerLevel("read_file"));
        assertEquals("🟡 MCP", ApprovalPolicy.getDangerLevel("mcp__demo__tool"));
    }

    private static void registerReadTool(ToolRegistry registry,
                                         String name,
                                         ConcurrencyProbe probe,
                                         ToolRegistry.Destructiveness destructiveness,
                                         ToolRegistry.Idempotency idempotency) {
        registry.registerTool(new ToolRegistry.Tool(
                name, "contract probe", JsonNodeFactory.instance.objectNode(),
                arguments -> {
                    probe.enter();
                    try {
                        probe.bothEntered.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        probe.exit();
                    }
                    return "ok";
                },
                ToolRegistry.ToolEffect.READ_ONLY,
                -1,
                ToolRegistry.ToolCancellationCapability.INTERRUPT_ONLY,
                ToolPresentation.defaultFor(name),
                destructiveness,
                idempotency,
                false));
    }

    private static McpToolDescriptor descriptor(String server, String toolName) {
        return new McpToolDescriptor(
                server,
                toolName,
                McpToolDescriptor.namespaced(server, toolName),
                toolName,
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                null);
    }

    private static final class ConcurrencyProbe {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final CountDownLatch bothEntered = new CountDownLatch(2);

        private void enter() {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        }

        private void exit() {
            active.decrementAndGet();
        }
    }
}
