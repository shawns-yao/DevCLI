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
                delegate.runEvents.stream().map(Object::getClass).toList());
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
