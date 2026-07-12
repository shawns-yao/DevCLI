package com.devcli.runtime;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessAgentRunnerTest {

    @Test
    void bindsRunContextDuringHeadlessExecutionAndClearsItAfterward(@TempDir Path projectRoot) throws Exception {
        AtomicReference<RunContext> seen = new AtomicReference<>();
        LlmClient client = new RecordingLlmClient(seen);

        String result = HeadlessAgentRunner.run(client, projectRoot, "hello", List.of());

        assertTrue(result.contains("done"));
        assertEquals(projectRoot.toAbsolutePath().normalize(), seen.get().projectPath());
        assertNull(CancellationContext.currentRun());
    }

    @Test
    void reusesCallerRunContext(@TempDir Path projectRoot) throws Exception {
        AtomicReference<RunContext> seen = new AtomicReference<>();
        LlmClient client = new RecordingLlmClient(seen);

        try (RunContext outer = CancellationContext.startRunContext(projectRoot)) {
            HeadlessAgentRunner.run(client, projectRoot, "hello", List.of());

            assertSame(outer, seen.get());
            assertSame(outer, CancellationContext.currentRun());
        }
    }

    @Test
    void seedsProvidedHistoryBeforeCurrentPrompt(@TempDir Path projectRoot) throws Exception {
        AtomicReference<List<LlmClient.Message>> seenMessages = new AtomicReference<>();
        LlmClient client = new RecordingLlmClient(new AtomicReference<>()) {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                seenMessages.set(List.copyOf(messages));
                return new ChatResponse("assistant", "done", List.of(), 1, 1);
            }
        };
        List<LlmClient.Message> seed = List.of(
                LlmClient.Message.user("before"),
                LlmClient.Message.assistant("answer")
        );

        HeadlessAgentRunner.run(client, projectRoot, "now", seed);

        List<LlmClient.Message> messages = seenMessages.get();
        assertSame(seed.get(0), messages.get(1));
        assertSame(seed.get(1), messages.get(2));
        assertEquals("now", messages.get(3).content());
    }

    private static class RecordingLlmClient implements LlmClient {
        private final AtomicReference<RunContext> seen;

        private RecordingLlmClient(AtomicReference<RunContext> seen) {
            this.seen = seen;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            seen.set(CancellationContext.currentRun());
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

        @Override
        public int maxContextWindow() {
            return 8_000;
        }
    }
}
