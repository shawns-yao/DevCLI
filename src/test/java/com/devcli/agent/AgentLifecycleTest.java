package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLifecycleTest {

    @Test
    void closesToolRegistryWhenAgentOwnsIt() {
        TrackingToolRegistry registry = new TrackingToolRegistry();

        try (Agent ignored = new Agent(new NoopLlmClient(), registry, true)) {
        }

        assertTrue(registry.closed);
    }

    @Test
    void leavesInjectedToolRegistryOpen() {
        TrackingToolRegistry registry = new TrackingToolRegistry();
        try {
            try (Agent ignored = new Agent(new NoopLlmClient(), registry)) {
            }

            assertFalse(registry.closed);
        } finally {
            registry.close();
        }
    }

    private static final class TrackingToolRegistry extends ToolRegistry {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    private static final class NoopLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
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
