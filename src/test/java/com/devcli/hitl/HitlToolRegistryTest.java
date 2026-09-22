package com.devcli.hitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.devcli.browser.BrowserGuard;
import com.devcli.browser.BrowserSession;
import com.devcli.browser.SensitivePagePolicy;
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.policy.TaskGrant;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class HitlToolRegistryTest {

    // ------------------ 旁路行为（原有测试保留） ------------------

    @Test
    void disabledHitlPassesThroughToParent() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);

        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        // list_dir 不是危险工具，HITL 启用也应直接通过
        String result = registry.executeTool("list_dir", "{\"path\": \".\"}");
        assertNotNull(result);
        assertFalse(result.startsWith("[HITL]"));
    }

    @Test
    void hitlHandlerIsReturnedFromGetter() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        assertSame(handler, registry.getHitlHandler());
    }

    @Test
    void enableAndDisableHitl() {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        assertTrue(handler.isEnabled());
        handler.setEnabled(false);
        assertFalse(handler.isEnabled());
    }

    @Test
    void clearApprovedAllResetsState() {
        TerminalHitlHandler handler = new TerminalHitlHandler(true);
        handler.clearApprovedAll();
        assertTrue(handler.isEnabled());
    }

    // ------------------ 开启 HITL 后的决策分支（新增） ------------------

    @Test
    void rejectedDecisionBlocksExecutionAndReturnsRejectMessage(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("should-not-exist.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("too risky"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertTrue(result.startsWith("[HITL]"), "结果应为 HITL 拒绝消息: " + result);
        assertTrue(result.contains("too risky"));
        assertFalse(Files.exists(target), "拒绝后文件不应被创建");
        assertEquals(1, stub.requestCount(), "应只发起一次审批");
    }

    @Test
    void rejectionUsesStructuredResultThroughBasePipeline(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("structured-reject.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("denied"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        ToolOutput output = registry.executeToolOutput("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertEquals(ToolStatus.REJECTED, output.status());
        assertEquals(ToolErrorCode.HITL_REJECTED, output.errorCode());
        assertFalse(output.retryable());
        assertFalse(Files.exists(target));
        assertEquals(ToolRegistry.class, HitlToolRegistry.class
                .getMethod("executeToolOutput", String.class, String.class)
                .getDeclaringClass());
    }

    @Test
    void projectForkRetainsHitlApprovalMiddleware(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent");
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(parentRoot);
        Files.createDirectories(forkRoot);
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(parentRoot.toString());

        try (ToolRegistry fork = registry.forkForProject(forkRoot)) {
            String result = fork.executeTool("write_file",
                    "{\"path\":\"approved-in-fork.txt\",\"content\":\"fork-content\"}");

            assertFalse(result.startsWith("[HITL]"), result);
            assertEquals(1, stub.requestCount(), "隔离注册表仍应触发 HITL 审批");
            assertEquals("fork-content", Files.readString(forkRoot.resolve("approved-in-fork.txt")));
            assertFalse(Files.exists(parentRoot.resolve("approved-in-fork.txt")));
            assertInstanceOf(HitlToolRegistry.class, fork);
        }
    }

    @Test
    void skippedDecisionBlocksExecution(@TempDir Path tempDir) {
        Path target = tempDir.resolve("skipped.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.skip());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}");

        assertTrue(result.startsWith("[HITL]"), "结果应为 HITL 跳过消息: " + result);
        assertTrue(result.contains("跳过"));
        assertFalse(Files.exists(target));
    }

    @Test
    void approvedDecisionExecutesToolWithOriginalArgs(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("approved.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"approved\"}");

        assertFalse(result.startsWith("[HITL]"));
        assertTrue(Files.exists(target));
        assertEquals("approved", Files.readString(target));
    }

    @Test
    void editFileRequiresApprovalBeforeMutation(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("editable.txt"), "before");
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("review edit"));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        ToolOutput output = registry.executeToolOutput("edit_file",
                "{\"path\":\"editable.txt\",\"old_string\":\"before\",\"new_string\":\"after\"}");

        assertEquals(ToolStatus.REJECTED, output.status());
        assertEquals(ToolErrorCode.HITL_REJECTED, output.errorCode());
        assertEquals(1, stub.requestCount());
        assertEquals("before", Files.readString(tempDir.resolve("editable.txt")));
    }

    @Test
    void modifiedDecisionExecutesToolWithModifiedArgs(@TempDir Path tempDir) throws Exception {
        Path original = tempDir.resolve("original.txt");
        Path modified = tempDir.resolve("modified.txt");

        String modifiedArgs = "{\"path\":\"" + modified.toString().replace("\\", "\\\\") + "\",\"content\":\"modified!\"}";
        StubHandler stub = new StubHandler(req -> ApprovalResult.modify(modifiedArgs));
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + original.toString().replace("\\", "\\\\") + "\",\"content\":\"oops\"}");

        assertFalse(result.startsWith("[HITL]"), "MODIFIED 应实际执行工具: " + result);
        assertFalse(Files.exists(original), "原始路径不应被写入");
        assertTrue(Files.exists(modified), "修改后的路径应被写入");
        assertEquals("modified!", Files.readString(modified));
    }

    @Test
    void approvedAllDecisionExecutesTool(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("approved-all.txt");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAll());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("write_file",
                "{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"ok\"}");

        assertFalse(result.startsWith("[HITL]"));
        assertTrue(Files.exists(target));
    }

    /**
     * 放行缓存按任务边界收敛：任务内复用，任务边界后必须重新询问用户。
     */
    @Test
    void approvalAllIsReusedWithinTaskAndClearedAtTaskBoundary(@TempDir Path tempDir) throws Exception {
        TaskScopedApprovalHandler handler = new TaskScopedApprovalHandler();
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("write_file", "{\"path\":\"a.txt\",\"content\":\"1\"}");
        assertEquals(1, handler.requestCount(), "首次调用需要审批");

        registry.executeTool("write_file", "{\"path\":\"b.txt\",\"content\":\"2\"}");
        assertEquals(1, handler.requestCount(), "同一任务内应复用放行结果");

        handler.clearApprovedAll();
        registry.executeTool("write_file", "{\"path\":\"c.txt\",\"content\":\"3\"}");
        assertEquals(2, handler.requestCount(), "任务边界后必须重新询问用户，不得跨任务静默放行");
        assertTrue(Files.exists(tempDir.resolve("c.txt")));
    }

    /**
     * 任务级授权：范围内的写在执行管线里静默放行，不再逐次打扰用户。
     */
    @Test
    void taskGrantAllowsWorkspaceWriteWithoutPrompting(@TempDir Path tempDir) throws Exception {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("已授权范围内的写不应触发审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        String result = registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES,
                () -> registry.executeTool("write_file", "{\"path\":\"granted.txt\",\"content\":\"ok\"}"));

        assertFalse(result.startsWith("[HITL]"), result);
        assertEquals(0, stub.requestCount(), "命中授权后不应再询问用户");
        assertEquals("ok", Files.readString(tempDir.resolve("granted.txt")));
    }

    /**
     * 策略硬边界优先于授权：越界写直接拒绝，且不先弹一次注定会被拒的审批。
     */
    @Test
    void taskGrantNeverOverridesPolicyBoundaryAndDoesNotPromptFirst(@TempDir Path tempDir) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());
        Path outside = tempDir.getParent().resolve("outside-grant.txt");

        String result = registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES_AND_COMMANDS,
                () -> registry.executeTool("write_file",
                        "{\"path\":\"" + outside.toString().replace("\\", "\\\\")
                                + "\",\"content\":\"x\"}"));

        assertTrue(result.contains("[策略] 已拒绝"), result);
        assertFalse(Files.exists(outside), "越界写即使有授权也必须被策略拒绝");
        assertEquals(0, stub.requestCount(), "策略必然拒绝的操作不应先打扰用户");
    }

    /** 资源粒度授权：授权 glob 内的写静默放行，glob 外的写仍逐次确认。 */
    @Test
    void scopedTaskGrantOnlySilencesWritesInsideGrantedGlobs(@TempDir Path tempDir) throws Exception {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());
        Files.createDirectories(tempDir.resolve("src"));

        registry.runWithTaskGrant(new TaskGrant(java.util.List.of("src/**"), false), () -> {
            String granted = registry.executeTool("write_file",
                    "{\"path\":\"src/granted.txt\",\"content\":\"ok\"}");
            assertFalse(granted.startsWith("[HITL]"), granted);

            // glob 外的写会重新触发审批；批准后照常执行
            registry.executeTool("write_file", "{\"path\":\"other.txt\",\"content\":\"ok\"}");
            return null;
        });

        assertEquals(1, stub.requestCount(), "只有超出授权 glob 的写需要审批");
        assertEquals("ok", Files.readString(tempDir.resolve("src/granted.txt")));
        assertTrue(Files.exists(tempDir.resolve("other.txt")));
    }

    @Test
    void withoutTaskGrantWorkspaceWriteStillPrompts(@TempDir Path tempDir) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("write_file", "{\"path\":\"plain.txt\",\"content\":\"ok\"}");

        assertEquals(1, stub.requestCount(), "未授权时必须逐次确认");
    }

    @Test
    void approvedAllByServerDecisionExecutesMcpTool() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approveAllByServer());
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "navigate_page", args -> "navigated");

        String result = registry.executeTool("mcp__chrome-devtools__navigate_page",
                "{\"url\":\"https://example.com\"}");

        assertEquals("navigated", result);
        assertEquals(1, stub.requestCount());
    }

    @Test
    void approvedAllByServerCacheSkipsApprovalForSameMcpServer() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("server 维度已放行后不应再次触发审批");
        });
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(0, stub.requestCount());
    }

    @Test
    void destructiveMcpAnnotationForcesApprovalDespiteServerCache() {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        stub.approveServer("filesystem");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registerMcpTool(registry, "filesystem", "delete_file",
                new McpToolDescriptor.Annotations(false, true, false),
                args -> "deleted");

        String result = registry.executeTool("mcp__filesystem__delete_file", "{\"path\":\"a.txt\"}");

        assertEquals("deleted", result);
        assertEquals(1, stub.requestCount());
    }

    @Test
    void trustedReadOnlyMcpToolSkipsApproval() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("受信任只读 MCP 工具不应触发审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setMcpToolTrustPolicy("calendar", new McpToolTrustPolicy(true, Set.of(), Set.of()));
        registerMcpTool(registry, "calendar", "search_events",
                new McpToolDescriptor.Annotations(true, false, false),
                args -> "events");

        String result = registry.executeTool("mcp__calendar__search_events", "{}");

        assertEquals("events", result);
        assertFalse(registry.requiresApproval("mcp__calendar__search_events"));
        assertEquals(0, stub.requestCount());
    }

    @Test
    void sensitiveBrowserToolBypassesApprovedAllByServerCache(@TempDir Path tempDir) throws Exception {
        Path rules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(rules, "*://example.com/admin/*\n");
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        session.rememberNavigation("https://example.com/admin/users");
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        stub.approveServer("chrome-devtools");
        HitlToolRegistry registry = new HitlToolRegistry(stub);
        registry.setBrowserGuard(new BrowserGuard(session, new SensitivePagePolicy(rules)));
        registerMcpTool(registry, "chrome-devtools", "click", args -> "clicked");

        String result = registry.executeTool("mcp__chrome-devtools__click", "{\"uid\":\"1\"}");

        assertEquals("clicked", result);
        assertEquals(1, stub.requestCount());
        assertNotNull(stub.received.get(0).sensitiveNotice());
    }

    @Test
    void nonDangerousToolSkipsApprovalEvenWhenEnabled() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("non-dangerous 工具不应触发审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("list_dir", "{\"path\":\".\"}");
        assertFalse(result.startsWith("[HITL]"));
        assertEquals(0, stub.requestCount());
    }

    @Test
    void invalidDangerousToolArgumentsAreRejectedBeforeApproval() {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("参数非法时不应打扰 HITL 审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub);

        String result = registry.executeTool("write_file", "{\"path\":\"a.txt\",\"content\":123}");

        assertTrue(result.contains("工具参数校验失败"));
        assertTrue(result.contains("$.content must be string"));
        assertEquals(0, stub.requestCount());
    }

    @Test
    void serializesApprovalPromptsForParallelDangerousTools(@TempDir Path tempDir) {
        ConcurrentApprovalHandler handler = new ConcurrentApprovalHandler();
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(tempDir.toString());

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_a", "write_file",
                        "{\"path\":\"a.txt\",\"content\":\"a\"}"),
                new ToolRegistry.ToolInvocation("call_b", "write_file",
                        "{\"path\":\"b.txt\",\"content\":\"b\"}")));

        assertEquals(1, handler.maxConcurrent(), "审批输入必须串行化");
        assertEquals(2, handler.requestCount());
        assertTrue(results.stream().allMatch(result -> result.status() == ToolStatus.SUCCESS));
    }

    /** 复刻真实 handler 的「全部放行」任务级缓存语义。 */
    private static final class TaskScopedApprovalHandler implements HitlHandler {
        private final Set<String> approvedAllByTool = ConcurrentHashMap.newKeySet();
        private int requests;

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests++;
            approvedAllByTool.add(request.toolName());
            return ApprovalResult.approveAll();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        @Override
        public boolean isApprovedAllByTool(String toolName) {
            return toolName != null && approvedAllByTool.contains(toolName);
        }

        @Override
        public void clearApprovedAll() {
            approvedAllByTool.clear();
        }

        int requestCount() {
            return requests;
        }
    }

    /** 可预设决策结果的 HitlHandler stub。 */
    private static final class StubHandler implements HitlHandler {
        private final Function<ApprovalRequest, ApprovalResult> decision;
        private final List<ApprovalRequest> received = new ArrayList<>();
        private final List<String> approvedServers = new ArrayList<>();
        private boolean enabled = true;

        StubHandler(Function<ApprovalRequest, ApprovalResult> decision) {
            this.decision = decision;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            received.add(request);
            return decision.apply(request);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        int requestCount() {
            return received.size();
        }

        void approveServer(String serverName) {
            approvedServers.add(serverName);
        }

        @Override
        public boolean isApprovedAllByServer(String serverName) {
            return approvedServers.contains(serverName);
        }
    }

    private static final class ConcurrentApprovalHandler implements HitlHandler {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            int current = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            requests.incrementAndGet();
            try {
                Thread.sleep(100);
                return ApprovalResult.approve();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalResult.reject("审批线程被中断");
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        int maxConcurrent() {
            return maxConcurrent.get();
        }

        int requestCount() {
            return requests.get();
        }
    }

    private static void registerMcpTool(HitlToolRegistry registry, String serverName, String toolName,
                                        Function<String, String> invoker) {
        registerMcpTool(registry, serverName, toolName, null, invoker);
    }

    private static void registerMcpTool(HitlToolRegistry registry, String serverName, String toolName,
                                        McpToolDescriptor.Annotations annotations,
                                        Function<String, String> invoker) {
        registry.registerMcpTool(new McpToolDescriptor(
                serverName,
                toolName,
                McpToolDescriptor.namespaced(serverName, toolName),
                "test tool",
                JsonNodeFactory.instance.objectNode(),
                annotations
        ), invoker);
    }
}
