package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.llm.SamplingRequestCoordinator;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionEngineTest {

    @Test
    void ownsTheSharedLlmToolLoopAndMessageProtocol() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "reading", "reasoning", List.of(call), 10, 2),
                new LlmClient.ChatResponse("assistant", "done", null, null, 4, 1)
        ));
        AgentBudget budget = new AgentBudget(1_000, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate();

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("done", result);
        assertEquals(2, budget.iteration());
        assertEquals(14, budget.totalInputTokens());
        assertEquals(3, budget.totalOutputTokens());
        assertEquals(List.of("before:1", "tools:1", "before:2", "complete"), delegate.events);
        assertEquals(List.of("system", "assistant", "tool", "assistant"),
                delegate.history.stream().map(LlmClient.Message::role).toList());
        assertEquals(List.of(LlmClient.ToolChoice.REQUIRED, LlmClient.ToolChoice.AUTO), llm.toolChoices);
        assertEquals(List.of(RunEvent.ToolCalls.class, RunEvent.ToolResults.class),
                delegate.runEvents.stream()
                        .filter(event -> event instanceof RunEvent.ToolCalls
                                || event instanceof RunEvent.ToolResults)
                        .map(Object::getClass)
                        .toList());
        List<RunEvent.ModelContext> contexts = delegate.runEvents.stream()
                .filter(RunEvent.ModelContext.class::isInstance)
                .map(RunEvent.ModelContext.class::cast)
                .toList();
        assertEquals(2, contexts.size());
        assertEquals("SYSTEM_INTERNAL", contexts.getFirst().messages().getFirst().source());
        assertTrue(delegate.runEvents.stream().anyMatch(RunEvent.ModelUsage.class::isInstance));
        assertEquals(List.of(
                        RunEvent.ExecutionState.THINKING,
                        RunEvent.ExecutionState.TOOL_EXECUTING,
                        RunEvent.ExecutionState.TOOL_RESULTS_PAIRED,
                        RunEvent.ExecutionState.THINKING,
                        RunEvent.ExecutionState.COMPLETED),
                delegate.runEvents.stream()
                        .filter(RunEvent.ExecutionStateChanged.class::isInstance)
                        .map(RunEvent.ExecutionStateChanged.class::cast)
                        .map(RunEvent.ExecutionStateChanged::state)
                        .toList());
    }

    @Test
    void registersAndCleansSamplingRequestAroundEachModelCall() {
        SamplingRequestCoordinator coordinator = new SamplingRequestCoordinator();
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)
        ), () -> assertEquals(1, coordinator.activeCount()));

        String result = new AgentExecutionEngine<String>(
                llm, new AgentBudget(100, 2, 1), null, coordinator).run(new RecordingDelegate());

        assertEquals("done", result);
        assertEquals(0, coordinator.activeCount());
    }

    @Test
    void canCompleteImmediatelyAfterSuccessfulToolResults() {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", "reasoning", List.of(call), 10, 2)
        ));
        AgentBudget budget = new AgentBudget(1_000, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate(true);

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("tool-complete", result);
        assertEquals(1, budget.iteration());
        assertEquals(List.of("before:1", "tools:1", "tool-complete"), delegate.events);
        assertEquals(List.of("system", "assistant", "tool"),
                delegate.history.stream().map(LlmClient.Message::role).toList());
        assertEquals(List.of(LlmClient.ToolChoice.REQUIRED), llm.toolChoices);
    }

    @Test
    void repeatToolAdvisorInjectsReminderAndLetsTheLoopContinue() {
        ScriptedClient llm = new ScriptedClient(List.of(
                toolCallResponse("read_file", "{\"path\":\"a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"a.txt\"}"),
                toolCallResponse("read_file", "{\"path\":\"b.txt\"}"),
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)
        ));
        AgentBudget budget = new AgentBudget(1_000_000, 3, 10);
        SequenceDelegate delegate = new SequenceDelegate(List.of(
                toolResult("read_file", "{\"path\":\"a.txt\"}"),
                toolResult("read_file", "{\"path\":\"a.txt\"}"),
                toolResult("read_file", "{\"path\":\"a.txt\"}"),
                toolResult("read_file", "{\"path\":\"b.txt\"}")));

        String result = new AgentExecutionEngine<String>(llm, budget).run(delegate);

        assertEquals("done", result);
        // 第 3 次连续相同调用后注入温和提醒，循环没有被停滞检测踢出
        List<LlmClient.Message> reminders = delegate.history.stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null
                        && m.content().contains("系统提醒"))
                .toList();
        assertEquals(1, reminders.size());
        assertTrue(reminders.get(0).content().contains("read_file"));
        assertEquals(LlmClient.MessageSource.PLUGIN, reminders.get(0).source());
        assertEquals("assistant", delegate.history.get(delegate.history.size() - 1).role());
    }

    @Test
    void repeatToolAdvisorDefersStagnationExitUntilThresholdsExhausted() {
        List<LlmClient.ChatResponse> responses = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            responses.add(toolCallResponse("read_file", "{\"path\":\"a.txt\"}"));
        }
        AgentBudget budget = new AgentBudget(1_000_000, 3, 10);
        RecordingDelegate delegate = new RecordingDelegate();

        String result = new AgentExecutionEngine<String>(new ScriptedClient(responses), budget).run(delegate);

        // 3/5/8 三次提醒注入后，第 9 次仍重复，停滞检测最终兜底
        assertEquals("budget", result);
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
        long reminders = delegate.history.stream()
                .filter(m -> "user".equals(m.role()) && m.content() != null
                        && m.content().contains("系统提醒"))
                .count();
        assertEquals(3, reminders);
        assertTrue(delegate.runEvents.stream()
                .filter(RunEvent.CustomMessage.class::isInstance)
                .map(RunEvent.CustomMessage.class::cast)
                .anyMatch(event -> "tool_loop_guard".equals(event.messageType())
                        && "CIRCUIT_BREAKER".equals(event.attributes().get("action"))));
    }

    @Test
    void reconcilesMalformedDelegateResultsBeforeWritingToolMessages() {
        List<LlmClient.ToolCall> calls = List.of(
                new LlmClient.ToolCall("call_a",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}")),
                new LlmClient.ToolCall("call_b",
                        new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}")));
        ScriptedClient llm = new ScriptedClient(List.of(
                new LlmClient.ChatResponse("assistant", "", "reasoning", calls, 1, 1),
                new LlmClient.ChatResponse("assistant", "done", null, null, 1, 1)));
        MalformedResultDelegate delegate = new MalformedResultDelegate();

        String result = new AgentExecutionEngine<String>(
                llm, new AgentBudget(1_000, 3, 10)).run(delegate);

        assertEquals("done", result);
        List<LlmClient.Message> toolMessages = delegate.history.stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
        assertEquals(List.of("call_a", "call_b"),
                toolMessages.stream().map(LlmClient.Message::toolCallId).toList());
        assertTrue(toolMessages.get(0).content().contains("未返回结果"));
        assertEquals("b", toolMessages.get(1).content());
        assertTrue(delegate.runEvents.stream()
                .filter(RunEvent.CustomMessage.class::isInstance)
                .map(RunEvent.CustomMessage.class::cast)
                .anyMatch(event -> "tool_result_pairing_anomaly".equals(event.messageType())
                        && "3".equals(event.attributes().get("count"))));
    }

    private static LlmClient.ChatResponse toolCallResponse(String tool, String arguments) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call_1", new LlmClient.ToolCall.Function(tool, arguments));
        return new LlmClient.ChatResponse("assistant", "", "reasoning", List.of(call), 10, 2);
    }

    private static ToolRegistry.ToolExecutionResult toolResult(String tool, String arguments) {
        return new ToolRegistry.ToolExecutionResult(
                "call_1", tool, arguments, "content", 1,
                ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of());
    }

    private static final class SequenceDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history =
                new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final Iterator<ToolRegistry.ToolExecutionResult> results;

        private SequenceDelegate(List<ToolRegistry.ToolExecutionResult> results) {
            this.results = results.iterator();
        }

        @Override
        public List<LlmClient.Message> history() {
            return history;
        }

        @Override
        public List<LlmClient.Tool> toolDefinitions(int iteration) {
            return List.of();
        }

        @Override
        public LlmClient.StreamListener streamListener() {
            return LlmClient.StreamListener.NO_OP;
        }

        @Override
        public void beforeIteration(int iteration, AgentBudget budget) {
        }

        @Override
        public List<ToolRegistry.ToolExecutionResult> executeTools(
                List<LlmClient.ToolCall> toolCalls, int iteration) {
            return List.of(results.next());
        }

        @Override
        public String completed(LlmClient.ChatResponse response, AgentBudget budget) {
            return response.content();
        }

        @Override
        public String cancelled(AgentBudget budget) {
            return "cancelled";
        }

        @Override
        public String budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget) {
            return "budget";
        }

        @Override
        public String iterationLimitReached(AgentBudget budget) {
            return "limit";
        }

        @Override
        public String failed(IOException error, AgentBudget budget) {
            return "failed";
        }
    }

    private static final class RecordingDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history = new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final List<String> events = new ArrayList<>();
        private final List<RunEvent> runEvents = new ArrayList<>();
        private final boolean completeAfterTools;

        private RecordingDelegate() {
            this(false);
        }

        private RecordingDelegate(boolean completeAfterTools) {
            this.completeAfterTools = completeAfterTools;
        }

        @Override
        public List<LlmClient.Message> history() {
            return history;
        }

        @Override
        public List<LlmClient.Tool> toolDefinitions(int iteration) {
            return List.of();
        }

        @Override
        public LlmClient.StreamListener streamListener() {
            return LlmClient.StreamListener.NO_OP;
        }

        @Override
        public RunEventSink eventSink() {
            return runEvents::add;
        }

        @Override
        public LlmClient.ToolChoice toolChoice(int iteration) {
            return iteration == 1 ? LlmClient.ToolChoice.REQUIRED : LlmClient.ToolChoice.AUTO;
        }

        @Override
        public void beforeIteration(int iteration, AgentBudget budget) {
            events.add("before:" + iteration);
        }

        @Override
        public List<ToolRegistry.ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                                   int iteration) {
            events.add("tools:" + iteration);
            return List.of(new ToolRegistry.ToolExecutionResult(
                    "call_1", "read_file", "{\"path\":\"a.txt\"}", "content",
                    1, ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()));
        }

        @Override
        public Optional<String> completedAfterToolResults(
                LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> toolResults,
                int iteration,
                AgentBudget budget) {
            if (!completeAfterTools) {
                return Optional.empty();
            }
            events.add("tool-complete");
            return Optional.of("tool-complete");
        }

        @Override
        public String completed(LlmClient.ChatResponse response, AgentBudget budget) {
            events.add("complete");
            return response.content();
        }

        @Override
        public String cancelled(AgentBudget budget) {
            return "cancelled";
        }

        @Override
        public String budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget) {
            return "budget";
        }

        @Override
        public String iterationLimitReached(AgentBudget budget) {
            return "limit";
        }

        @Override
        public String failed(IOException error, AgentBudget budget) {
            return "failed";
        }
    }

    private static final class MalformedResultDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history =
                new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final List<RunEvent> runEvents = new ArrayList<>();

        @Override
        public List<LlmClient.Message> history() {
            return history;
        }

        @Override
        public List<LlmClient.Tool> toolDefinitions(int iteration) {
            return List.of();
        }

        @Override
        public LlmClient.StreamListener streamListener() {
            return LlmClient.StreamListener.NO_OP;
        }

        @Override
        public RunEventSink eventSink() {
            return runEvents::add;
        }

        @Override
        public void beforeIteration(int iteration, AgentBudget budget) {
        }

        @Override
        public List<ToolRegistry.ToolExecutionResult> executeTools(
                List<LlmClient.ToolCall> toolCalls, int iteration) {
            return List.of(
                    new ToolRegistry.ToolExecutionResult(
                            "call_b", "list_dir", "{\"path\":\".\"}", "b", 1,
                            ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()),
                    new ToolRegistry.ToolExecutionResult(
                            "unknown", "grep_code", "{}", "unknown", 1,
                            ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()),
                    new ToolRegistry.ToolExecutionResult(
                            "call_b", "list_dir", "{\"path\":\".\"}", "duplicate", 1,
                            ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()));
        }

        @Override
        public String completed(LlmClient.ChatResponse response, AgentBudget budget) {
            return response.content();
        }

        @Override
        public String cancelled(AgentBudget budget) {
            return "cancelled";
        }

        @Override
        public String budgetExceeded(AgentBudget.ExitReason reason, AgentBudget budget) {
            return "budget";
        }

        @Override
        public String iterationLimitReached(AgentBudget budget) {
            return "limit";
        }

        @Override
        public String failed(IOException error, AgentBudget budget) {
            return "failed";
        }
    }

    private static final class ScriptedClient extends GLMClient {
        private final Iterator<LlmClient.ChatResponse> responses;
        private final List<LlmClient.ToolChoice> toolChoices = new ArrayList<>();
        private final Runnable beforeResponse;

        private ScriptedClient(List<LlmClient.ChatResponse> responses) {
            this(responses, () -> { });
        }

        private ScriptedClient(List<LlmClient.ChatResponse> responses, Runnable beforeResponse) {
            super("test-key");
            this.responses = responses.iterator();
            this.beforeResponse = beforeResponse;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools, listener, LlmClient.ToolChoice.AUTO);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener, LlmClient.ToolChoice toolChoice) throws IOException {
            toolChoices.add(toolChoice);
            beforeResponse.run();
            if (!responses.hasNext()) {
                throw new IOException("script exhausted");
            }
            return responses.next();
        }
    }
}
