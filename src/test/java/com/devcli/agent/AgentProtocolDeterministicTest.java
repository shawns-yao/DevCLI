package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.plan.ExecutionPlan;
import com.devcli.plan.Planner;
import com.devcli.plan.Task;
import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.render.Renderer;
import com.devcli.render.StatusInfo;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct、Plan Task 与 SubAgent 的确定性工具协议矩阵。
 *
 * <p>3 条路径 × 4 类场景 × 10 个固定变体，共 120 组。测试只使用脚本模型、
 * 内存工具与门闩调度，不依赖网络、Docker、真实命令或随机延迟。
 */
class AgentProtocolDeterministicTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(5);
    private static final String MEMORY_DIR_PROPERTY = "devcli.memory.dir";
    private static String previousMemoryDir;

    @TempDir
    static Path memoryTempDir;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void isolateLongTermMemory() {
        previousMemoryDir = System.getProperty(MEMORY_DIR_PROPERTY);
        System.setProperty(MEMORY_DIR_PROPERTY, memoryTempDir.resolve("memory").toString());
    }

    @AfterAll
    static void restoreLongTermMemory() {
        if (previousMemoryDir == null) {
            System.clearProperty(MEMORY_DIR_PROPERTY);
        } else {
            System.setProperty(MEMORY_DIR_PROPERTY, previousMemoryDir);
        }
    }

    @TestFactory
    Stream<DynamicTest> deterministicProtocolMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (PathKind path : PathKind.values()) {
            for (ScenarioKind scenario : ScenarioKind.values()) {
                for (int variant = 0; variant < 10; variant++) {
                    int currentVariant = variant;
                    String name = path + " / " + scenario + " / variant-" + (variant + 1);
                    tests.add(DynamicTest.dynamicTest(name, () ->
                            assertTimeoutPreemptively(CASE_TIMEOUT,
                                    () -> executeCase(path, scenario, currentVariant))));
                }
            }
        }
        assertEquals(120, tests.size());
        return tests.stream();
    }

    private void executeCase(PathKind path, ScenarioKind scenario, int variant) throws Exception {
        Path caseRoot = tempDir.resolve(path.name().toLowerCase())
                .resolve(scenario.name().toLowerCase())
                .resolve("variant-" + variant);
        Files.createDirectories(caseRoot);
        try (ProtocolToolRegistry tools = new ProtocolToolRegistry()) {
            tools.setProjectPath(caseRoot.toString());
            Scenario fixture = scenario.create(variant, tools);
            ScriptedLlmClient llm = new ScriptedLlmClient(fixture.responses());

            PathExecution execution = runPath(path, llm, tools);

            assertScenario(scenario, fixture, llm, tools, execution);
        }
    }

    private PathExecution runPath(PathKind path, ScriptedLlmClient llm,
                                  ProtocolToolRegistry tools) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(output, true, StandardCharsets.UTF_8);
        return switch (path) {
            case REACT -> {
                Agent agent = new Agent(llm, tools);
                agent.setRenderer(new SilentRenderer(sink));
                try {
                    yield new PathExecution(agent.run("执行确定性协议场景"),
                            agent.conversationHistorySnapshot());
                } finally {
                    agent.getMemoryManager().close();
                }
            }
            case PLAN_TASK -> {
                try (MemoryManager memoryManager = new MemoryManager(
                        llm,
                        4_096,
                        128_000,
                        new LongTermMemory(Path.of(tools.getProjectPath())
                                .resolve("plan-memory").toFile()))) {
                    PlanExecuteAgent agent = new PlanExecuteAgent(
                            llm,
                            tools,
                            new SingleTaskPlanner(llm),
                            memoryManager,
                            (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                            sink);
                    String result = agent.run("执行确定性协议场景");
                    yield new PathExecution(result, llm.lastMessages());
                }
            }
            case SUB_AGENT -> {
                SubAgent agent = new SubAgent("protocol-worker", AgentRole.WORKER, llm, tools);
                AgentMessage result = agent.execute(
                        AgentMessage.task("protocol-test", "执行确定性协议场景"), sink);
                yield new PathExecution(result.content(), llm.lastMessages());
            }
        };
    }

    private static void assertScenario(ScenarioKind scenario, Scenario fixture,
                                       ScriptedLlmClient llm, ProtocolToolRegistry tools,
                                       PathExecution execution) {
        assertEquals(fixture.responses().size(), llm.callCount(), "脚本响应必须全部消费");
        assertTrue(execution.result() != null, "执行路径必须返回终态");

        List<LlmClient.Message> toolMessages = execution.history().stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
        List<String> toolMessageIds = toolMessages.stream()
                .map(LlmClient.Message::toolCallId)
                .toList();
        List<String> declaredCallIds = execution.history().stream()
                .filter(message -> "assistant".equals(message.role()) && message.toolCalls() != null)
                .flatMap(message -> message.toolCalls().stream())
                .map(LlmClient.ToolCall::id)
                .toList();
        assertEquals(fixture.callIds(), declaredCallIds,
                "写回的工具结果必须对应历史中的 assistant tool_call");
        assertEquals(toolMessageIds.size(), new HashSet<>(toolMessageIds).size(),
                "工具结果不得重复回灌");
        assertTrue(toolMessageIds.stream().allMatch(id -> id != null && !id.isBlank()),
                "工具结果不得成为孤立消息");

        switch (scenario) {
            case SINGLE_SUCCESS -> {
                assertEquals(List.of(fixture.callIds().get(0)), toolMessageIds);
                assertEquals(1, tools.executionCount("probe_0"));
                if (fixture.expectedPayloads().get(0).isEmpty()) {
                    assertEquals("(probe_0 执行完毕无输出)", toolMessages.get(0).content());
                } else if (fixture.expectedPayloads().get(0).length() > 10_000) {
                    assertTrue(toolMessages.get(0).content().startsWith("single-final-"));
                    assertTrue(toolMessages.get(0).content().contains("已截断"),
                            "超大工具结果必须经过结果尺寸治理");
                } else {
                    assertEquals(fixture.expectedPayloads().get(0), toolMessages.get(0).content());
                }
            }
            case MULTI_OUT_OF_ORDER -> {
                assertEquals(fixture.callIds(), toolMessageIds,
                        "工具消息必须按原始 tool_call 顺序归并");
                assertEquals(fixture.completionOrder(), tools.completionOrder(),
                        "门闩必须产生固定完成顺序");
                for (int i = 0; i < fixture.callIds().size(); i++) {
                    assertEquals(1, tools.executionCount("probe_" + i));
                    assertTrue(toolMessages.get(i).content().contains(fixture.expectedPayloads().get(i)));
                }
            }
            case MIXED_RESULTS -> {
                assertEquals(fixture.callIds(), toolMessageIds);
                Map<String, ToolRegistry.ToolExecutionResult> actualResults = resultsById(tools);
                for (int i = 0; i < fixture.callIds().size(); i++) {
                    assertEquals(1, tools.executionCount("probe_" + i));
                    assertTrue(toolMessages.get(i).content().contains(fixture.expectedPayloads().get(i)),
                            "结构化终态对应的结果文本不能丢失");
                    ToolOutput expected = fixture.expectedOutputs().get(i);
                    ToolRegistry.ToolExecutionResult actual = actualResults.get(fixture.callIds().get(i));
                    assertNotNull(actual);
                    assertEquals(expected.status(), actual.status());
                    assertEquals(expected.errorCode(), actual.errorCode());
                    assertEquals(expected.retryable(), actual.retryable());
                }
            }
            case INVALID_THEN_CORRECTED -> {
                assertEquals(fixture.callIds(), toolMessageIds);
                assertEquals(1, tools.executionCount("probe_0"),
                        "非法参数不得触达执行器，修正后只执行一次");
                assertTrue(toolMessages.get(0).content().contains("工具参数校验失败"));
                assertTrue(toolMessages.get(1).content().contains(fixture.expectedPayloads().get(0)));
                assertEquals(ToolStatus.REJECTED,
                        resultsById(tools).get(fixture.callIds().get(0)).status());
                assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                        resultsById(tools).get(fixture.callIds().get(0)).errorCode());
                assertEquals(ToolStatus.SUCCESS,
                        resultsById(tools).get(fixture.callIds().get(1)).status());
            }
        }
    }

    private static Map<String, ToolRegistry.ToolExecutionResult> resultsById(
            ProtocolToolRegistry tools) {
        Map<String, ToolRegistry.ToolExecutionResult> byId = new LinkedHashMap<>();
        for (ToolRegistry.ToolExecutionResult result : tools.results()) {
            byId.put(result.id(), result);
        }
        return byId;
    }

    private enum PathKind {
        REACT,
        PLAN_TASK,
        SUB_AGENT
    }

    private enum ScenarioKind {
        SINGLE_SUCCESS {
            @Override
            Scenario create(int variant, ProtocolToolRegistry tools) throws Exception {
                String payload = singlePayload(variant);
                tools.registerProbe(0, ToolOutput.success(payload), null);
                String callId = "single_" + variant;
                return new Scenario(
                        List.of(
                                response(List.of(call(callId, "probe_0", validArguments(variant))), variant),
                                finalResponse(variant)),
                        List.of(callId),
                        List.of(payload),
                        List.of(ToolOutput.success(payload)),
                        List.of());
            }
        },
        MULTI_OUT_OF_ORDER {
            @Override
            Scenario create(int variant, ProtocolToolRegistry tools) throws Exception {
                List<Integer> releaseOrder = COMPLETION_ORDERS.get(variant);
                DeterministicCompletionGate gate = new DeterministicCompletionGate(4, releaseOrder);
                List<String> ids = new ArrayList<>();
                List<String> payloads = new ArrayList<>();
                List<ToolOutput> outputs = new ArrayList<>();
                List<LlmClient.ToolCall> calls = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String payload = "parallel-" + variant + "-" + i;
                    ToolOutput output = ToolOutput.success(payload);
                    tools.registerProbe(i, output, gate);
                    String id = "parallel_" + variant + "_" + i;
                    ids.add(id);
                    payloads.add(payload);
                    outputs.add(output);
                    calls.add(call(id, "probe_" + i, validArguments(variant * 10 + i)));
                }
                return new Scenario(
                        List.of(response(calls, variant), finalResponse(variant)),
                        ids,
                        payloads,
                        outputs,
                        releaseOrder);
            }
        },
        MIXED_RESULTS {
            @Override
            Scenario create(int variant, ProtocolToolRegistry tools) throws Exception {
                List<ToolOutput> outputs = MIXED_OUTPUTS.get(variant);
                List<String> ids = new ArrayList<>();
                List<String> payloads = new ArrayList<>();
                List<LlmClient.ToolCall> calls = new ArrayList<>();
                for (int i = 0; i < outputs.size(); i++) {
                    ToolOutput output = outputs.get(i);
                    tools.registerProbe(i, output, null);
                    String id = "mixed_" + variant + "_" + i;
                    ids.add(id);
                    payloads.add(output.text());
                    calls.add(call(id, "probe_" + i, validArguments(variant * 10 + i)));
                }
                return new Scenario(
                        List.of(response(calls, variant), finalResponse(variant)),
                        ids,
                        payloads,
                        outputs,
                        List.of());
            }
        },
        INVALID_THEN_CORRECTED {
            @Override
            Scenario create(int variant, ProtocolToolRegistry tools) throws Exception {
                String payload = "corrected-" + variant;
                tools.registerProbe(0, ToolOutput.success(payload), null);
                String invalidId = "invalid_" + variant;
                String correctedId = "corrected_" + variant;
                return new Scenario(
                        List.of(
                                response(List.of(call(invalidId, "probe_0", INVALID_ARGUMENTS.get(variant))), variant),
                                response(List.of(call(correctedId, "probe_0", validArguments(variant))), variant),
                                finalResponse(variant)),
                        List.of(invalidId, correctedId),
                        List.of(payload),
                        List.of(ToolOutput.success(payload)),
                        List.of());
            }
        };

        abstract Scenario create(int variant, ProtocolToolRegistry tools) throws Exception;
    }

    private record Scenario(List<LlmClient.ChatResponse> responses,
                            List<String> callIds,
                            List<String> expectedPayloads,
                            List<ToolOutput> expectedOutputs,
                            List<Integer> completionOrder) {
    }

    private record PathExecution(String result, List<LlmClient.Message> history) {
        private PathExecution {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    private static final class ScriptedLlmClient extends GLMClient {
        private final Queue<LlmClient.ChatResponse> responses;
        private final List<List<LlmClient.Message>> messagesByCall = new ArrayList<>();

        private ScriptedLlmClient(List<LlmClient.ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools,
                                              StreamListener listener) throws IOException {
            if ((tools == null || tools.isEmpty()) && messages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(Message::content)
                    .anyMatch(content -> content != null && content.contains("预摘要维护器"))) {
                return new ChatResponse("assistant", "确定性会话预摘要", null, null, 1, 1);
            }
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        private int callCount() {
            return messagesByCall.size();
        }

        private List<LlmClient.Message> lastMessages() {
            return messagesByCall.isEmpty()
                    ? List.of()
                    : messagesByCall.get(messagesByCall.size() - 1);
        }
    }

    private static final class ProtocolToolRegistry extends ToolRegistry {
        private final Map<String, AtomicInteger> executions = new LinkedHashMap<>();
        private final List<ToolExecutionResult> results = new ArrayList<>();
        private DeterministicCompletionGate gate;

        private ProtocolToolRegistry() {
            super();
        }

        private void registerProbe(int index, ToolOutput output,
                                   DeterministicCompletionGate completionGate) throws Exception {
            String name = "probe_" + index;
            AtomicInteger count = executions.computeIfAbsent(name, ignored -> new AtomicInteger());
            if (completionGate != null) {
                gate = completionGate;
            }
            registerTool(new ToolRegistry.Tool(
                    name,
                    "deterministic protocol probe",
                    JSON.readTree("""
                            {"type":"object","properties":{
                              "value":{"type":"integer"},
                              "mode":{"type":"string","enum":["run"]},
                              "meta":{"type":"object","properties":{
                                "enabled":{"type":"boolean"}
                              },"required":["enabled"],"additionalProperties":false}
                            },"required":["value","mode","meta"],"additionalProperties":false}
                            """),
                    (ToolRegistry.StructuredToolExecutor) args -> {
                        count.incrementAndGet();
                        if (completionGate != null) {
                            completionGate.complete(index);
                        }
                        return output;
                    },
                    ToolRegistry.ToolEffect.READ_ONLY));
        }

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            List<ToolExecutionResult> executed;
            if (gate == null || invocations.size() <= 1) {
                executed = super.executeTools(invocations);
            } else {
                var future = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> super.executeTools(invocations));
                gate.releaseInConfiguredOrder();
                executed = future.join();
            }
            synchronized (results) {
                results.addAll(executed);
            }
            return executed;
        }

        private int executionCount(String name) {
            AtomicInteger count = executions.get(name);
            return count == null ? 0 : count.get();
        }

        private List<Integer> completionOrder() {
            return gate == null ? List.of() : gate.completedOrder();
        }

        private List<ToolExecutionResult> results() {
            synchronized (results) {
                return List.copyOf(results);
            }
        }
    }

    private static final class DeterministicCompletionGate {
        private final CountDownLatch[] started;
        private final CountDownLatch[] released;
        private final CountDownLatch[] completed;
        private final List<Integer> releaseOrder;
        private final List<Integer> observedCompletionOrder = new ArrayList<>();

        private DeterministicCompletionGate(int size, List<Integer> releaseOrder) {
            this.started = latches(size);
            this.released = latches(size);
            this.completed = latches(size);
            this.releaseOrder = List.copyOf(releaseOrder);
        }

        private void complete(int index) {
            started[index].countDown();
            await(released[index], "等待工具释放超时: " + index);
            synchronized (observedCompletionOrder) {
                observedCompletionOrder.add(index);
            }
            completed[index].countDown();
        }

        private void releaseInConfiguredOrder() {
            for (int index = 0; index < started.length; index++) {
                await(started[index], "等待工具启动超时: " + index);
            }
            for (int index : releaseOrder) {
                released[index].countDown();
                await(completed[index], "等待工具完成超时: " + index);
            }
        }

        private List<Integer> completedOrder() {
            synchronized (observedCompletionOrder) {
                return List.copyOf(observedCompletionOrder);
            }
        }

        private static CountDownLatch[] latches(int size) {
            CountDownLatch[] result = new CountDownLatch[size];
            Arrays.setAll(result, ignored -> new CountDownLatch(1));
            return result;
        }

        private static void await(CountDownLatch latch, String message) {
            try {
                assertTrue(latch.await(2, TimeUnit.SECONDS), message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(message, e);
            }
        }
    }

    private static final class SingleTaskPlanner extends Planner {
        private SingleTaskPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("protocol-plan", goal);
            plan.addTask(new Task("protocol-task", "执行确定性工具协议", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class SilentRenderer implements Renderer {
        private final PrintStream out;

        private SilentRenderer(PrintStream out) {
            this.out = out;
        }

        @Override
        public void start() {
        }

        @Override
        public void close() {
        }

        @Override
        public PrintStream stream() {
            return out;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("deterministic test does not approve side effects");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }
    }

    private static LlmClient.ChatResponse response(List<LlmClient.ToolCall> calls, int variant) {
        return new LlmClient.ChatResponse(
                "assistant",
                variant % 2 == 0 ? "执行工具" : "",
                variant % 3 == 0 ? "固定推理片段 " + variant : null,
                calls,
                10 + variant,
                2,
                0);
    }

    private static LlmClient.ChatResponse finalResponse(int variant) {
        return new LlmClient.ChatResponse(
                "assistant", "协议场景完成 " + variant, null, null, 4, 1);
    }

    private static LlmClient.ToolCall call(String id, String name, String arguments) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, arguments));
    }

    private static String validArguments(int value) {
        return "{\"value\":" + value + ",\"mode\":\"run\",\"meta\":{\"enabled\":true}}";
    }

    private static String singlePayload(int variant) {
        return switch (variant) {
            case 0 -> "single-plain";
            case 1 -> "single 中文";
            case 2 -> "single\nmultiline";
            case 3 -> "";
            case 4 -> "single-with-spaces   ";
            case 5 -> "single-json-{\"ok\":true}";
            case 6 -> "single-unicode-ＡＰＩ";
            case 7 -> "single-path-C:\\Temp\\A.txt";
            case 8 -> "single-long-" + "x".repeat(64);
            case 9 -> "single-final-" + "x".repeat(12_100);
            default -> throw new IllegalArgumentException("variant");
        };
    }

    private static ToolOutput error(String text) {
        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, text, true);
    }

    private static ToolOutput rejected(String text) {
        return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, text);
    }

    private static final List<List<Integer>> COMPLETION_ORDERS = List.of(
            List.of(0, 1, 2, 3),
            List.of(3, 2, 1, 0),
            List.of(1, 0, 3, 2),
            List.of(2, 3, 0, 1),
            List.of(1, 3, 0, 2),
            List.of(2, 0, 3, 1),
            List.of(3, 1, 2, 0),
            List.of(0, 2, 1, 3),
            List.of(2, 1, 0, 3),
            List.of(3, 0, 2, 1));

    private static final List<List<ToolOutput>> MIXED_OUTPUTS = List.of(
            List.of(ToolOutput.success("ok-0"), error("error-0")),
            List.of(error("error-1"), ToolOutput.success("ok-1")),
            List.of(ToolOutput.success("ok-2"), rejected("rejected-2")),
            List.of(rejected("rejected-3"), ToolOutput.success("ok-3")),
            List.of(ToolOutput.success("ok-4"), ToolOutput.cancelled("cancelled-4")),
            List.of(ToolOutput.cancelled("cancelled-5"), ToolOutput.success("ok-5")),
            List.of(ToolOutput.success("ok-6"), ToolOutput.timedOut("timeout-6")),
            List.of(ToolOutput.timedOut("timeout-7"), ToolOutput.success("ok-7")),
            List.of(error("error-8"), rejected("rejected-8"), ToolOutput.success("ok-8")),
            List.of(ToolOutput.success("ok-9"), ToolOutput.timedOut("timeout-9"), error("error-9")));

    private static final List<String> INVALID_ARGUMENTS = List.of(
            "{not-json",
            "{\"mode\":\"run\",\"meta\":{\"enabled\":true}}",
            "{\"value\":\"1\",\"mode\":\"run\",\"meta\":{\"enabled\":true}}",
            "{\"value\":1,\"mode\":\"stop\",\"meta\":{\"enabled\":true}}",
            "{\"value\":1,\"mode\":\"run\",\"meta\":{\"enabled\":true},\"extra\":1}",
            "{\"value\":1,\"mode\":\"run\",\"meta\":{}}",
            "{\"value\":1,\"mode\":\"run\",\"meta\":{\"enabled\":\"yes\"}}",
            "{\"value\":1,\"mode\":\"run\",\"meta\":true}",
            "[]",
            "null");
}
