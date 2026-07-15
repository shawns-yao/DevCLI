package com.devcli.agent;

import com.devcli.hook.HookDefinition;
import com.devcli.hook.HookDispatcher;
import com.devcli.hook.HookEvent;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionEngineHookTest {

    @Test
    void requiredHookFailureUsesNormalEngineFailureExit() {
        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient llm = new StubLlmClient(llmCalls);
        try (FailingRegistry registry = new FailingRegistry()) {
            HookDefinition hook = new HookDefinition(
                    "gate", "gate", HookEvent.TURN_START, true,
                    "gate", null, HookDefinition.FailureMode.REQUIRED, false);
            HookLifecycle lifecycle = HookLifecycle.create(
                    HookDispatcher.create(registry, List.of(hook)),
                    HookDispatcher.HookContext.empty());
            RecordingDelegate delegate = new RecordingDelegate();

            String result = new AgentExecutionEngine<String>(
                    llm, new AgentBudget(1_000, 3, 10), lifecycle).run(delegate);

            assertTrue(result.contains("Hook 执行失败"));
            assertEquals(0, llmCalls.get());
        }
    }

    @Test
    void requiredAgentEndHookAlsoUsesNormalFailureExit() {
        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient llm = new StubLlmClient(llmCalls);
        try (FailingRegistry registry = new FailingRegistry()) {
            HookDefinition hook = new HookDefinition(
                    "gate", "gate", HookEvent.AGENT_END, true,
                    "gate", null, HookDefinition.FailureMode.REQUIRED, false);
            HookLifecycle lifecycle = HookLifecycle.create(
                    HookDispatcher.create(registry, List.of(hook)),
                    HookDispatcher.HookContext.empty());

            String result = new AgentExecutionEngine<String>(
                    llm, new AgentBudget(1_000, 3, 10), lifecycle)
                    .run(new RecordingDelegate());

            assertTrue(result.contains("Hook 执行失败"));
            assertEquals(1, llmCalls.get());
        }
    }

    private static final class FailingRegistry extends ToolRegistry {
        @Override
        public ToolEffect toolEffect(String name) {
            return ToolEffect.READ_ONLY;
        }

        @Override
        public ToolOutput executeToolOutput(String name, String argumentsJson) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "gate rejected", false);
        }
    }

    private static final class RecordingDelegate implements AgentExecutionEngine.Delegate<String> {
        private final List<LlmClient.Message> history = new ArrayList<>(
                List.of(LlmClient.Message.system("system")));

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
            return List.of();
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
            return error.getMessage();
        }
    }

    private static final class StubLlmClient implements LlmClient {
        private final AtomicInteger calls;

        private StubLlmClient(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(
                List<Message> messages, List<Tool> tools, StreamListener listener) {
            calls.incrementAndGet();
            return new ChatResponse("assistant", "done", List.of(), 1, 1);
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
