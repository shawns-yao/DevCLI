package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
    }

    private static final class RecordingDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history = new ArrayList<>(List.of(LlmClient.Message.system("system")));
        private final List<String> events = new ArrayList<>();

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

        private ScriptedClient(List<LlmClient.ChatResponse> responses) {
            super("test-key");
            this.responses = responses.iterator();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools, listener, LlmClient.ToolChoice.AUTO);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener, LlmClient.ToolChoice toolChoice) throws IOException {
            toolChoices.add(toolChoice);
            if (!responses.hasNext()) {
                throw new IOException("script exhausted");
            }
            return responses.next();
        }
    }
}
