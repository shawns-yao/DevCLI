package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.plan.ExecutionPlan;
import com.devcli.plan.Planner;
import com.devcli.plan.Task;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWritePlanExecutionArtifactsBackToWorkingMemoryOnly() throws Exception {
        Path sampleFile = Files.createFile(tempDir.resolve("sample.txt"));
        Files.writeString(sampleFile, "plan-memory-content");

        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_1",
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + sampleFile.toString().replace("\\", "\\\\") + "\"}"
                                )
                        )),
                        120,
                        30
                ),
                new LlmClient.ChatResponse("assistant", "已读取并确认文件内容", null, 140, 40)
        ));

        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                new LongTermMemory(tempDir.resolve("memory-store").toFile())
        );
        try (memoryManager) {
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.setProjectPath(tempDir.toString());
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    toolRegistry,
                    new StubPlanner(llmClient),
                    memoryManager,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
            );

            String result = agent.run("请读取测试文件并确认内容");

            // v2（路径 B）：用户输入摘要进 volatile facts、工具结果原文进 recentToolResults，
            // assistant 内容只进 conversationHistory（已不再回写到记忆笔记本）
            assertTrue(result.contains("计划执行完成"));

            String section = memoryManager.buildWorkingMemorySection();
            assertTrue(section.contains("read_file"), "工具调用应记录到 working memory");
            assertTrue(section.contains("plan-memory-content"), "工具返回的文件原文应保留在 working memory");
            assertTrue(llmClient.messagesByCall.size() >= 2);
            String secondSystem = llmClient.messagesByCall.get(1).get(0).content();
            assertTrue(secondSystem.contains("Working Memory"), secondSystem);
            assertTrue(secondSystem.contains("plan-memory-content"),
                    "工具结果写入后，下一轮 task LLM 调用应刷新 Working Memory 段");
            assertTrue(memoryManager.getWorkingMemory().getVolatileFacts().stream()
                    .anyMatch(f -> f.contains("请读取测试文件")),
                    "用户输入摘要应作为 volatile fact 记录");
            assertEquals(0, memoryManager.getLongTermMemory().size(),
                    "Plan 执行不应自动写长期记忆");
        }
    }

    @Test
    void shouldNotExtractFactsWhenPlanIsCanceled() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of());
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory-store-cancel").toFile());
             MemoryManager memoryManager = new MemoryManager(llmClient, 4096, 128000, longTermMemory)) {
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    new ToolRegistry(),
                    new StubPlanner(llmClient),
                    memoryManager,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
            );

            String result = agent.run("列出当前目录的文件");

            assertEquals("⏹️ 已取消本次计划执行。", result);
            assertEquals(0, longTermMemory.size());
        }
    }

    @Test
    void shouldScheduleSessionPreSummaryMaintenanceAfterPlanTurn() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "计划任务结果", null, 20, 10)
        ));
        try (CountingMemoryManager memoryManager = new CountingMemoryManager(llmClient, tempDir.resolve("memory-store-plan").toFile())) {
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    new ToolRegistry(),
                    new StubPlanner(llmClient),
                    memoryManager,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
            );

            agent.run("执行一个计划任务");

            assertEquals(1, memoryManager.asyncMaintenanceCalls.get());
        }
    }

    @Test
    void shouldNotRepeatStreamedTaskOutputInFinalPlanSummary() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "当前目录包含 8 个目录和 8 个文件。",
                        null,
                        60,
                        20
                ))
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("✅ 计划执行完成！", result);
    }

    @Test
    void shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(
                        listener -> {
                            listener.onReasoningDelta("  \n");
                            listener.onContentDelta("我来读取 pom.xml 文件。");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来读取 pom.xml 文件。",
                                "  \n",
                                null,
                                60,
                                20
                        )
                )
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.run("读取 pom.xml");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("任务思考 [task_1]"),
                "空白 reasoning 不应打印空的任务思考标题: " + rendered);
        assertTrue(rendered.contains("任务输出 [task_1]"));
        assertFalse(rendered.contains("任务结果 [task_1]"),
                "tool-call 前后的流式 content 不应被误标成任务结果: " + rendered);
    }

    private record StubResponse(LlmClient.ChatResponse response, boolean streamContent,
                                java.util.function.Consumer<LlmClient.StreamListener> streamScript) {
        private static StubResponse plain(LlmClient.ChatResponse response) {
            return new StubResponse(response, false, null);
        }

        private static StubResponse streamed(LlmClient.ChatResponse response) {
            return new StubResponse(response, true, null);
        }

        private static StubResponse scripted(java.util.function.Consumer<LlmClient.StreamListener> streamScript,
                                             LlmClient.ChatResponse response) {
            return new StubResponse(response, false, streamScript);
        }
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "读取测试文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class CountingMemoryManager extends MemoryManager {
        private final AtomicInteger asyncMaintenanceCalls = new AtomicInteger();

        private CountingMemoryManager(LlmClient llmClient, java.io.File storageDir) {
            super(llmClient, 4096, 128000, new LongTermMemory(storageDir));
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
        private final Queue<StubResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses.stream().map(StubResponse::plain).toList());
        }

        private StubGLMClient(Queue<StubResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        private static StubGLMClient streaming(List<StubResponse> responses) {
            return new StubGLMClient(new ArrayDeque<>(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            StubResponse stubResponse = responses.poll();
            if (stubResponse == null) {
                throw new IOException("缺少预设响应");
            }
            if (stubResponse.streamScript() != null) {
                stubResponse.streamScript().accept(listener);
            } else if (stubResponse.streamContent() && stubResponse.response().content() != null) {
                listener.onContentDelta(stubResponse.response().content());
            }
            return stubResponse.response();
        }
    }
}
