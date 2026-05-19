package com.paicli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.skill.SkillContextBuffer;
import com.paicli.tool.ToolRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {

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
    void shouldNotAppendDuplicateFinalIntegrationStep() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "模型", "FILE_WRITE", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "final_integration", "INTEGRATION", List.of("step_1"))
        );

        assertSame(steps, orchestrator.appendFinalIntegrationStep(steps));
    }

    @Test
    void shouldPropagateOriginalUserTaskToWorkerAndReviewer(@TempDir Path tempDir) {
        String fullRequirement = "完整需求：必须生成 public final class，private constructor，public static 方法";
        List<String> workerInputs = new CopyOnWriteArrayList<>();
        List<String> reviewerInputs = new CopyOnWriteArrayList<>();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "实现工具类",
                          "steps": [
                            {"id": "s1", "description": "实现 StringStats 工具类", "type": "FILE_WRITE", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("原始任务")) {
                reviewerInputs.add(body);
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            if (body.contains("实现 StringStats 工具类")) {
                workerInputs.add(body);
                return response("worker result");
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(llmClient, new ToolRegistry(), mm);

            orchestrator.run(fullRequirement);

            assertFalse(workerInputs.isEmpty(), "worker should be invoked");
            assertFalse(reviewerInputs.isEmpty(), "reviewer should be invoked");
            assertTrue(workerInputs.get(0).contains(fullRequirement),
                    "worker context should include the original user task");
            assertTrue(reviewerInputs.get(0).contains(fullRequirement),
                    "reviewer context should include the original user task");
        }
    }

    @Test
    void shouldParseReviewApproval() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // 正常通过的 JSON
        assertTrue(orchestrator.parseReviewApproval(
                "{\"approved\": true, \"summary\": \"通过\", \"issues\": []}"));

        // 未通过的 JSON
        assertFalse(orchestrator.parseReviewApproval(
                "{\"approved\": false, \"summary\": \"未通过\", \"issues\": [\"缺少错误处理\"]}"));

        // null 或空内容采取保守策略：默认不通过
        assertFalse(orchestrator.parseReviewApproval(null));
        assertFalse(orchestrator.parseReviewApproval(""));

        // 含否定关键词的纯文本
        assertFalse(orchestrator.parseReviewApproval("执行结果未通过审查"));
        assertFalse(orchestrator.parseReviewApproval("代码质量不合格"));

        // 含肯定关键词的非 JSON 文本
        assertTrue(orchestrator.parseReviewApproval("审查通过，代码质量良好"));

        // 既无肯定关键词也无 JSON：保守判为不通过
        assertFalse(orchestrator.parseReviewApproval("hmm"));

        // JSON 缺少 approved 字段：保守判为不通过
        assertFalse(orchestrator.parseReviewApproval("{\"summary\": \"无 approved 字段\"}"));
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
    void shouldRetryRejectedStepUntilApproval(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "执行任务",
                              "type": "COMMAND",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("第一次执行结果"),
                response("""
                        {"approved": false, "summary": "第一次未通过", "issues": ["需要补充细节"]}
                        """),
                response("第二次执行结果"),
                response("""
                        {"approved": false, "summary": "第二次未通过", "issues": ["还缺最后结论"]}
                        """),
                response("第三次执行结果"),
                response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """)
        ));

        AgentOrchestrator orchestrator;
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            orchestrator = new AgentOrchestrator(
                    llmClient,
                    new ToolRegistry(),
                    mm
            );

            String finalResult = orchestrator.run("测试重试逻辑");

            assertTrue(finalResult.contains("第三次执行结果"));
            assertFalse(finalResult.contains("第二次执行结果"));
        }
    }

    @Test
    void shouldNotMarkApprovedWhenReviewerLlmFailsDuringRetry(@TempDir Path tempDir) {
        // 回归测试 P0 bug：reviewer LLM 调用在重试阶段失败时，旧代码会把 approved 设为 true
        // 让用户看到 "重试后审查通过" 但实际 reviewer 根本没回复。
        // 修复后行为：reviewer 未通过/故障时标记 FAILED，不能放行下游依赖。
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
                response("初始执行结果"),
                response("""
                        {"approved": false, "summary": "首次未通过", "issues": ["补充细节"]}
                        """),
                response("重试执行结果")
                // 第 4 次 chat 调用（重试后再次审查）会因为队列耗尽抛 IOException，
                // 模拟 "reviewer LLM 在重试阶段调用失败" 的真实场景
        ));

        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    new ToolRegistry(),
                    mm,
                    new java.io.PrintStream(stdoutCapture, true, java.nio.charset.StandardCharsets.UTF_8)
            );

            String finalResult = orchestrator.run("测试 reviewer 故障不应误判通过");
            assertTrue(finalResult.contains("未完全完成"), finalResult);
        }

        String stdout = stdoutCapture.toString(java.nio.charset.StandardCharsets.UTF_8);
        // 修复前：日志会显示 "重试后审查通过"
        assertFalse(stdout.contains("重试后审查通过"),
                "reviewer LLM 故障时不应宣称审查通过");
        assertTrue(stdout.contains("审查阶段 LLM 调用失败") || stdout.contains("审查未通过"),
                "应明确告知用户 reviewer 故障，stdout=" + stdout);
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
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    new ToolRegistry(),
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
    void shouldUseSharedPromptPrefixForParallelWorkers(@TempDir Path tempDir) {
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
            if (body.contains("原始任务")) {
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            if (body.contains("任务A")) {
                return response("任务A 的结果");
            }
            if (body.contains("任务B")) {
                return response("任务B 的结果");
            }
            return response("fallback");
        };

        RecordingDispatchingStubGLMClient llmClient = new RecordingDispatchingStubGLMClient(dispatcher);
        ToolRegistry toolRegistry = new ToolRegistry();
        try (NoOpMemoryManager mm = new NoOpMemoryManager(tempDir.toFile())) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    llmClient,
                    toolRegistry,
                    mm
            );
            orchestrator.setExternalContextSupplier(() -> "稳定外部上下文");
            orchestrator.setStickyMemorySupplier(() -> "稳定长期记忆");
            SkillContextBuffer skillBuffer = new SkillContextBuffer();
            skillBuffer.push("parallel-skill", "并行 worker 都应该看到这段 skill");
            orchestrator.setSkillSystem(null, skillBuffer);

            orchestrator.run("测试并行共享 prompt prefix");

            List<List<LlmClient.Message>> workerCalls = llmClient.calls.stream()
                    .filter(messages -> messages.size() == 2)
                    .filter(messages -> {
                        String lastUser = DispatchingStubGLMClient.findLastUser(messages);
                        return lastUser.contains("当前任务：任务A") || lastUser.contains("当前任务：任务B");
                    })
                    .toList();

            assertEquals(2, workerCalls.size(), "should record the two parallel worker calls");
            assertFalse(workerCalls.get(0).isEmpty());
            assertFalse(workerCalls.get(1).isEmpty());
            assertEquals(workerCalls.get(0).get(0).role(), workerCalls.get(1).get(0).role());
            assertEquals(workerCalls.get(0).get(0).content(), workerCalls.get(1).get(0).content(),
                    "parallel workers should share an identical frozen system prompt prefix");
            assertTrue(workerCalls.get(0).get(0).content().contains("稳定长期记忆"),
                    "parallel worker prefix should include sticky memory");
            assertTrue(workerCalls.get(0).get(0).content().contains("用户最新输入"),
                    "parallel worker prefix should include working memory");
            assertNotEquals(DispatchingStubGLMClient.findLastUser(workerCalls.get(0)),
                    DispatchingStubGLMClient.findLastUser(workerCalls.get(1)),
                    "task-specific suffixes should remain independent");
            assertTrue(DispatchingStubGLMClient.findLastUser(workerCalls.get(0)).contains("parallel-skill"),
                    "first worker should see the fork skill snapshot");
            assertTrue(DispatchingStubGLMClient.findLastUser(workerCalls.get(1)).contains("parallel-skill"),
                    "second worker should see the same fork skill snapshot");
            assertEquals(1, skillBuffer.size(), "fork skill snapshot should not drain the shared skill buffer");

            List<List<LlmClient.Tool>> workerTools = llmClient.toolsByCall.stream()
                    .filter(tools -> tools != null && !tools.isEmpty())
                    .toList();
            assertTrue(workerTools.size() >= 2, "worker calls should receive exact tool snapshots");
            assertSame(workerTools.get(0), workerTools.get(1),
                    "parallel forked workers should reuse the same tool definitions snapshot");
        }
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
                    new ToolRegistry(),
                    mm
            );

            String finalResult = orchestrator.run("测试失败阻塞");

            assertTrue(finalResult.contains("未完全完成"));
            assertTrue(finalResult.contains("[step_1] ❌ 第一步"));
            assertTrue(finalResult.contains("[step_2] ⏳ 第二步"));
        }
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
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
