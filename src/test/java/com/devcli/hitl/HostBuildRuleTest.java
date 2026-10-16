package com.devcli.hitl;

import com.devcli.policy.PermissionMode;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.command.CommandExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HostBuildRuleTest {
    @TempDir Path root;

    @Test
    void explicitAllowPassesWhitelistedCommandsWithoutInteractiveApproval() {
        AtomicInteger approvals = new AtomicInteger();
        try (var tools = tools(approvals, List.of(), List.of(), List.of("execute_command(mvn test:*)"))) {
            tools.withPermissionMode(PermissionMode.DONT_ASK);
            var result = tools.executeToolOutput("execute_command", "{\"command\":\"mvn test -Pquick\"}");
            assertTrue(result.isSuccess(), result.text());
            assertEquals(0, approvals.get());
        }
    }

    @Test
    void broadAllowCannotApproveArbitraryCommandsOrShellChains() {
        AtomicInteger approvals = new AtomicInteger();
        try (var tools = tools(approvals, List.of(), List.of(), List.of("execute_command"))) {
            for (String command : List.of("echo unsafe", "git status & echo unsafe", "mvn test; echo unsafe")) {
                var result = tools.executeToolOutput("execute_command",
                        "{\"command\":\"" + command + "\"}");
                assertFalse(result.isSuccess(), result.text());
            }
            assertEquals(3, approvals.get());
        }
    }

    @Test
    void denyAndAskRulesStillPrecedeAllow() {
        AtomicInteger approvals = new AtomicInteger();
        try (var tools = tools(approvals, List.of("execute_command(mvn:*)"), List.of(), List.of("execute_command"))) {
            assertEquals(ToolErrorCode.POLICY_DENIED,
                    tools.executeToolOutput("execute_command", "{\"command\":\"mvn test\"}").errorCode());
            assertEquals(0, approvals.get());
        }
        try (var tools = tools(approvals, List.of(), List.of("execute_command(mvn:*)"), List.of("execute_command"))) {
            assertEquals(ToolErrorCode.HITL_REJECTED,
                    tools.executeToolOutput("execute_command", "{\"command\":\"mvn test\"}").errorCode());
            assertEquals(1, approvals.get());
        }
    }

    private HitlToolRegistry tools(AtomicInteger approvals, List<String> deny, List<String> ask, List<String> allow) {
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                approvals.incrementAndGet();
                return ApprovalResult.reject("test requires approval");
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        };
        var tools = new HitlToolRegistry(handler).withPermissionRules(PermissionRuleSet.parse(deny, ask, allow, List.of()));
        tools.setProjectPath(root.toString());
        tools.setCommandExecutionService(request -> CommandExecutionService.Result.completed(0, "test backend"));
        return tools;
    }
}
