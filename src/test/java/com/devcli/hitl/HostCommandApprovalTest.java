package com.devcli.hitl;

import com.devcli.concurrent.CancellationContext;
import com.devcli.policy.PermissionMode;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.policy.TaskGrant;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Public tool-entry tests using the real command service; only user decisions are scripted. */
class HostCommandApprovalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMAND = "echo approved > marker.txt";
    @TempDir Path root;

    private HitlToolRegistry registry(Handler handler) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        return registry;
    }

    private String args(String command) throws Exception {
        return JSON.writeValueAsString(Map.of("command", command));
    }

    private ToolOutput run(ToolRegistry registry) throws Exception {
        return registry.executeToolOutput("execute_command", args(COMMAND));
    }

    @Test
    void missingHandlerAndDisabledApprovalFailClosed() throws Exception {
        try (var registry = new ToolRegistry()) {
            registry.setProjectPath(root.toString());
            assertEquals(ToolErrorCode.HITL_REJECTED, run(registry).errorCode());
        }
        Handler handler = new Handler();
        handler.enabled = false;
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.HITL_REJECTED, run(registry).errorCode());
            assertEquals(0, handler.calls);
        }
        assertFalse(Files.exists(root.resolve("marker.txt")));
    }

    @Test
    void approvalDisplaysHostRiskAndExecutesExactlyOnce() throws Exception {
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput output = run(registry);
            assertTrue(output.isSuccess(), output.text());
            assertEquals(1, handler.calls);
            assertTrue(handler.request.hostExecution());
            assertFalse(handler.request.contentReview());
            assertTrue(handler.request.singleDecisionOnly());
            assertFalse(handler.request.redactionAllowed());
            assertTrue(handler.request.toDisplayText().contains("没有操作系统隔离"));
            assertEquals(COMMAND, JSON.readTree(handler.request.arguments()).path("command").asText());
            assertEquals(root.toString(),
                    JSON.readTree(handler.request.arguments()).path("working_directory").asText());
            assertTrue(Files.exists(root.resolve("marker.txt")));
        }
    }

    @Test
    void taskGrantAndBulkGrantNeverSuppressHostApproval() throws Exception {
        Handler handler = new Handler();
        handler.bulk = true;
        try (var registry = registry(handler)) {
            String arguments = args("echo approved");
            TaskGrant grant = new TaskGrant(List.of("**"), true);
            for (int i = 0; i < 2; i++) {
                ToolOutput output = registry.runWithTaskGrant(grant,
                        () -> registry.executeToolOutput("execute_command", arguments));
                assertTrue(output.isSuccess(), output.text());
            }
            assertEquals(2, handler.calls);
        }
    }

    @Test
    void everyNonSingleApprovalDecisionBlocksExecution() throws Exception {
        for (ApprovalResult.Decision decision : ApprovalResult.Decision.values()) {
            if (decision == ApprovalResult.Decision.APPROVED) continue;
            Handler handler = new Handler();
            handler.answer = ignored -> new ApprovalResult(decision, "{\"command\":\"echo changed\"}", "no");
            try (var registry = registry(handler)) {
                assertEquals(ToolErrorCode.HITL_REJECTED, run(registry).errorCode(), decision.name());
                assertEquals(1, handler.calls);
                assertFalse(Files.exists(root.resolve("marker.txt")));
            }
        }
    }

    @Test
    void nullDecisionDoesNotStartProcess() throws Exception {
        Handler handler = new Handler();
        handler.answer = ignored -> null;
        try (var registry = registry(handler)) {
            assertEquals(ToolErrorCode.HITL_REJECTED, run(registry).errorCode());
            assertFalse(Files.exists(root.resolve("marker.txt")));
        }
    }

    @Test
    void knownPolicyDenialHappensBeforeHostApproval() throws Exception {
        Handler handler = new Handler();
        try (var registry = registry(handler)) {
            ToolOutput result = registry.executeToolOutput("execute_command", args("sudo echo blocked"));
            assertEquals(ToolErrorCode.POLICY_DENIED, result.errorCode());
            assertEquals(0, handler.calls);
        }
    }

    @Test
    void cancellationDuringApprovalCannotStartProcess() throws Exception {
        Handler handler = new Handler();
        try (var runContext = CancellationContext.startRunContext(root); var registry = registry(handler)) {
            handler.answer = ignored -> {
                runContext.cancellationToken().cancel();
                return ApprovalResult.approve();
            };
            String arguments = args(COMMAND);
            ToolOutput result = registry.executeToolOutput("execute_command", arguments);
            assertEquals(ToolStatus.CANCELLED, result.status(), result.text());
            assertFalse(Files.exists(root.resolve("marker.txt")));
        }
    }

    @Test
    void projectChangeWhilePromptingDoesNotRetargetApprovedCommand() throws Exception {
        Handler handler = new Handler();
        Path other = Files.createDirectories(root.resolve("other"));
        try (var registry = registry(handler)) {
            handler.answer = ignored -> {
                registry.setProjectPath(other.toString());
                return ApprovalResult.approve();
            };
            assertTrue(run(registry).isSuccess());
            assertTrue(Files.exists(root.resolve("marker.txt")));
            assertFalse(Files.exists(other.resolve("marker.txt")));
        }
    }

    @Test
    void isolatedHostRestrictedModeAlsoRequiresSingleApproval() throws Exception {
        String old = System.getProperty("devcli.command.sandbox.mode");
        System.setProperty("devcli.command.sandbox.mode", "HOST_RESTRICTED");
        Handler handler = new Handler();
        handler.answer = ignored -> ApprovalResult.reject("no");
        try (var registry = registry(handler)) {
            String arguments = args("git status --short");
            ToolOutput result = registry.runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> registry.executeToolOutput("execute_command", arguments));
            assertEquals(ToolErrorCode.HITL_REJECTED, result.errorCode());
            assertEquals(1, handler.calls);
            assertTrue(handler.request.hostExecution());
            String forbidden = args(COMMAND);
            ToolOutput denied = registry.runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> registry.executeToolOutput("execute_command", forbidden));
            assertEquals(ToolErrorCode.POLICY_DENIED, denied.errorCode());
            assertEquals(1, handler.calls, "Backend policy must reject before another approval prompt");
        } finally {
            if (old == null) System.clearProperty("devcli.command.sandbox.mode");
            else System.setProperty("devcli.command.sandbox.mode", old);
        }
    }

    @Test
    void hostApprovalDisplaysLongCommandTailWithoutTruncation() throws Exception {
        String command = "echo " + "x".repeat(180) + " ; echo visible_tail";
        ApprovalRequest request = ApprovalRequest.hostCommand(args(command), "test");
        assertTrue(request.toDisplayText().replaceAll("[│\\s]", "").contains("visible_tail"));
        assertFalse(request.toDisplayText().contains("字符)"));
    }

    @Test
    void terminalControlCharactersAreDisplayedAsEscapes() throws Exception {
        ApprovalRequest request = ApprovalRequest.hostCommand(args("echo \u001b[2Jvisible"), "test");
        assertFalse(request.toDisplayText().contains("\u001b"));
        assertTrue(request.toDisplayText().contains("\\u001B"));
    }

    @Test
    void longWorkingDirectoryRemainsVisibleInApproval() throws Exception {
        String directory = "folder/".repeat(30) + "destination";
        String arguments = JSON.writeValueAsString(Map.of("command", "echo ok", "working_directory", directory));
        ApprovalRequest request = ApprovalRequest.hostCommand(arguments, "test");
        assertTrue(request.toDisplayText().replaceAll("[│\\s]", "").contains(directory));
    }

    private HitlToolRegistry registry(Handler handler, PermissionMode mode,
                                     List<String> deny, List<String> allow) {
        HitlToolRegistry registry = new HitlToolRegistry(handler)
                .withPermissionRules(PermissionRuleSet.parse(deny, List.of(), allow))
                .withPermissionMode(mode);
        registry.setProjectPath(root.toString());
        return registry;
    }

    @Test
    void denyRuleBlocksHostCommandBeforeApproval() throws Exception {
        // 主机命令同样经过规则层：否则一条 deny 规则会挂在「命令走哪条后端」这个
        // 与规则无关的分支后面而静默失效
        Handler handler = new Handler();
        try (var registry = registry(handler, PermissionMode.DEFAULT,
                List.of("execute_command(echo:*)"), List.of())) {
            ToolOutput output = run(registry);

            assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
            assertTrue(output.text().contains("[规则]"), output.text());
            assertEquals(0, handler.calls, "规则层拒绝不该先弹一次注定被拒的审批");
            assertFalse(Files.exists(root.resolve("marker.txt")));
        }
    }

    @Test
    void dontAskModeDeniesHostCommandInsteadOfPrompting() throws Exception {
        Handler handler = new Handler();
        try (var registry = registry(handler, PermissionMode.DONT_ASK, List.of(), List.of())) {
            ToolOutput output = run(registry);

            assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
            assertTrue(output.text().contains("[模式]"), output.text());
            assertEquals(0, handler.calls, "dontAsk 不询问");
            assertFalse(Files.exists(root.resolve("marker.txt")));
        }
    }

    @Test
    void allowRuleAndBypassModeNeverSuppressHostApproval() throws Exception {
        // 主机单次人工确认不可被放宽类判定免除：allow 规则与 bypassPermissions 都不放行它。
        // 这是刻意比 WorkBuddy 更严的一处——代价是给 execute_command 写 allow 规则在主机路径上不生效
        Handler handler = new Handler();
        try (var registry = registry(handler, PermissionMode.BYPASS_PERMISSIONS,
                List.of(), List.of("execute_command(**)"))) {
            ToolOutput output = run(registry);

            assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
            assertEquals(1, handler.calls, "主机命令仍必须单次人工确认");
            assertTrue(Files.exists(root.resolve("marker.txt")));
        }
    }

    private static final class Handler implements HitlHandler {
        boolean enabled = true;
        boolean bulk;
        int calls;
        ApprovalRequest request;
        Function<ApprovalRequest, ApprovalResult> answer = ignored -> ApprovalResult.approve();
        public ApprovalResult requestApproval(ApprovalRequest request) {
            this.request = request;
            calls++;
            return answer.apply(request);
        }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isApprovedAllByTool(String name) { return bulk; }
    }
}
