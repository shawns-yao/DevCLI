package com.devcli.runtime;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSessionRuntimeTransactionTest {
    @Test
    void headlessSessionCommitsReactWriteThroughPatchSet(@TempDir Path project) throws Exception {
        LlmClient client = new ScriptedClient(
                new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(
                        "call-1", new LlmClient.ToolCall.Function("write_file",
                        "{\"path\":\"created.txt\",\"content\":\"value\"}"))), 0, 0),
                new LlmClient.ChatResponse("assistant", "done", List.of(), 0, 0));

        try (AgentSessionRuntime session = AgentSessionRuntime.create(
                client, project, com.devcli.runtime.event.RunEventSink.NO_OP)) {
            AgentSessionRuntime.RunResult result = session.runBlocking("create file");

            assertEquals("done", result.output());
            assertEquals("value", Files.readString(project.resolve("created.txt")));
        }
    }

    private static final class ScriptedClient implements LlmClient {
        private final java.util.ArrayDeque<ChatResponse> responses;

        private ScriptedClient(ChatResponse... responses) {
            this.responses = new java.util.ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return responses.removeFirst();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            return responses.removeFirst();
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
