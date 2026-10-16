package com.devcli.agent;

import static com.devcli.tool.ToolContract.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmErrorCode;
import com.devcli.llm.LlmException;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {

    @BeforeAll
    static void redirectCheckpointDir() throws IOException {
        // run() 会写 orchestration checkpoint；测试重定向到临时目录，避免污染用户主目录
        System.setProperty("devcli.checkpoint.dir",
                Files.createTempDirectory("devcli-test-checkpoints").toString());
        System.setProperty("devcli.team.plan.review.enabled", "false");
    }

    @AfterAll
    static void clearCheckpointDirProperty() {
        System.clearProperty("devcli.checkpoint.dir");
        System.clearProperty("devcli.team.plan.review.enabled");
    }

    @Test
    void resolveWorkerCountDefaultsToTwo() {
        String prev = System.getProperty("devcli.team.workers");
        System.clearProperty("devcli.team.workers");
        try {
            assertEquals(AgentOrchestrator.DEFAULT_WORKER_COUNT, AgentOrchestrator.resolveWorkerCount());
        } finally {
            if (prev != null) System.setProperty("devcli.team.workers", prev);
        }
    }

    @Test
    void resolveWorkerCountReadsSystemProperty() {
        String prev = System.getProperty("devcli.team.workers");
        System.setProperty("devcli.team.workers", "4");
        try {
            assertEquals(4, AgentOrchestrator.resolveWorkerCount());
        } finally {
            if (prev == null) System.clearProperty("devcli.team.workers");
            else System.setProperty("devcli.team.workers", prev);
        }
    }

    @Test
    void resolveWorkerCountRejectsOutOfRangeAndGarbage() {
        String prev = System.getProperty("devcli.team.workers");
        try {
            System.setProperty("devcli.team.workers", "999");
            assertThrows(IllegalArgumentException.class, AgentOrchestrator::resolveWorkerCount);
            System.setProperty("devcli.team.workers", "0");
            assertThrows(IllegalArgumentException.class, AgentOrchestrator::resolveWorkerCount);
            System.setProperty("devcli.team.workers", "abc");
            assertThrows(IllegalArgumentException.class, AgentOrchestrator::resolveWorkerCount);
        } finally {
            if (prev == null) System.clearProperty("devcli.team.workers");
            else System.setProperty("devcli.team.workers", prev);
        }
    }

    @Test
    void additionalEventSinkShouldPropagateToExistingAgentsImmediately() throws Exception {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        com.devcli.event.RunEventSink sink = event -> { };

        orchestrator.setAdditionalEventSink(sink);

        assertSame(sink, additionalEventSink(field(orchestrator, "planner")));
        assertSame(sink, additionalEventSink(field(orchestrator, "reviewer")));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object additionalEventSink(Object subAgent) throws Exception {
        return field(subAgent, "additionalEventSink");
    }

    @Test
    void resolvePlannerRepairAttemptsRejectsInvalidExplicitValues() {
        String previous = System.getProperty("devcli.team.planner.repair.max.attempts");
        try {
            System.setProperty("devcli.team.planner.repair.max.attempts", "1");
            assertEquals(1, TeamPlannerProtocol.resolveRepairAttempts());
            System.setProperty("devcli.team.planner.repair.max.attempts", "99");
            assertThrows(IllegalArgumentException.class, TeamPlannerProtocol::resolveRepairAttempts);
            System.setProperty("devcli.team.planner.repair.max.attempts", "invalid");
            assertThrows(IllegalArgumentException.class, TeamPlannerProtocol::resolveRepairAttempts);
        } finally {
            if (previous == null) {
                System.clearProperty("devcli.team.planner.repair.max.attempts");
            } else {
                System.setProperty("devcli.team.planner.repair.max.attempts", previous);
            }
        }
    }

    @Test
    void shouldParseSimplePlan() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "读取文件",
                    "steps": [
                        {
                            "id": "step_1",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("读取 pom.xml", steps.get(0).description());
    }

    @Test
    void shouldParseMultiStepPlanWithDependencies() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "创建并验证项目",
                    "steps": [
                        {
                            "id": "s1",
                            "description": "创建项目",
                            "type": "COMMAND",
                            "dependencies": []
                        },
                        {
                            "id": "s2",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": ["s1"]
                        },
                        {
                            "id": "s3",
                            "description": "验证结构",
                            "type": "VERIFICATION",
                            "dependencies": ["s2"]
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(3, steps.size());

        // 验证重编号
        assertEquals("step_1", steps.get(0).id());
        assertEquals("step_2", steps.get(1).id());
        assertEquals("step_3", steps.get(2).id());

        // 验证依赖被正确映射
        assertTrue(steps.get(0).dependencies().isEmpty());
        assertEquals(List.of("step_1"), steps.get(1).dependencies());
        assertEquals(List.of("step_2"), steps.get(2).dependencies());
    }

    @Test
    void shouldParsePlanWithMarkdownCodeBlock() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                ```json
                {
                    "summary": "简单任务",
                    "steps": [
                        {
                            "id": "t1",
                            "description": "执行命令",
                            "type": "COMMAND",
                            "dependencies": []
                        }
                    ]
                }
                ```
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
    }

    @Test
    void workspaceDiscoveryValidationShouldDependOnUserDeliveryIntent() {
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending(
                        "step_1", "检查空工作区目录是否存在", "ANALYSIS", List.of()));

        assertNull(TeamPlannerProtocol.validate(
                steps, "检查空工作区目录是否存在",
                AgentOrchestrator.ExecutionStep::id,
                AgentOrchestrator.ExecutionStep::description,
                AgentOrchestrator.ExecutionStep::dependencies));
        assertNotNull(TeamPlannerProtocol.validate(
                steps, "在空工作区实现核心功能",
                AgentOrchestrator.ExecutionStep::id,
                AgentOrchestrator.ExecutionStep::description,
                AgentOrchestrator.ExecutionStep::dependencies));
    }

    @Test
    void reviewerFailureDegradationShouldRequireHardCheckForEveryStep() {
        assertFalse(ReviewCoordinator.canDegradeReviewerFailure(true, false));
        assertTrue(ReviewCoordinator.canDegradeReviewerFailure(true, true));
        assertFalse(ReviewCoordinator.canDegradeReviewerFailure(false, true));
    }

    @Test
    void shouldParsePlanJsonSurroundedByExplanationText() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                下面是执行计划：
                {
                  "summary": "实现功能",
                  "steps": [
                    {"id": "s1", "description": "实现核心功能", "type": "FILE_WRITE", "dependencies": []}
                  ]
                }
                请按计划执行。
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);

        assertEquals(1, steps.size());
        assertEquals("实现核心功能", steps.get(0).description());
    }

    @Test
    void shouldRejectUnknownPlannerStepType() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan("""
                {
                  "steps": [
                    {"id":"s1","description":"执行任意动作","type":"SHELL","dependencies":[]}
                  ]
                }
                """);

        assertTrue(steps.isEmpty());
    }

    @Test
    void shouldRejectReadOnlyDiscoveryStepMislabeledAsFileWrite() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan("""
                {
                  "steps": [
                    {"id":"s1","description":"读取 Node.java 并检查现有实现","type":"FILE_WRITE","dependencies":[]}
                  ]
                }
                """);

        assertTrue(steps.isEmpty());
    }

    @Test
    void shouldRejectAcceptanceCriteriaWithoutVerificationContract() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id": "AC-01",
                      "description": "默认模型可以完成请求",
                      "test_signal": "请求成功"
                    }
                  ],
                  "steps": [
                    {"id":"s1","description":"实现功能","type":"ANALYSIS","dependencies":[]}
                  ]
                }
                """);

        AcceptanceCriteriaPreflight.Report report = orchestrator.acceptanceCriteriaPreflight();

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("verification_method"));
    }

    @Test
    void shouldParseExecutableToolAcceptanceCriterion() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id": "AC-01",
                      "description": "默认模型可以完成请求",
                      "verification_method": "TOOL",
                      "verifier": "execute_command",
                      "test_signal": "命令退出码为 0",
                      "applies_to": ["FINAL"]
                    }
                  ],
                  "steps": [
                    {"id":"s1","description":"实现功能","type":"ANALYSIS","dependencies":[]}
                  ]
                }
                """);

        AcceptanceCriteriaPreflight.Report report = orchestrator.acceptanceCriteriaPreflight();

        assertTrue(report.executable(), report.describeIssues());
        assertFalse(report.requiresHumanReview());
    }

    @Test
    void shouldRejectMutationToolAsAcceptanceVerifier() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id":"AC-01",
                      "description":"文件内容正确",
                      "verification_method":"TOOL",
                      "verifier":"write_file",
                      "test_signal":"文件存在",
                      "applies_to":["FINAL"]
                    }
                  ],
                  "steps":[
                    {"id":"s1","description":"实现功能","type":"ANALYSIS","dependencies":[]}
                  ]
                }
                """);

        AcceptanceCriteriaPreflight.Report report = orchestrator.acceptanceCriteriaPreflight();

        assertFalse(report.executable());
        assertTrue(report.describeIssues().contains("write_file"));
    }

    @Test
    void shouldKeepHumanCriteriaVisibleAsDeliveryObligations() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        orchestrator.parsePlan(humanReviewPlanJson());

        String summary = orchestrator.pendingHumanAcceptanceSummary();

        assertTrue(summary.contains("待人工验收"), summary);
        assertTrue(summary.contains("AC-01"), summary);
        assertTrue(summary.contains("用户检查最终终端展示"), summary);
    }

    @Test
    void shouldRequireReviewerToUseDeclaredToolVerifier() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id":"AC-01",
                      "description":"项目可以编译",
                      "verification_method":"TOOL",
                      "verifier":"execute_command",
                      "test_signal":"命令退出码为 0",
                      "applies_to":["FINAL"]
                    }
                  ],
                  "steps":[
                    {"id":"s1","description":"实现功能","type":"ANALYSIS","dependencies":[]}
                  ]
                }
                """);

        assertTrue(orchestrator.missingDeclaredVerifierTools(List.of("read_file"))
                .contains("execute_command"));
        assertTrue(orchestrator.missingDeclaredVerifierTools(List.of("execute_command")).isEmpty());
    }

    @Test
    void shouldNormalizeCriterionTargetsAndInjectOnlyRelevantStepCriteria() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id":"AC-IMPL",
                      "description":"实现结果存在",
                      "verification_method":"TOOL",
                      "verifier":"list_dir",
                      "test_signal":"目录中存在实现产物",
                      "applies_to":["impl"]
                    },
                    {
                      "id":"AC-VERIFY",
                      "description":"验证结果通过",
                      "verification_method":"TOOL",
                      "verifier":"execute_command",
                      "test_signal":"命令退出码为 0",
                      "applies_to":["verify"]
                    }
                  ],
                  "steps":[
                    {"id":"impl","description":"实现功能","type":"ANALYSIS","dependencies":[]},
                    {"id":"verify","description":"验证功能","type":"VERIFICATION","dependencies":["impl"]}
                  ]
                }
                """);

        assertEquals(List.of("AC-IMPL"), orchestrator.acceptanceCriteriaForStep(steps.get(0)).stream()
                .map(AcceptanceCriterion::id).toList());
        assertEquals(List.of("AC-VERIFY"), orchestrator.acceptanceCriteriaForStep(steps.get(1)).stream()
                .map(AcceptanceCriterion::id).toList());

        AgentOrchestrator.ExecutionStep finalStep = orchestrator.appendFinalIntegrationStep(steps).get(2);
        assertEquals(List.of("AC-IMPL", "AC-VERIFY"),
                orchestrator.acceptanceCriteriaForStep(finalStep).stream()
                        .map(AcceptanceCriterion::id).toList());
    }

    @Test
    void shouldEscalateWithRetryEvidenceWhenAllAutomaticRecoveryIsExhausted() {
        AgentOrchestrator.ExecutionStep failed = AgentOrchestrator.ExecutionStep
                .pending("step_1", "实现支付补偿", "ANALYSIS", List.of())
                .withFailed("AC-03 未满足：退款状态错误");

        String escalation = AgentOrchestrator.formatFailureEscalation(
                List.of(failed), Map.of("step_1", 2), Map.of("step_1", 1), "orch-pay");

        assertTrue(escalation.contains("需要人工介入"), escalation);
        assertTrue(escalation.contains("step_1"), escalation);
        assertTrue(escalation.contains("Reviewer 重试 2/2"), escalation);
        assertTrue(escalation.contains("原位重做 1/1"), escalation);
        assertTrue(escalation.contains("orch-pay"), escalation);
        assertTrue(escalation.contains("失败分类：校验错误"), escalation);
        assertTrue(escalation.contains("操作建议："), escalation);
        assertTrue(escalation.contains("/plan resume orch-pay"), escalation);
        assertTrue(escalation.contains("人工接手："), escalation);
        assertTrue(escalation.contains("接受部分结果："), escalation);
        assertTrue(escalation.contains("回滚："), escalation);
    }

    @Test
    void shouldBlockExecutionWhenAcceptanceCriteriaRemainUnverifiable(@TempDir Path tempDir) {
        String previous = System.getProperty("devcli.team.planner.repair.max.attempts");
        System.setProperty("devcli.team.planner.repair.max.attempts", "0");
        AtomicInteger workerCalls = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "acceptance_criteria": [
                            {
                              "id": "AC-01",
                              "description": "结果满足用户目标",
                              "test_signal": "结果正确"
                            }
                          ],
                          "steps": [
                            {"id":"s1","description":"完成任务","type":"ANALYSIS","dependencies":[]}
                          ]
                        }
                        """);
            }
            workerCalls.incrementAndGet();
            return response("worker result");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);

            String result = orchestrator.run("完成一个需要验收的任务");

            assertTrue(result.contains("规划失败"), result);
            assertTrue(result.contains("验收标准不可执行"), result);
            assertEquals(0, workerCalls.get());
        } finally {
            if (previous == null) {
                System.clearProperty("devcli.team.planner.repair.max.attempts");
            } else {
                System.setProperty("devcli.team.planner.repair.max.attempts", previous);
            }
        }
    }

    @Test
    void shouldRequireExplicitReviewBeforeExecutingHumanCriteria(@TempDir Path tempDir) {
        AtomicInteger workerCalls = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "acceptance_criteria": [
                            {
                              "id": "AC-01",
                              "description": "终端信息层级清晰",
                              "verification_method": "HUMAN",
                              "verifier": "用户检查最终终端展示的信息层级",
                              "test_signal": "用户确认不存在歧义",
                              "applies_to": ["FINAL"]
                            }
                          ],
                          "steps": [
                            {"id":"s1","description":"完成终端展示","type":"ANALYSIS","dependencies":[]}
                          ]
                        }
                        """);
            }
            workerCalls.incrementAndGet();
            return response("worker result");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);

            String result = orchestrator.run("调整终端展示");

            assertTrue(result.contains("需要人工确认"), result);
            assertEquals(0, workerCalls.get());
        }
    }

    @Test
    void shouldExecuteHumanCriteriaAfterExplicitPlanApproval(@TempDir Path tempDir) {
        AtomicInteger workerCalls = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response(humanReviewPlanJson());
            }
            workerCalls.incrementAndGet();
            return response("worker result");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);
            orchestrator.setPlanReviewHandler(request -> AgentOrchestrator.TeamPlanReviewDecision.execute());

            orchestrator.run("调整终端展示");

            assertTrue(workerCalls.get() > 0);
        }
    }

    @Test
    void shouldReplanFromPreExecutionReviewFeedback(@TempDir Path tempDir) {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger reviewCalls = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                plannerCalls.incrementAndGet();
                return response(humanReviewPlanJson());
            }
            workerCalls.incrementAndGet();
            return response("worker result");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);
            orchestrator.setPlanReviewHandler(request -> reviewCalls.getAndIncrement() == 0
                    ? AgentOrchestrator.TeamPlanReviewDecision.supplement("补充键盘操作验收")
                    : AgentOrchestrator.TeamPlanReviewDecision.cancel("测试结束"));

            String result = orchestrator.run("调整终端展示");

            assertEquals(2, plannerCalls.get());
            assertEquals(0, workerCalls.get());
            assertTrue(result.contains("测试结束"), result);
        }
    }

    @Test
    void semanticPlanReviewRejectionIsAdvisoryBeforeHumanApproval(@TempDir Path tempDir) {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger semanticReviewCalls = new AtomicInteger();
        AtomicReference<AgentOrchestrator.TeamPlanReviewRequest> approvalRequest = new AtomicReference<>();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划") || body.contains("上次规划输出无法解析")) {
                plannerCalls.incrementAndGet();
                return response("""
                        {"summary":"交付计划","acceptance_criteria":[{
                          "id":"AC-01","description":"功能成果可观察","verification_method":"TOOL",
                          "verifier":"list_dir","test_signal":"工具返回成功","severity":"high","applies_to":["s1"]
                        }],"steps":[
                          {"id":"s1","description":"实现完整功能","type":"ANALYSIS","dependencies":[]}
                        ]}
                        """);
            }
            if (body.contains("计划语义评审")) {
                semanticReviewCalls.incrementAndGet();
                return response("""
                        {"approved":false,"summary":"遗漏原始目标","requirement_coverage":[
                          {"requirement":"交付功能","status":"missing","step_ids":["step_1"],"criterion_ids":["AC-01"]}
                        ],"criteria_reviews":[
                          {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"list_dir 可验证"}
                        ],"issues":[
                          {"type":"missing_requirement","severity":"high","requirement":"交付功能","description":"计划未覆盖完整目标","suggested_fix":"补齐实现范围"}
                        ]}
                        """);
            }
            return response("unexpected worker call");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);
            orchestrator.setPlanSemanticReviewEnabled(true);
            orchestrator.setPlanReviewHandler(request -> {
                approvalRequest.set(request);
                return AgentOrchestrator.TeamPlanReviewDecision.cancel("测试结束");
            });

            String result = orchestrator.run("交付完整功能");

            assertEquals(1, plannerCalls.get());
            assertEquals(1, semanticReviewCalls.get());
            assertTrue(result.contains("测试结束"), result);
            assertNotNull(approvalRequest.get());
            assertTrue(approvalRequest.get().semanticReviewExecuted());
            assertFalse(approvalRequest.get().semanticReviewApproved());
            assertTrue(approvalRequest.get().semanticReviewSummary().contains("遗漏原始目标"));
        }
    }

    @Test
    void semanticPlanReviewUsesIndependentReviewerClient(@TempDir Path tempDir) {
        AtomicInteger primarySemanticCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        LlmClient primary = new DispatchingStubGLMClient(body -> {
            if (body.contains("计划语义评审")) {
                primarySemanticCalls.incrementAndGet();
            }
            return response("""
                    {"summary":"交付计划","acceptance_criteria":[{
                      "id":"AC-01","description":"功能可验证","test_signal":"目录存在",
                      "verification_method":"TOOL","verifier":"list_dir","severity":"high","applies_to":["s1"]
                    }],"steps":[
                      {"id":"s1","description":"实现完整功能","type":"ANALYSIS","dependencies":[]}
                    ]}
                    """);
        });
        LlmClient independentReviewer = new DispatchingStubGLMClient(body -> {
            reviewerCalls.incrementAndGet();
            return response("""
                    {"approved":true,"summary":"独立评审通过","requirement_coverage":[
                      {"requirement":"实现完整功能","status":"covered","step_ids":["step_1"],"criterion_ids":["AC-01"]}
                    ],"criteria_reviews":[
                      {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"list_dir 验证"}
                    ],"counterexamples":[
                      {"criterion_id":"AC-01","input":"目标目录不存在","expected_failure_signal":"list_dir 返回不存在","step_ids":["step_1"]}
                    ],"issues":[]}
                    """);
        });

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    primary, independentReviewer, isolatedToolRegistry(tempDir), mm, System.out);
            orchestrator.setPlanSemanticReviewEnabled(true);
            orchestrator.setPlanReviewHandler(request ->
                    AgentOrchestrator.TeamPlanReviewDecision.cancel("测试结束"));

            String result = orchestrator.run("实现完整功能");

            assertTrue(result.contains("测试结束"), result);
            assertEquals(1, reviewerCalls.get());
            assertEquals(0, primarySemanticCalls.get());
        }
    }

    @Test
    void shouldRepairMalformedPlanReviewerProtocolOnce(@TempDir Path tempDir) {
        AtomicInteger reviewCalls = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("计划语义评审")) {
                return response(reviewCalls.incrementAndGet() == 1 ? "not-json" : """
                        {"approved":true,"summary":"协议修复后通过","requirement_coverage":[
                          {"requirement":"实现功能","status":"covered","step_ids":["step_1"],"criterion_ids":["AC-01"]}
                        ],"criteria_reviews":[
                          {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"list_dir 可验证"}
                        ],"counterexamples":[
                          {"criterion_id":"AC-01","input":"目标目录不存在","expected_failure_signal":"list_dir 返回不存在","step_ids":["step_1"]}
                        ],"issues":[]}
                        """);
            }
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {"acceptance_criteria":[{
                          "id":"AC-01","description":"功能成果可观察","verification_method":"TOOL",
                          "verifier":"list_dir","test_signal":"工具返回成功","severity":"high","applies_to":["s1"]
                        }],"steps":[
                          {"id":"s1","description":"实现功能","type":"ANALYSIS","dependencies":[]}
                        ]}
                        """);
            }
            return response("unexpected worker call");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);
            orchestrator.setPlanSemanticReviewEnabled(true);
            orchestrator.setPlanReviewHandler(request ->
                    AgentOrchestrator.TeamPlanReviewDecision.cancel("协议修复完成"));

            String result = orchestrator.run("实现功能");

            assertEquals(2, reviewCalls.get());
            assertTrue(result.contains("协议修复完成"), result);
        }
    }

    @Test
    void malformedPlanReviewerProtocolIsAdvisoryAfterBoundedRepair(@TempDir Path tempDir) {
        AtomicInteger workerCalls = new AtomicInteger();
        AtomicInteger reviewCalls = new AtomicInteger();
        AtomicReference<AgentOrchestrator.TeamPlanReviewRequest> approvalRequest = new AtomicReference<>();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("计划语义评审")) {
                reviewCalls.incrementAndGet();
                return response("not-json");
            }
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {"acceptance_criteria":[{
                          "id":"AC-01","description":"功能成果可观察","verification_method":"TOOL",
                          "verifier":"list_dir","test_signal":"工具返回成功","severity":"high","applies_to":["s1"]
                        }],"steps":[
                          {"id":"s1","description":"实现功能","type":"ANALYSIS","dependencies":[]}
                        ]}
                        """);
            }
            workerCalls.incrementAndGet();
            return response("unexpected worker call");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);
            orchestrator.setPlanSemanticReviewEnabled(true);
            orchestrator.setPlanReviewHandler(request -> {
                approvalRequest.set(request);
                return AgentOrchestrator.TeamPlanReviewDecision.cancel("建议已展示");
            });

            String result = orchestrator.run("实现功能");

            assertTrue(result.contains("建议已展示"), result);
            assertEquals(2, reviewCalls.get());
            assertEquals(0, workerCalls.get());
            assertNotNull(approvalRequest.get());
            assertFalse(approvalRequest.get().semanticReviewApproved());
            assertTrue(approvalRequest.get().semanticReviewSummary().contains("计划评审输出不是有效 JSON"));
        }
    }

    @Test
    void dependencyPreviewShouldKeepFullAcceptanceCriteriaArray() throws Exception {
        String longCriteria = "A".repeat(900) + "CRITERION_END_SENTINEL";
        String result = "head\n" + "x".repeat(2_500) + """
                {
                  "acceptance_criteria": [
                    {"id":"c1","description":"
                """ + longCriteria + """
                    ","severity":"critical"}
                  ]
                }
                tail
                """ + "z".repeat(3_000) + """
                """;

        String preview = invokePreviewDependencyResult(result);

        assertTrue(preview.contains("CRITERION_END_SENTINEL"), preview);
        assertTrue(preview.contains("\"severity\":\"critical\"") || preview.contains("\"severity\":\"critical\""), preview);
    }

    @Test
    void shouldParsePlanWithTasksField() {
        // 兼容 "tasks" 字段（Plan-and-Execute 的格式）
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "用 tasks 字段",
                    "tasks": [
                        {
                            "id": "task_1",
                            "description": "第一步",
                            "type": "COMMAND",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("第一步", steps.get(0).description());
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        assertTrue(orchestrator.parsePlan("").isEmpty());
        assertTrue(orchestrator.parsePlan("not json").isEmpty());
        assertTrue(orchestrator.parsePlan("{}").isEmpty());
        assertTrue(orchestrator.parsePlan("{\"steps\": []}").isEmpty());
    }

    @Test
    void shouldCoarsenOverSplitPlan() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                  "summary": "过度拆分",
                  "steps": [
                    {"id": "s1", "description": "分析需求", "type": "ANALYSIS", "dependencies": []},
                    {"id": "s2", "description": "实现模型", "type": "FILE_WRITE", "dependencies": ["s1"]},
                    {"id": "s3", "description": "实现解析", "type": "FILE_WRITE", "dependencies": ["s2"]},
                    {"id": "s4", "description": "实现服务", "type": "FILE_WRITE", "dependencies": ["s3"]},
                    {"id": "s5", "description": "实现入口", "type": "FILE_WRITE", "dependencies": ["s4"]},
                    {"id": "s6", "description": "运行验证", "type": "VERIFICATION", "dependencies": ["s5"]}
                  ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);

        assertEquals(3, steps.size());
        assertTrue(steps.get(1).description().contains("实现模型"));
        assertTrue(steps.get(1).description().contains("实现入口"));
        assertEquals(List.of("step_1"), steps.get(1).dependencies());
        assertEquals(List.of("step_2"), steps.get(2).dependencies());
    }

    @Test
    void shouldGetExecutableSteps() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // step_1 无依赖，step_2 依赖 step_1
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "创建项目", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "验证结构", "VERIFICATION", List.of("step_1"))
        ));

        // 只有 step_1 可执行
        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);
        assertEquals(1, executable.size());
        assertEquals("step_1", executable.get(0).id());

        // 完成 step_1 后 step_2 可执行
        steps.set(0, steps.get(0).withResult("项目已创建"));
        executable = orchestrator.getExecutableSteps(steps);
        assertEquals(1, executable.size());
        assertEquals("step_2", executable.get(0).id());
    }

    @Test
    void shouldGetMultipleExecutableStepsForParallelTasks() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "任务A", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "任务B", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_3", "汇总", "ANALYSIS", List.of("step_1", "step_2"))
        );

        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);
        assertEquals(2, executable.size());
    }

    @Test
    void shouldAppendFinalIntegrationStepDependingOnLeaves() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "服务", "FILE_WRITE", List.of("step_1")),
                AgentOrchestrator.ExecutionStep.pending("step_3", "导出", "FILE_WRITE", List.of("step_1"))
        );

        List<AgentOrchestrator.ExecutionStep> withFinal = orchestrator.appendFinalIntegrationStep(steps);

        assertEquals(4, withFinal.size());
        AgentOrchestrator.ExecutionStep finalStep = withFinal.get(3);
        assertEquals("step_4", finalStep.id());
        assertEquals(List.of("step_2", "step_3"), finalStep.dependencies());
        assertTrue(finalStep.description().contains("最终集成验收"));
    }

    @Test
    void dependencyContextUsesOrchestratorSummaryInsteadOfWorkerSelfReport() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        AgentOrchestrator.ExecutionStep dependency = AgentOrchestrator.ExecutionStep
                .pending("step_1", "实现支付状态机", "FILE_WRITE", List.of())
                .withResult("我已经全部做好了", "summary_source=ORCHESTRATOR; review=APPROVED; tools=write_file");
        AgentOrchestrator.ExecutionStep current = AgentOrchestrator.ExecutionStep
                .pending("step_2", "接入支付接口", "FILE_WRITE", List.of("step_1"));

        String context = orchestrator.buildStepContext(List.of(dependency, current), current);

        assertTrue(context.contains("summary_source=ORCHESTRATOR"), context);
        assertFalse(context.contains("我已经全部做好了"), context);
        assertEquals("我已经全部做好了", dependency.artifact().output());
    }


    @Test
    void shouldNotAppendDuplicateFinalIntegrationStep() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "final_integration", "INTEGRATION", List.of("step_1"))
        );

        assertSame(steps, orchestrator.appendFinalIntegrationStep(steps));
    }

    @Test
    void shouldRunFinalIntegrationAfterFailedLeafSteps() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of()).withResult("ok"),
                AgentOrchestrator.ExecutionStep.pending("step_2", "服务", "FILE_WRITE", List.of("step_1")).withFailed("review failed"),
                AgentOrchestrator.ExecutionStep.pending("step_3", "最终集成验收", "INTEGRATION", List.of("step_2"))
        ));

        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);

        assertEquals(1, executable.size());
        assertEquals("step_3", executable.get(0).id());
    }

    @Test
    void shouldNotRunFinalIntegrationWhenFailureLeavesBlockedPendingSteps() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of()).withFailed("review failed"),
                AgentOrchestrator.ExecutionStep.pending("step_2", "服务", "FILE_WRITE", List.of("step_1")),
                AgentOrchestrator.ExecutionStep.pending("step_3", "最终集成验收", "INTEGRATION", List.of("step_2"))
        ));

        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);

        assertTrue(executable.isEmpty());
    }

    @Test
    void finalIntegrationExecutableWhenDependencyFailed() {
        // 在位重做模型下无 SUPERSEDED：依赖步骤 redo 用尽后保持 FAILED 终态时，最终集成仍可执行收尾
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of())
                        .withFailed("review failed"),
                AgentOrchestrator.ExecutionStep.pending("step_2", "最终集成验收", "INTEGRATION", List.of("step_1"))
        ));

        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);

        assertEquals(1, executable.size());
        assertEquals("step_2", executable.get(0).id());
    }

    @Test
    void shouldFuseFinalIntegrationWhenFailureRatioHigh() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // 失败比例 1/2 达到熔断阈值：停止让最终集成强行修补
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "实现", "FILE_WRITE", List.of()).withFailed("编译失败"),
                AgentOrchestrator.ExecutionStep.pending("step_2", "导出", "FILE_WRITE", List.of()).withResult("ok"),
                AgentOrchestrator.ExecutionStep.pending("step_3", "最终集成验收", "INTEGRATION", List.of("step_1", "step_2"))
        );
        assertTrue(orchestrator.shouldFuseFinalIntegration(steps));
    }

    @Test
    void shouldNotFuseFinalIntegrationWhenFailureRatioLow() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // 只有 1/3 失败，未达熔断阈值：最终集成正常执行
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "实现", "FILE_WRITE", List.of()).withFailed("编译失败"),
                AgentOrchestrator.ExecutionStep.pending("step_2", "导出", "FILE_WRITE", List.of()).withResult("ok"),
                AgentOrchestrator.ExecutionStep.pending("step_3", "文档", "FILE_WRITE", List.of()).withResult("ok"),
                AgentOrchestrator.ExecutionStep.pending("step_4", "最终集成验收", "INTEGRATION",
                        List.of("step_1", "step_2", "step_3"))
        );
        assertFalse(orchestrator.shouldFuseFinalIntegration(steps));
    }

    @Test
    void plannerInvalidProtocolShouldBeRepairedBeforeExecution(@TempDir Path tempDir) {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        List<String> plannerInputs = new CopyOnWriteArrayList<>();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                plannerCalls.incrementAndGet();
                plannerInputs.add(body);
                return response("我先检查项目结构，再制定计划。");
            }
            if (body.contains("上次规划输出无法解析")) {
                plannerCalls.incrementAndGet();
                plannerInputs.add(body);
                return response("""
                        {
                          "summary": "直接实现",
                          "steps": [
                            {"id": "s1", "description": "实现核心功能", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务")) {
                return response(approvedReviewJson());
            }
            if (body.contains("实现核心功能") || body.contains("最终集成")) {
                workerCalls.incrementAndGet();
                return response("worker result");
            }
            return response("fallback");
        };

        RecordingDispatchingStubGLMClient llmClient = new RecordingDispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llmClient, isolatedToolRegistry(tempDir), mm);

            String result = orchestrator.run("在空工作区实现核心功能");

            assertFalse(result.contains("规划失败"), result);
            assertEquals(2, plannerCalls.get());
            assertTrue(workerCalls.get() >= 1, "repaired plan should reach worker execution");
            assertTrue(plannerInputs.get(1).contains("只输出 JSON"));
            assertTrue(plannerInputs.get(1).contains("工作区可能为空"));
        }
    }

    @Test
    void plannerBlockingWorkspaceDiscoveryShouldBeRepairedBeforeExecution(@TempDir Path tempDir) {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger blockedStepExecutions = new AtomicInteger();
        AtomicInteger implementationExecutions = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                plannerCalls.incrementAndGet();
                return response("""
                        {
                          "summary": "先检查再实现",
                          "steps": [
                            {"id": "s1", "description": "检查空工作区目录是否存在", "type": "ANALYSIS", "dependencies": []},
                            {"id": "s2", "description": "实现核心功能", "type": "ANALYSIS", "dependencies": ["s1"]}
                          ]
                        }
                        """);
            }
            if (body.contains("阻塞性纯检查步骤")) {
                plannerCalls.incrementAndGet();
                return response("""
                        {
                          "summary": "直接实现",
                          "steps": [
                            {"id": "s1", "description": "实现核心功能，若目标不存在则创建", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务：")) {
                return response(approvedReviewJson());
            }
            if (body.contains("检查空工作区目录是否存在")) {
                blockedStepExecutions.incrementAndGet();
                return response("checked");
            }
            if (body.contains("实现核心功能") || body.contains("最终集成")) {
                implementationExecutions.incrementAndGet();
                return response("implemented");
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llmClient, isolatedToolRegistry(tempDir), mm);

            String result = orchestrator.run("在空工作区实现核心功能");

            assertFalse(result.contains("规划失败"), result);
            assertEquals(2, plannerCalls.get());
            assertEquals(0, blockedStepExecutions.get(), "blocking discovery step must not execute");
            assertTrue(implementationExecutions.get() >= 1);
        }
    }

    @Test
    void shouldScheduleSessionPreSummaryMaintenanceAfterMultiAgentTurn(@TempDir Path tempDir) {
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "单步分析",
                          "steps": [
                            {"id": "s1", "description": "分析模块", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务")) {
                return response(approvedReviewJson());
            }
            if (body.contains("分析模块")) {
                return response("worker result");
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (CountingMemoryManager mm = new CountingMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llmClient, isolatedToolRegistry(tempDir), mm);

            orchestrator.run("测试 Multi-Agent 会话预摘要维护");

            assertEquals(1, mm.asyncMaintenanceCalls.get());
        }
    }

    @Test
    void shouldPropagateAcceptanceCriteriaToWorkerAndReviewer(@TempDir Path tempDir) {
        String fullRequirement = "搭建日志分析 CLI，clean shard 不带 --before 时使用默认日期";
        List<String> workerInputs = new CopyOnWriteArrayList<>();
        List<String> reviewerInputs = new CopyOnWriteArrayList<>();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "实现日志 CLI",
                          "acceptance_criteria": [
                            {
                              "id": "AC-01",
                              "category": "default_param",
                              "description": "log clean shard 不带 --before 时合法，默认删除早于2026-01-01的文件",
                              "verification_method": "TOOL",
                              "verifier": "list_dir",
                              "test_signal": "args = ['log','clean','shard'] 无异常",
                              "applies_to": ["s1"]
                            }
                          ],
                          "steps": [
                            {"id": "s1", "description": "实现 clean/stat 业务逻辑", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("逐条验证以下验收点")) {
                reviewerInputs.add(body);
                return response(approvedReviewJson());
            }
            if (body.contains("当前步骤：实现 clean/stat 业务逻辑")) {
                workerInputs.add(body);
                return response("worker result");
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llmClient, isolatedToolRegistry(tempDir), mm);

            orchestrator.run(fullRequirement);

            assertFalse(workerInputs.isEmpty(), "worker should receive acceptance criteria");
            assertFalse(reviewerInputs.isEmpty(), "reviewer should receive acceptance criteria");
            assertTrue(workerInputs.get(0).contains("本步骤必须满足以下验收点"));
            assertTrue(workerInputs.get(0).contains("AC-01"));
            assertTrue(workerInputs.get(0).contains("default_param"));
            assertTrue(reviewerInputs.get(0).contains("逐条验证以下验收点"));
            assertTrue(reviewerInputs.get(0).contains("log clean shard 不带 --before 时合法"));
        }
    }

    @Test
    void shouldParseReviewApproval() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // 正常通过的 JSON
        assertTrue(orchestrator.parseReviewApproval(approvedReviewJson()));

        // 未通过的 JSON
        assertFalse(orchestrator.parseReviewApproval(
                "{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"缺少错误处理\"]}"));

        // 数字评分只做诊断；没有阻塞验收点或高风险问题时不覆盖 approved=true
        assertTrue(orchestrator.parseReviewApproval("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 0.9,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "issues": []
                }
                """));
        assertTrue(orchestrator.parseReviewApproval("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 0.5,
                    "code_quality": 1.0
                  },
                  "issues": []
                }
                """));
        assertFalse(orchestrator.parseReviewApproval(
                "{\"approved\": true, \"summary\": \"旧扁平 JSON\", \"issues\": []}"));

        // null 或空内容采取保守策略：默认不通过
        assertFalse(orchestrator.parseReviewApproval(null));
        assertFalse(orchestrator.parseReviewApproval(""));

        // 含否定关键词的纯文本
        assertFalse(orchestrator.parseReviewApproval("执行结果未通过审查"));
        assertFalse(orchestrator.parseReviewApproval("代码质量不合格"));

        // 否定语义变体：含"通过"二字但语义是失败，不能误判为批准
        assertFalse(orchestrator.parseReviewApproval("测试未全部通过，存在 2 个失败用例"));
        assertFalse(orchestrator.parseReviewApproval("集成验证没有通过"));
        assertFalse(orchestrator.parseReviewApproval("编译检查未能通过"));

        // 非 JSON 文本不能绕过结构化协议约束
        assertFalse(orchestrator.parseReviewApproval("审查通过，代码质量良好"));

        // 既无肯定关键词也无 JSON：保守判为不通过
        assertFalse(orchestrator.parseReviewApproval("hmm"));

        // JSON 缺少 approved 字段：保守判为不通过
        assertFalse(orchestrator.parseReviewApproval("{\"summary\": \"无 approved 字段\"}"));
    }

    @Test
    void shouldRejectCriticalFailedAcceptanceCriteria() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        assertFalse(orchestrator.parseReviewApproval("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id": "AC-01",
                      "passed": false,
                      "evidence": "代码直接校验 args.length < 4 返回错误，未走默认逻辑",
                      "severity": "critical"
                    }
                  ],
                  "issues": []
                }
                """));
    }

    @Test
    void shouldRejectFailedAcceptanceCriteriaUsingPlannerSeverity() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id": "AC-01",
                      "category": "default_param",
                      "severity": "critical",
                      "description": "缺省参数必须走默认逻辑",
                      "test_signal": "省略参数仍成功"
                    }
                  ],
                  "steps": [
                    {"id": "s1", "description": "实现功能", "type": "ANALYSIS", "dependencies": []}
                  ]
                }
                """);

        assertFalse(orchestrator.parseReviewApproval("""
                {
                  "approved": true,
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id": "AC-01",
                      "passed": false,
                      "evidence": "默认参数路径仍然失败"
                    }
                  ],
                  "issues": []
                }
                """));
    }

    @Test
    void shouldRejectApprovalWhenAcceptanceCriteriaCoverageMissing() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        orchestrator.parsePlan("""
                {
                  "acceptance_criteria": [
                    {
                      "id": "AC-01",
                      "category": "default_param",
                      "description": "缺省参数必须走默认逻辑",
                      "test_signal": "省略参数仍成功",
                      "verification_method": "TOOL",
                      "verifier": "execute_command",
                      "applies_to": ["FINAL"]
                    }
                  ],
                  "steps": [
                    {"id": "s1", "description": "实现功能", "type": "ANALYSIS", "dependencies": []}
                  ]
                }
                """);

        assertFalse(orchestrator.parseReviewApproval("""
                {
                  "approved": true,
                  "summary": "通过",
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [],
                  "issues": []
                }
                """));
    }

    @Test
    void shouldParseReviewIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        String reviewJson = """
                {
                    "approved": false,
                    "summary": "存在问题",
                    "issues": ["缺少错误处理", "代码风格不一致"],
                    "suggestions": ["添加 try-catch", "统一缩进"]
                }
                """;

        String issues = orchestrator.parseReviewIssues(reviewJson);
        assertTrue(issues.contains("缺少错误处理"));
        assertTrue(issues.contains("代码风格不一致"));
    }

    @Test
    void shouldParseStructuredReviewIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        String reviewJson = """
                {
                  "approved": false,
                  "issues": [
                    {
                      "type": "integration",
                      "severity": "high",
                      "description": "缺少默认参数导致空指针风险"
                    }
                  ]
                }
                """;

        String issues = orchestrator.parseReviewIssues(reviewJson);
        assertTrue(issues.contains("type=integration"));
        assertTrue(issues.contains("severity=high"));
        assertTrue(issues.contains("缺少默认参数导致空指针风险"));
    }

    @Test
    void shouldIncludeFailedAcceptanceCriteriaInReviewIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        String issues = orchestrator.parseReviewIssues("""
                {
                  "approved": false,
                  "criteria_results": [
                    {
                      "id": "AC-01",
                      "passed": false,
                      "evidence": "log clean shard 缺少默认日期分支",
                      "severity": "critical"
                    }
                  ],
                  "issues": []
                }
                """);

        assertTrue(issues.contains("AC-01"));
        assertTrue(issues.contains("critical"));
        assertTrue(issues.contains("log clean shard 缺少默认日期分支"));
    }

    @Test
    void shouldFuseFinalIntegrationWhenFailureRatioTooHigh() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of()).withFailed("failed"),
                AgentOrchestrator.ExecutionStep.pending("step_2", "服务", "FILE_WRITE", List.of("step_1")).withResult("ok"),
                AgentOrchestrator.ExecutionStep.pending("step_3", "最终集成验收", "INTEGRATION", List.of("step_2"))
        );

        assertTrue(orchestrator.shouldFuseFinalIntegration(steps));
    }

    @Test
    void shouldFallbackToSummaryForIssues() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        String reviewJson = "{\"approved\": false, \"summary\": \"质量不达标\", \"issues\": []}";
        String issues = orchestrator.parseReviewIssues(reviewJson);
        assertEquals("质量不达标", issues);
    }

    @Test
    void shouldHandleInvalidReviewJson() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String issues = orchestrator.parseReviewIssues("not valid json");
        assertEquals("审查未通过，请改进执行结果", issues);
    }

    @Test
    void reviewerAdvisoryRequiresExecutedSuccessfulHardCheck() {
        assertTrue(ReviewCoordinator.isReviewerAdvisory(
                new ReviewCoordinator.ReviewDecision(false, "建议", false, true)));
        assertFalse(ReviewCoordinator.isReviewerAdvisory(
                new ReviewCoordinator.ReviewDecision(false, "建议", false, false)));
        assertFalse(ReviewCoordinator.isReviewerAdvisory(
                new ReviewCoordinator.ReviewDecision(false, "环境失败", false, true,
                        PreReviewVerifier.FailureKind.INFRASTRUCTURE)));
    }

    @Test
    void reviewerLlmFailureWithoutHardCheckShouldNotBeAdvisory(@TempDir Path tempDir) {
        java.io.ByteArrayOutputStream stdoutCapture = new java.io.ByteArrayOutputStream();
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步任务",
                          "steps": [
                            {"id": "s1", "description": "执行任务", "type": "COMMAND", "dependencies": []}
                          ]
                        }
                        """),
                response("初始执行结果")
                // Reviewer 调用因队列耗尽抛 IOException。
        ));

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    isolatedToolRegistry(tempDir),
                    mm,
                    new java.io.PrintStream(stdoutCapture, true, java.nio.charset.StandardCharsets.UTF_8)
            );

            String finalResult = orchestrator.run("测试 Reviewer 故障降为建议");
            assertTrue(finalResult.contains("未完全完成"), finalResult);
        }

        String stdout = stdoutCapture.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(stdout.contains("重试后审查通过"),
                "reviewer LLM 故障时不应宣称审查通过");
        assertFalse(stdout.contains("Reviewer 建议已记录"), stdout);
    }

    @Test
    void shouldRunIndependentStepsInParallel(@TempDir Path tempDir) throws Exception {
        // 两个互相独立的步骤同属一个依赖批次。若并行执行生效，两个 worker 应同时在 chat() 内等待。
        CountDownLatch workersInFlight = new CountDownLatch(2);
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicInteger currentConcurrency = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "并行两步",
                          "steps": [
                            {"id": "a", "description": "任务A", "type": "ANALYSIS", "dependencies": []},
                            {"id": "b", "description": "任务B", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("任务A")) {
                return awaitBarrierThenReturn(workersInFlight, currentConcurrency, peakConcurrency,
                        response("任务A 的结果"));
            }
            if (body.contains("任务B")) {
                return awaitBarrierThenReturn(workersInFlight, currentConcurrency, peakConcurrency,
                        response("任务B 的结果"));
            }
            if (body.contains("原始任务")) {
                return response(approvedReviewJson());
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    isolatedToolRegistry(tempDir),
                    mm
            );

            String finalResult = orchestrator.run("测试并行执行");

            assertTrue(finalResult.contains("任务A"), "finalResult should mention task A");
            assertTrue(finalResult.contains("任务B"), "finalResult should mention task B");
            // 两个 Worker 同时持有 chat() 调用 → 并发峰值至少为 2
            assertEquals(2, peakConcurrency.get(), "Expected two workers to run concurrently");
        }
    }

    @Test
    void preReviewHookShouldBlockCompileFailureBeforeReviewer(@TempDir Path tempDir) throws Exception {
        Path javaRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(javaRoot);
        Files.writeString(javaRoot.resolve("BrokenCli.java"),
                "public class BrokenCli { public static void main(String[] args) { missing }",
                StandardCharsets.UTF_8);

        RecordingDispatchingStubGLMClient llmClient = new RecordingDispatchingStubGLMClient(body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "实现 Java CLI",
                          "steps": [
                            {"id": "a", "description": "实现 Java CLI 入口 BrokenCli", "type": "FILE_WRITE", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("实现 Java CLI 入口 BrokenCli")) {
                return response("已写入 BrokenCli.java");
            }
            if (body.contains("原始任务")) {
                return response(approvedReviewJson());
            }
            return response("fallback");
        });

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    isolatedToolRegistry(tempDir),
                    mm
            );

            String finalResult = orchestrator.run("实现 Java CLI 入口 BrokenCli");

            assertTrue(finalResult.contains("未完全完成"), finalResult);
        }

        boolean reviewerCalled = llmClient.calls.stream()
                .map(DispatchingStubGLMClient::findLastUser)
                .anyMatch(body -> body.contains("必须调用工具检查真实产物"));
        assertFalse(reviewerCalled, "compile failure should be blocked before Reviewer LLM");
    }

    private static LlmClient.ChatResponse awaitBarrierThenReturn(CountDownLatch latch,
                                                                  AtomicInteger current,
                                                                  AtomicInteger peak,
                                                                  LlmClient.ChatResponse response) {
        int now = current.incrementAndGet();
        peak.updateAndGet(prev -> Math.max(prev, now));
        latch.countDown();
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Both workers should reach chat() concurrently");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return response;
    }

    @Test
    void shouldReportIncompleteRunWhenFailureBlocksRemainingSteps(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "两步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "COMMAND",
                              "dependencies": []
                            },
                            {
                              "id": "s2",
                              "description": "第二步",
                              "type": "ANALYSIS",
                              "dependencies": ["s1"]
                            }
                          ]
                        }
                        """),
                response("")
        ));

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    isolatedToolRegistry(tempDir),
                    mm
            );

            String finalResult = orchestrator.run("测试失败阻塞");

            assertTrue(finalResult.contains("未完全完成"));
            assertTrue(finalResult.contains("[step_1] ❌ 第一步"));
            assertTrue(finalResult.contains("[step_2] ⏳ 第二步"));
        }
    }

    @Test
    void analysisStepCannotInvokeProjectMutationTool(@TempDir Path tempDir) throws Exception {
        AtomicInteger workerTurns = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "只读分析",
                          "steps": [
                            {"id": "s1", "description": "分析需求", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("分析需求")) {
                if (workerTurns.incrementAndGet() == 1) {
                    return toolResponse("worker-write-denied", "write_file",
                            "{\"path\":\"must-not-write.txt\",\"content\":\"denied\"}");
                }
                return response("分析完成");
            }
            if (body.contains("最终集成验收")) {
                return response("最终集成完成");
            }
            if (body.contains("执行结果：")) {
                return response(approvedReviewJson());
            }
            return response(approvedReviewJson());
        };

        RecordingDispatchingStubGLMClient llmClient = new RecordingDispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient, isolatedToolRegistry(tempDir), mm);

            orchestrator.run("只分析，不修改项目");

            assertFalse(Files.exists(tempDir.resolve("must-not-write.txt")));
            assertTrue(llmClient.calls.stream()
                    .flatMap(List::stream)
                    .anyMatch(message -> message.content().contains("工具能力被当前执行范围拒绝")));
        }
    }

    @Test
    void approvedWorkspacePatchIsAppliedAfterReviewerReadsIsolatedFile(@TempDir Path tempDir) throws Exception {
        AtomicInteger workerTurns = new AtomicInteger();
        AtomicInteger reviewerTurns = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "隔离写入",
                          "steps": [
                            {"id": "s1", "description": "写入隔离文件 result.txt", "type": "FILE_WRITE", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("执行结果：")) {
                if (reviewerTurns.incrementAndGet() % 2 == 1) {
                    return toolResponse("review-read", "read_file", "{\"path\":\"result.txt\"}");
                }
                return response(approvedReviewJson());
            }
            if (body.contains("写入隔离文件 result.txt")) {
                if (workerTurns.incrementAndGet() == 1) {
                    return toolResponse("worker-write", "write_file",
                            "{\"path\":\"result.txt\",\"content\":\"isolated-content\"}");
                }
                return response("文件写入完成");
            }
            if (body.contains("最终集成验收")) {
                return response("最终集成完成");
            }
            return response(approvedReviewJson());
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient, isolatedToolRegistry(tempDir), mm);

            orchestrator.run("验证隔离工作区 PatchSet");

            assertEquals("isolated-content", Files.readString(tempDir.resolve("result.txt")));
            assertFalse(workspaceRootHasEntries(tempDir));
        }
    }

    @Test
    void deterministicPreReviewFailureShouldStillBlockWorkspacePatch(@TempDir Path tempDir)
            throws Exception {
        AtomicInteger workerTurns = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "硬检查失败",
                          "steps": [
                            {"id":"s1","description":"写入 src/main/java/Broken.java","type":"FILE_WRITE","dependencies":[]}
                          ]
                        }
                        """);
            }
            if (body.contains("写入 src/main/java/Broken.java")) {
                if (workerTurns.getAndIncrement() % 2 == 0) {
                    return toolResponse("worker-write", "write_file",
                            "{\"path\":\"src/main/java/Broken.java\",\"content\":\"broken\"}");
                }
                return response("文件写入完成");
            }
            return response(approvedReviewJson());
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient, isolatedToolRegistry(tempDir), mm);
            orchestrator.setPreReviewVerifier(new PreReviewVerifier(30,
                    request -> com.devcli.tool.command.CommandExecutionService.Result
                            .completed(1, "compile failed")));

            String result = orchestrator.run("验证确定性硬检查仍阻断 PatchSet");

            assertTrue(result.contains("未完全完成"), result);
            assertFalse(Files.exists(tempDir.resolve("src/main/java/Broken.java")));
            assertFalse(workspaceRootHasEntries(tempDir));
        }
    }

    private static boolean workspaceRootHasEntries(Path projectRoot) throws IOException {
        Path workspaceRoot = projectRoot.resolve("Temp/devcli-workspaces");
        if (!Files.isDirectory(workspaceRoot)) {
            return false;
        }
        try (var entries = Files.list(workspaceRoot)) {
            return entries.findAny().isPresent();
        }
    }

    @Test
    void workerToolEvidenceCarriesProducingStepProvenance(@TempDir Path tempDir) {
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "单步列目录",
                          "steps": [
                            {"id": "a", "description": "列出项目目录", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务：")) {
                return response(approvedReviewJson());
            }
            if (body.contains("列出项目目录")) {
                return toolResponse("call_1", "list_dir", "{\"path\":\".\"}");
            }
            return response("done");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), isolatedToolRegistry(tempDir), mm);

            orchestrator.run("列目录并验收");

            List<String> stepIds = mm.getSessionMemory().getRecentToolResults().stream()
                    .map(evidence -> evidence.stepId)
                    .toList();
            assertTrue(stepIds.stream().anyMatch(stepId -> !stepId.isBlank()),
                    "Multi-Agent 步骤产生的工具证据必须带步骤标识，实际: " + stepIds);
            assertTrue(stepIds.stream().anyMatch("step_1"::equals),
                    "步骤标识应为产生证据的步骤 id，实际: " + stepIds);
        }
    }

    private static LlmClient.ChatResponse toolResponse(String id, String name, String arguments) {
        return new LlmClient.ChatResponse(
                "assistant", "", null,
                List.of(new LlmClient.ToolCall(id,
                        new LlmClient.ToolCall.Function(name, arguments))),
                100, 20);
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse(
                "assistant", withDefaultAcceptanceCriterion(content), null, 100, 20);
    }

    private static String withDefaultAcceptanceCriterion(String content) {
        if (content == null
                || !content.contains("\"steps\"")
                || content.contains("\"acceptance_criteria\"")) {
            return content;
        }
        int objectStart = content.indexOf('{');
        if (objectStart < 0) {
            return content;
        }
        String criterion = """

                  "acceptance_criteria": [
                    {
                      "id":"AC-01",
                      "description":"测试夹具要求任务结果可观察",
                      "verification_method":"TOOL",
                      "verifier":"list_dir",
                      "test_signal":"工具返回成功",
                      "applies_to":["FINAL"]
                    }
                  ],
                """;
        return content.substring(0, objectStart + 1)
                + criterion
                + content.substring(objectStart + 1);
    }

    private static String humanReviewPlanJson() {
        return """
                {
                  "acceptance_criteria": [
                    {
                      "id": "AC-01",
                      "description": "终端信息层级清晰",
                      "verification_method": "HUMAN",
                      "verifier": "用户检查最终终端展示的信息层级",
                      "test_signal": "用户确认不存在歧义",
                      "applies_to": ["FINAL"]
                    }
                  ],
                  "steps": [
                    {"id":"s1","description":"完成终端展示","type":"ANALYSIS","dependencies":[]}
                  ]
                }
                """;
    }

    private static String approvedReviewJson() {
        return """
                {
                  "approved": true,
                  "summary": "通过",
                  "scores": {
                    "functional_correctness": 1.0,
                    "integration_completeness": 1.0,
                    "code_quality": 1.0
                  },
                  "criteria_results": [
                    {
                      "id":"AC-01",
                      "passed":true,
                      "verification_method":"TOOL",
                      "evidence":"测试夹具验证通过"
                    }
                  ],
                  "issues": []
                }
                """;
    }

    private static String rejectedReviewJson() {
        return """
                {
                  "approved": false,
                  "summary": "拒绝应用",
                  "scores": {
                    "functional_correctness": 0.0,
                    "integration_completeness": 0.5,
                    "code_quality": 0.5
                  },
                  "issues": ["验收条件未满足"]
                }
                """;
    }

    private static ToolRegistry isolatedToolRegistry(Path tempDir) {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(tempDir.toString());
        return toolRegistry;
    }

    private static final class RecordingLeaseToolRegistry extends ToolRegistry {
        private final List<String> releasedStepIds = new CopyOnWriteArrayList<>();

        @Override
        public void releaseResourceLeases(String stepId) {
            releasedStepIds.add(stepId);
            super.releaseResourceLeases(stepId);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AgentOrchestrator.ExecutionStep> invokeRebuildStepsFromCheckpoint(
            AgentOrchestrator orchestrator, AgentCheckpoint checkpoint) throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod("rebuildStepsFromCheckpoint", AgentCheckpoint.class);
        method.setAccessible(true);
        return (List<AgentOrchestrator.ExecutionStep>) method.invoke(orchestrator, checkpoint);
    }

    private static String invokeBuildStepContext(AgentOrchestrator orchestrator,
                                                 List<AgentOrchestrator.ExecutionStep> steps,
                                                 AgentOrchestrator.ExecutionStep current) throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod("buildStepContext", List.class,
                AgentOrchestrator.ExecutionStep.class);
        method.setAccessible(true);
        return (String) method.invoke(orchestrator, steps, current);
    }

    private static void invokeRestoreCheckpointArtifacts(
            AgentOrchestrator orchestrator, AgentCheckpoint.RecoveryState recovery) throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "restoreCheckpointArtifactsIntoSessionMemory", AgentCheckpoint.RecoveryState.class);
        method.setAccessible(true);
        method.invoke(orchestrator, recovery);
    }

    private static String invokePreviewDependencyResult(String result) throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod("previewDependencyResult", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, result);
    }

    private static AgentCheckpoint.RecoveryState invokeRestoreAgentTopology(
            AgentOrchestrator orchestrator, AgentCheckpoint checkpoint) throws Exception {
        Field stateField = AgentOrchestrator.class.getDeclaredField("runState");
        stateField.setAccessible(true);
        OrchestrationRunState runState = (OrchestrationRunState) stateField.get(orchestrator);
        runState.setCheckpoint(checkpoint);
        Method method = AgentOrchestrator.class.getDeclaredMethod("restoreAgentTopology", AgentCheckpoint.class);
        method.setAccessible(true);
        return (AgentCheckpoint.RecoveryState) method.invoke(orchestrator, checkpoint);
    }

    @SuppressWarnings("unchecked")
    private static List<String> workersOf(AgentOrchestrator orchestrator) throws Exception {
        Field field = AgentOrchestrator.class.getDeclaredField("workerIds");
        field.setAccessible(true);
        return (List<String>) field.get(orchestrator);
    }

    private static String invokeResolveAssignedWorker(AgentOrchestrator orchestrator,
                                                        String stepId, int preferredIndex) throws Exception {
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "resolveAssignedWorker", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(orchestrator, stepId, preferredIndex);
    }

    private static String findSystemByLastUser(List<List<LlmClient.Message>> calls, String userNeedle) {
        return calls.stream()
                .filter(messages -> DispatchingStubGLMClient.findLastUser(messages).contains(userNeedle))
                .findFirst()
                .map(messages -> messages.isEmpty() ? "" : messages.get(0).content())
                .orElseThrow(() -> new AssertionError("未找到 user 包含: " + userNeedle));
    }

    /**
     * 取匹配调用的最后一条 user 消息内容。当轮上下文快照（记忆 / skill 索引 / 工作记忆）
     * 以 append-only 方式前置在这条消息里，不再进 system prompt。
     */
    private static String findTurnContextByLastUser(List<List<LlmClient.Message>> calls, String userNeedle) {
        return calls.stream()
                .map(DispatchingStubGLMClient::findLastUser)
                .filter(lastUser -> lastUser.contains(userNeedle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 user 包含: " + userNeedle));
    }

    @Test
    void dependencyStepContextShouldIncludeCompletedStepModifiedFiles(@TempDir File memoryDir) throws Exception {
        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            AgentOrchestrator.ExecutionStep dependency = new AgentOrchestrator.ExecutionStep(
                    "step-1", "改实现", "code", List.of(), "代码已改",
                    AgentOrchestrator.StepStatus.COMPLETED, List.of("src/main/java/App.java"));
            AgentOrchestrator.ExecutionStep current = AgentOrchestrator.ExecutionStep.pending(
                    "step-2", "补测试", "test", List.of("step-1"));

            String context = invokeBuildStepContext(orchestrator, List.of(dependency, current), current);

            assertTrue(context.contains("修改文件"));
            assertTrue(context.contains("src/main/java/App.java"));
            assertTrue(context.contains("基于真实落盘状态衔接实现"));
        }
    }

    @Test
    void resumedStepContextIncludesOnlyItsPersistedAttemptDigests(@TempDir File memoryDir) throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-attempt-context", "目标");
        checkpoint.recordAttemptDigests(List.of(
                new AgentCheckpoint.AttemptDigestRecord(
                        "step-1", "mvn test 已失败：排除网络问题", "attempt-1", 1),
                new AgentCheckpoint.AttemptDigestRecord(
                        "step-2", "npm test 已失败：排除依赖缺失", "attempt-2", 2)));
        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            invokeRestoreCheckpointArtifacts(orchestrator, checkpoint.recoveryState());
            AgentOrchestrator.ExecutionStep current = AgentOrchestrator.ExecutionStep.pending(
                    "step-1", "修复测试", "test", List.of());

            String context = invokeBuildStepContext(orchestrator, List.of(current), current);

            assertTrue(context.contains("排除网络问题"));
            assertFalse(context.contains("排除依赖缺失"));
        }
    }

    @Test
    void rebuildStepsFromCheckpointShouldRestoreCompletedModifiedFiles(@TempDir File memoryDir) throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-modified-files", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "改实现", "code", List.of()),
                new AgentCheckpoint.PlanStep("step-2", "补测试", "test", List.of("step-1"))));
        checkpoint.addCompletedStep("step-1", List.of("src/main/java/App.java"), "代码已改");

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);

            List<AgentOrchestrator.ExecutionStep> steps = invokeRebuildStepsFromCheckpoint(orchestrator, checkpoint);

            assertEquals(List.of("src/main/java/App.java"), steps.get(0).modifiedFiles());
        } finally {
            checkpoint.delete();
        }
    }

    @Test
    void rebuildKeepsExhaustedRedoFailureTerminal(@TempDir File memoryDir) throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-redo-exhausted", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "改实现", "code", List.of())));
        checkpoint.addFailedStep("step-1", List.of("src/main/java/App.java"), "首次失败");
        checkpoint.recordRedoAttempt("step-1", 1, "首次失败", List.of("src/main/java/App.java"));
        checkpoint.addFailedStep("step-1", List.of("src/main/java/App.java"), "重做仍失败");

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);

            List<AgentOrchestrator.ExecutionStep> steps = invokeRebuildStepsFromCheckpoint(orchestrator, checkpoint);

            assertEquals(AgentOrchestrator.StepStatus.FAILED, steps.get(0).status());
            assertEquals("重做仍失败", steps.get(0).result());
        }
    }

    @Test
    void rebuildResumesRedoThatWasScheduledButNotFinished(@TempDir File memoryDir) throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-redo-pending", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "改实现", "code", List.of())));
        checkpoint.addFailedStep("step-1", List.of("src/main/java/App.java"), "首次失败");
        checkpoint.recordRedoAttempt("step-1", 1, "首次失败", List.of("src/main/java/App.java"));

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);

            List<AgentOrchestrator.ExecutionStep> steps = invokeRebuildStepsFromCheckpoint(orchestrator, checkpoint);

            assertEquals(AgentOrchestrator.StepStatus.PENDING, steps.get(0).status());
        }
    }

    @Test
    void restoreUsesCheckpointWorkerTopologyAssignmentAndCursor(@TempDir File memoryDir) throws Exception {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-agent-topology", "目标");
        checkpoint.ensureAgentIdentities(List.of(
                new AgentCheckpoint.AgentIdentityRecord("planner", "PLANNER", "planner", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("worker-1", "WORKER", "worker-1", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("worker-2", "WORKER", "worker-2", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("worker-3", "WORKER", "worker-3", 1, 1, 1),
                new AgentCheckpoint.AgentIdentityRecord("reviewer", "REVIEWER", "reviewer", 1, 1, 1)));
        checkpoint.assignStep("step-1", "worker-3", "reviewer");
        checkpoint.advanceAgentCursor("worker-3", "step-1", "上次执行已修改服务接口");

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);

            AgentCheckpoint.RecoveryState recovery = invokeRestoreAgentTopology(orchestrator, checkpoint);
            List<String> restoredWorkers = workersOf(orchestrator);
            String assigned = invokeResolveAssignedWorker(orchestrator, "step-1", 0);

            assertEquals(List.of("worker-1", "worker-2", "worker-3"),
                    restoredWorkers);
            assertEquals("worker-3", assigned);
            assertEquals("worker-3", recovery.stepAssignments().get("step-1").workerAgentId());
            assertEquals(1, recovery.agentCursors().get(assigned).lastMessageSeq());
            assertEquals("上次执行已修改服务接口", recovery.agentCursors().get(assigned).summary());
        }
    }

    @Test
    void resumeFinishesCompletedCheckpointWithoutLlmCalls(@TempDir File memoryDir) {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-resume1", "重构订单模块并补充测试");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "改代码", "code", List.of()),
                new AgentCheckpoint.PlanStep("step-2", "补测试", "test", List.of("step-1"))));
        checkpoint.addCompletedStep("step-1", List.of("src/Order.java"), "代码已改");
        checkpoint.addCompletedStep("step-2", List.of(), "测试已补");
        checkpoint.save();

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            String result = orchestrator.resume("orch-resume1");

            assertTrue(result.contains("Plan 任务完成"), result);
            assertTrue(result.contains("代码已改"), result);
            // 全部完成后 checkpoint 文件被删除
            assertNull(AgentCheckpoint.load("orch-resume1"));
        }
    }

    @Test
    void resumeIgnoresLegacySupersededFieldAndCompletes(@TempDir File memoryDir) {
        // 旧版 checkpoint 含遗留 supersededSteps 字段；在位重做模型下该字段被忽略，
        // resume 不报错，已完成步骤（含曾被标记 superseded 的）按 COMPLETED 直接收尾
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-resume2", "目标");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "原步骤", "code", List.of()),
                new AgentCheckpoint.PlanStep("step-2", "后续步骤", "code", List.of("step-1"))));
        checkpoint.setSupersededSteps(List.of("step-1")); // 遗留字段，应被忽略
        checkpoint.addCompletedStep("step-1", List.of(), "完成1");
        checkpoint.addCompletedStep("step-2", List.of(), "完成2");
        checkpoint.save();

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            String result = orchestrator.resume("orch-resume2");

            assertTrue(result.contains("Plan 任务完成"), result);
            assertNull(AgentCheckpoint.load("orch-resume2"));
        }
    }

    @Test
    void resumeLegacyPendingCheckpointRequiresHumanReviewForUnversionedCriteria(@TempDir File memoryDir) {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-legacy-criteria", "恢复旧任务");
        checkpoint.setProtocolVersion(5);
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "继续实现功能", "ANALYSIS", List.of())));
        checkpoint.setAcceptanceCriteria(List.of(
                new AgentCheckpoint.CriterionRecord(
                        "AC-01", "behavior", "结果符合原始意图", "用户确认功能方向", "high")));
        checkpoint.save();

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            AtomicInteger reviewCalls = new AtomicInteger();
            orchestrator.setPlanReviewHandler(request -> {
                reviewCalls.incrementAndGet();
                assertTrue(request.requiresHumanReview());
                assertEquals("HUMAN", request.criteria().get(0).verificationMethod());
                return AgentOrchestrator.TeamPlanReviewDecision.cancel("旧验收标准待人工确认");
            });

            String result = orchestrator.resume("orch-legacy-criteria");

            assertEquals(1, reviewCalls.get());
            assertTrue(result.contains("旧验收标准待人工确认"), result);
        } finally {
            checkpoint.delete();
        }
    }

    @Test
    void resumeTreatsSemanticPlanRejectionAsAdvisory(@TempDir File memoryDir) {
        AgentCheckpoint checkpoint = new AgentCheckpoint("orch-plan-review-resume", "恢复关键功能");
        checkpoint.setPlanSteps(List.of(
                new AgentCheckpoint.PlanStep("step-1", "继续实现功能", "ANALYSIS", List.of())));
        checkpoint.setAcceptanceCriteria(List.of(new AgentCheckpoint.CriterionRecord(
                "AC-01", "behavior", "结果符合原始意图", "list_dir 返回成功", "high",
                "TOOL", "list_dir", List.of("step-1"))));
        checkpoint.save();
        AtomicInteger reviewCalls = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("计划语义评审")) {
                reviewCalls.incrementAndGet();
                return response("""
                        {"approved":false,"summary":"恢复计划缺少目标覆盖","requirement_coverage":[
                          {"requirement":"恢复关键功能","status":"missing","step_ids":["step-1"],"criterion_ids":["AC-01"]}
                        ],"criteria_reviews":[
                          {"id":"AC-01","clear":true,"verifiable":true,"scope_valid":true,"evidence":"list_dir 可执行"}
                        ],"issues":[
                          {"type":"missing_requirement","severity":"high","requirement":"恢复关键功能","description":"节点没有覆盖完整目标","suggested_fix":"重新规划"}
                        ]}
                        """);
            }
            workerCalls.incrementAndGet();
            return response("unexpected worker call");
        };

        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new DispatchingStubGLMClient(dispatcher), new ToolRegistry(), mm);
            orchestrator.setPlanSemanticReviewEnabled(true);

            String result = orchestrator.resume("orch-plan-review-resume");

            assertEquals(1, reviewCalls.get());
            assertTrue(workerCalls.get() >= 1, "semantic advisory should not block resumed workers");
            assertFalse(result.contains("计划语义评审未通过"), result);
            assertNull(AgentCheckpoint.load("orch-plan-review-resume"), "全部分析步骤已完成时清理 checkpoint");
        } finally {
            checkpoint.delete();
        }
    }

    @Test
    void resumeReportsFutureCheckpointAsIncompatible(@TempDir File memoryDir) {
        AgentCheckpoint future = new AgentCheckpoint("orch-future-resume", "未来任务");
        future.setProtocolVersion(AgentCheckpoint.CURRENT_PROTOCOL_VERSION + 1);
        future.save();
        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);

            String result = orchestrator.resume("orch-future-resume");

            assertTrue(result.contains("不兼容"), result);
        } finally {
            future.delete();
        }
    }

    @Test
    void resumeWithUnknownIdListsAvailableCheckpoints(@TempDir File memoryDir) {
        AgentCheckpoint existing = new AgentCheckpoint("orch-exists", "已有任务");
        existing.setPlanSteps(List.of(new AgentCheckpoint.PlanStep("s1", "描述", "code", List.of())));
        existing.save();
        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            String result = orchestrator.resume("orch-nope");

            assertTrue(result.contains("未找到 checkpoint [orch-nope]"), result);
            assertTrue(result.contains("orch-exists"), result);
        } finally {
            existing.delete();
        }
    }

    @Test
    void resumeRejectsLegacyCheckpointWithoutPlanSteps(@TempDir File memoryDir) {
        AgentCheckpoint legacy = new AgentCheckpoint("orch-legacy", "旧格式任务");
        legacy.save();
        try (NoOpMemoryManager mm = new NoOpMemoryManager(memoryDir)) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    new GLMClient("test-key"), new ToolRegistry(), mm);
            String result = orchestrator.resume("orch-legacy");

            assertTrue(result.contains("缺少计划数据"), result);
        } finally {
            legacy.delete();
        }
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }
    }

    private static final class RoleScopedMemoryManager extends MemoryManager {
        private RoleScopedMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }

        @Override
        public void beginTask(String taskId) {
            super.beginTask(taskId);
            setTaskState("agent_scope", "multi-agent");
            addVolatileFact("planner-worker-visible-event");
            addToolResult("read_file", "{\"path\":\"Secret.java\"}", "reviewer-worker-visible-evidence");
        }
    }

    private static final class CountingMemoryManager extends MemoryManager {
        private final AtomicInteger asyncMaintenanceCalls = new AtomicInteger();

        private CountingMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }

        @Override
        public CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
                List<LlmClient.Message> history,
                int turnToolCalls,
                int largestToolResultChars) {
            asyncMaintenanceCalls.incrementAndGet();
            return CompletableFuture.completedFuture(SessionPreSummaryMaintenanceResult.SKIPPED_BELOW_THRESHOLD);
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }

    /**
     * 基于最后一条用户消息内容派发响应的 stub，支持多线程并发调用。
     */
    private static final class DispatchingStubGLMClient extends GLMClient {
        private final Function<String, ChatResponse> dispatcher;

        private DispatchingStubGLMClient(Function<String, ChatResponse> dispatcher) {
            super("test-key");
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            String lastUserMessage = findLastUser(messages);
            ChatResponse response = dispatcher.apply(lastUserMessage);
            if (response == null) {
                throw new IOException("无匹配响应，最后的 user 消息: " + lastUserMessage);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        private static String findLastUser(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if ("user".equals(m.role())) {
                    return m.content() == null ? "" : m.content();
                }
            }
            return "";
        }
    }

    private static final class RecordingDispatchingStubGLMClient extends GLMClient {
        private final Function<String, ChatResponse> dispatcher;
        private final List<List<Message>> calls = new CopyOnWriteArrayList<>();
        private final List<List<Tool>> toolsByCall = new CopyOnWriteArrayList<>();

        private RecordingDispatchingStubGLMClient(Function<String, ChatResponse> dispatcher) {
            super("test-key");
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls.add(List.copyOf(messages));
            toolsByCall.add(tools);
            String lastUserMessage = DispatchingStubGLMClient.findLastUser(messages);
            ChatResponse response = dispatcher.apply(lastUserMessage);
            if (response == null) {
                throw new IOException("无匹配响应，最后的 user 消息: " + lastUserMessage);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }
}
