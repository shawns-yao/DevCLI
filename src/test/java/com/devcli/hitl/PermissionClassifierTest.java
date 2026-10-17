package com.devcli.hitl;

import com.devcli.config.ProjectPermissionsStore;
import com.devcli.policy.PermissionMode;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolContract;
import com.devcli.tool.command.CommandExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离任务显式主机后端的分类器路径；仅替换分类器和进程，不执行外部命令。 */
class PermissionClassifierTest {
    @TempDir Path root;
    private static final String COMMAND = "{\"command\":\"mvn test\"}";
    private final AtomicInteger classified = new AtomicInteger();
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicInteger executed = new AtomicInteger();
    private PermissionClassifier.Request seen;
    private ApprovalRequest approval;
    private ApprovalResult answer = ApprovalResult.approve();
    private boolean bulk;

    private HitlToolRegistry tools(boolean trusted, PermissionClassifier classifier) throws IOException {
        var store = new ProjectPermissionsStore(root.resolve("user/project-permissions.json"));
        store.decideTrust(ProjectPermissionsStore.key(root), trusted);
        var handler = new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                asked.incrementAndGet(); approval = request; return answer;
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
            public boolean isApprovedAllByTool(String tool) { return bulk; }
        };
        var tools = new HitlToolRegistry(handler).withPermissionMode(PermissionMode.AUTO)
                .withPermissionClassifier(new PermissionClassifier() {
                    public Verdict classify(Request request) throws IOException {
                        classified.incrementAndGet(); seen = request; return classifier.classify(request);
                    }
                    public long decisionTimeoutMillis() { return classifier.decisionTimeoutMillis(); }
                });
        tools.setProjectPath(root.toString());
        tools.withProjectPermissions(store, ProjectPermissionsStore.key(root));
        tools.setExecutionEnvironment(new com.devcli.tool.environment.LocalExecutionEnvironment(request -> { executed.incrementAndGet(); return CommandExecutionService.Result.completed(0, "ok"); }));
        return tools;
    }

    private PermissionRuleSet rules(List<String> deny, List<String> ask, List<String> allow) {
        return PermissionRuleSet.parse(deny, ask, allow, List.of());
    }

    private com.devcli.tool.ToolOutput isolatedCommand(ToolRegistry tools) {
        return tools.runWithToolAccess(ToolContract.ToolAccessScope.ISOLATED_PROJECT,
                () -> tools.executeToolOutput("execute_command", COMMAND));
    }

    @Test void trustedIsolatedHostCommandUsesClassifierAndTrustedIntent() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("test"))) {
            tools.withTrustedIntentContext(() -> TrustedIntentContext.render(List.of(new com.devcli.llm.LlmClient.Message("user", "运行测试"))));
            assertTrue(isolatedCommand(tools).isSuccess());
            assertEquals(1, classified.get()); assertEquals(0, asked.get()); assertEquals(1, executed.get());
            assertEquals("execute_command", seen.toolName()); assertTrue(seen.intentContext().contains("运行测试"));
        }
    }

    @Test void narrowRulesShortCircuitInDefaultAndAuto() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.block("must not run"))) {
            tools.withPermissionRules(rules(List.of(), List.of(), List.of("execute_command(mvn test:*)")));
            for (var mode : List.of(PermissionMode.DEFAULT, PermissionMode.AUTO, PermissionMode.DONT_ASK)) {
                tools.withPermissionMode(mode);
                assertTrue(isolatedCommand(tools).isSuccess());
            }
            assertEquals(0, classified.get()); assertEquals(0, asked.get()); assertEquals(3, executed.get());
        }
    }

    @Test void broadCommandRulesDoNotShortCircuitAuto() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.block("no"))) {
            tools.withPermissionRules(rules(List.of(), List.of(), List.of("execute_command(*)", "execute_command(python:*)")));
            assertEquals(ToolErrorCode.POLICY_DENIED, isolatedCommand(tools).errorCode());
            assertEquals(1, classified.get()); assertEquals(0, executed.get());
            assertTrue(seen.rules().allow().isEmpty());
        }
    }

    @Test void hardDenyAndSoftDenyPrecedeAuto() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("must not run"))) {
            tools.withPermissionRules(rules(List.of("execute_command"), List.of(), List.of("execute_command(mvn test:*)")));
            assertEquals(ToolErrorCode.POLICY_DENIED, isolatedCommand(tools).errorCode());
            tools.withPermissionRules(rules(List.of(), List.of("execute_command"), List.of("execute_command(mvn test:*)")));
            assertTrue(isolatedCommand(tools).isSuccess());
            assertNull(approval.commandAllowRule());
            assertEquals(1, asked.get()); assertEquals(0, classified.get()); assertEquals(1, executed.get());
        }
    }

    @Test void untrustedProjectRequiresSingleApprovalDespiteRulesAndAuto() throws Exception {
        answer = ApprovalResult.approvePrefix();
        try (var tools = tools(false, r -> PermissionClassifier.Verdict.allow("must not run"))) {
            tools.withPermissionRules(rules(List.of(), List.of(), List.of("execute_command(mvn test:*)")));
            assertEquals(ToolErrorCode.HITL_REJECTED, isolatedCommand(tools).errorCode());
            assertNull(approval.commandAllowRule());
            assertEquals(1, asked.get()); assertEquals(0, classified.get()); assertEquals(0, executed.get());
        }
    }

    @Test void autoEditsDoNotCallClassifierAndProtectedPathsStayBlocked() throws Exception {
        try (var tools = tools(false, r -> PermissionClassifier.Verdict.block("must not run"))) {
            assertTrue(tools.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"old\"}").isSuccess());
            assertTrue(tools.executeToolOutput("edit_file", "{\"path\":\"a.txt\",\"old_string\":\"old\",\"new_string\":\"new\"}").isSuccess());
            assertFalse(tools.executeToolOutput("write_file", "{\"path\":\".devcli/agents/evil.md\",\"content\":\"x\"}").isSuccess());
            assertEquals("new", Files.readString(root.resolve("a.txt")));
            assertEquals(0, classified.get()); assertEquals(0, asked.get());
        }
    }

    @Test void deletionAndRollbackAlwaysAskDespiteAllowAndBypass() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("must not run"))) {
            tools.withPermissionMode(PermissionMode.BYPASS_PERMISSIONS);
            tools.withPermissionRules(rules(List.of(), List.of(), List.of("execute_command(rm:*)", "revert_turn")));
            tools.executeToolOutput("execute_command", "{\"command\":\"rm a.txt\"}");
            assertNull(approval.commandAllowRule());
            tools.executeToolOutput("revert_turn", "{\"offset\":1}");
            assertEquals(2, asked.get()); assertEquals(0, classified.get());
        }
    }

    @Test void threeConsecutiveBlocksExitAutoAndAskCurrentAction() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.block("no"))) {
            for (int i = 0; i < 2; i++) assertFalse(isolatedCommand(tools).isSuccess());
            assertTrue(isolatedCommand(tools).isSuccess());
            assertEquals(PermissionMode.DEFAULT, tools.currentPermissionMode());
            assertEquals(1, asked.get()); assertEquals(1, executed.get());
            assertTrue(approval.sensitiveNotice().contains("连续拦截 3"));
        }
    }

    @Test void twentyTotalBlocksExitAutoEvenWithSuccessfulDecisionsBetween() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        try (var tools = tools(true, r -> sequence.incrementAndGet() % 2 == 1
                ? PermissionClassifier.Verdict.block("no") : PermissionClassifier.Verdict.allow("yes"))) {
            for (int i = 0; i < 39; i++) isolatedCommand(tools);
            assertEquals(PermissionMode.DEFAULT, tools.currentPermissionMode());
            assertEquals(1, asked.get()); assertTrue(approval.sensitiveNotice().contains("累计拦截 20"));
        }
    }

    @Test void classifierFailureRemainsDeniedAndExitsAfterThreshold() throws Exception {
        try (var tools = tools(true, r -> { throw new IOException("offline"); })) {
            for (int i = 0; i < 3; i++) assertEquals(ToolErrorCode.POLICY_DENIED, isolatedCommand(tools).errorCode());
            assertEquals(PermissionMode.DEFAULT, tools.currentPermissionMode());
            assertEquals(0, executed.get()); assertEquals(0, asked.get());
        }
    }

    @Test void missingClassifierRejectsCommandsButEditsStillProceed() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("unused"))) {
            tools.withPermissionClassifier(null);
            assertEquals(ToolErrorCode.POLICY_DENIED, isolatedCommand(tools).errorCode());
            assertTrue(tools.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}").isSuccess());
            assertEquals(0, asked.get()); assertEquals(0, executed.get());
        }
    }

    @Test void incompleteIntentCannotReuseBulkApprovalForUndecidedHostCommand() throws Exception {
        bulk = true;
        answer = ApprovalResult.approveAll();
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("must not run"))) {
            tools.withTrustedIntentContext(() -> { throw new TrustedIntentContext.IncompleteContextException(); });
            assertEquals(ToolErrorCode.HITL_REJECTED, isolatedCommand(tools).errorCode());
            assertTrue(approval.hostExecution()); assertNull(approval.commandAllowRule());
            assertEquals(1, asked.get()); assertEquals(0, classified.get()); assertEquals(0, executed.get());
        }
    }

    @Test void insufficientBudgetDoesNotStartClassifier() throws Exception {
        PermissionClassifier classifier = new PermissionClassifier() {
            public Verdict classify(Request request) { fail("budget must be checked first"); return null; }
            public long decisionTimeoutMillis() { return 120_000; }
        };
        try (var tools = tools(true, classifier)) {
            var result = tools.runWithToolAccess(ToolContract.ToolAccessScope.ISOLATED_PROJECT,
                    () -> tools.executeTools(List.of(new ToolContract.ToolInvocation("limited", "execute_command", COMMAND)))).getFirst();
            assertEquals(ToolErrorCode.POLICY_DENIED, result.errorCode());
            assertEquals(0, classified.get()); assertEquals(0, executed.get());
        }
    }

    @Test void successfulClassificationResetsFailureCounterAndModeChangeResetsBlocks() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        try (var tools = tools(true, r -> {
            int index = sequence.incrementAndGet();
            if (index == 3) return PermissionClassifier.Verdict.allow("yes");
            throw new IOException("offline");
        })) {
            for (int i = 0; i < 5; i++) isolatedCommand(tools);
            assertEquals(PermissionMode.AUTO, tools.currentPermissionMode());
            tools.withPermissionClassifier(r -> PermissionClassifier.Verdict.block("no"));
            isolatedCommand(tools);
            isolatedCommand(tools);
            tools.withPermissionMode(PermissionMode.DEFAULT).withPermissionMode(PermissionMode.AUTO);
            isolatedCommand(tools);
            assertEquals(PermissionMode.AUTO, tools.currentPermissionMode());
        }
    }

    @Test void failureToPersistPrefixPreventsExecution() throws Exception {
        answer = ApprovalResult.approvePrefix();
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("unused"))) {
            tools.withPermissionMode(PermissionMode.DEFAULT);
            Files.delete(root.resolve("user/project-permissions.json"));
            Files.delete(root.resolve("user"));
            Files.writeString(root.resolve("user"), "blocked parent");
            assertEquals(ToolErrorCode.EXECUTION_FAILED, isolatedCommand(tools).errorCode());
            assertEquals(0, executed.get());
        }
    }

    @Test void forksShareBlockCounterAndTrust() throws Exception {
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.block("no"));
             ToolRegistry fork = tools.forkForProject(Files.createDirectory(root.resolve("fork")))) {
            isolatedCommand(tools);
            isolatedCommand(fork);
            isolatedCommand(tools);
            assertEquals(PermissionMode.DEFAULT, tools.currentPermissionMode());
            assertEquals(3, classified.get()); assertEquals(1, asked.get());
        }
    }

    @Test void rememberedPrefixSurvivesReopenAndDoesNotAuthorizeAnotherProject() throws Exception {
        answer = ApprovalResult.approvePrefix();
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.allow("yes"))) {
            tools.withPermissionMode(PermissionMode.DEFAULT);
            assertTrue(isolatedCommand(tools).isSuccess());
            assertEquals("execute_command(mvn test:*)", approval.commandAllowRule());
        }
        answer = ApprovalResult.reject("must not ask");
        var reopened = new ProjectPermissionsStore(root.resolve("user/project-permissions.json"));
        assertEquals(List.of("execute_command(mvn test:*)"), reopened.allowRules(ProjectPermissionsStore.key(root)));
        assertTrue(reopened.allowRules("another project").isEmpty());
        try (var tools = tools(true, r -> PermissionClassifier.Verdict.block("must not classify"))) {
            assertTrue(tools.runWithToolAccess(ToolContract.ToolAccessScope.ISOLATED_PROJECT,
                    () -> tools.executeCommandOutput("mvn test -Pquick")).isSuccess());
            assertEquals(1, asked.get()); assertEquals(0, classified.get());
            tools.setProjectPath(Files.createDirectory(root.resolve("other")).toString());
            assertFalse(tools.runWithToolAccess(ToolContract.ToolAccessScope.ISOLATED_PROJECT,
                    () -> tools.executeCommandOutput("mvn test -Pquick")).isSuccess());
            assertEquals(2, asked.get()); assertEquals(0, classified.get());
        }
    }
}
