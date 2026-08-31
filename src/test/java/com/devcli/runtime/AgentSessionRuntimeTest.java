package com.devcli.runtime;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentSessionRuntimeTest {

    @Test
    void synchronousCliEntryKeepsCallerRunContextAndClosesActiveState() {
        try (AgentSessionRuntime session = AgentSessionRuntime.create(
                new NoopLlmClient(), Path.of("."), null)) {
            AgentSessionRuntime.RunResult result = session.runInCurrentContext("hello");

            assertNotNull(result);
            assertFalse(result.cancelled());
            assertFalse(session.isRunning());
        }
    }

    @Test
    void streamedEmptyReturnRecoversFinalAssistantMessage() {
        String output = AgentSessionRuntime.resolveOutput("", List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.user("question"),
                LlmClient.Message.assistant("tool reasoning", List.of()),
                LlmClient.Message.assistant("final answer")
        ));

        assertEquals("final answer", output);
    }

    private static final class NoopLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 8_000;
        }
    }
}
