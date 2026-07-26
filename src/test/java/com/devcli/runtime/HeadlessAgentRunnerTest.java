package com.devcli.runtime;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
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
        // 当轮上下文快照前置在当轮 user 消息里，用户原文仍须落在末尾
        assertTrue(messages.get(3).content().endsWith("now"), messages.get(3).content());
    }

    @Test
    void detailedRunReturnsFinalHistoryWithoutForcingCheckpointBelowThreshold(@TempDir Path projectRoot) {
        LlmClient client = new RecordingLlmClient(new AtomicReference<>());

        HeadlessAgentRunner.RunResult result = HeadlessAgentRunner.runDetailed(
                client, projectRoot, "hello", List.of(), 100_000);

        assertTrue(result.output().contains("done"));
        assertTrue(result.history().stream().anyMatch(message ->
                "user".equals(message.role()) && message.content() != null
                        && message.content().endsWith("hello")));
        assertTrue(result.history().stream().anyMatch(message ->
                "assistant".equals(message.role()) && "done".equals(message.content())));
        assertEquals(false, result.compacted());
    }

    @Test
    void forwardsModelStreamAsTypedRunEvents(@TempDir Path projectRoot) {
        List<RunEvent> events = new ArrayList<>();
        LlmClient client = new RecordingLlmClient(new AtomicReference<>()) {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                listener.onReasoningDelta("analysis");
                listener.onContentDelta("streamed answer");
                return new ChatResponse("assistant", "streamed answer", List.of(), 1, 1);
            }
        };

        HeadlessAgentRunner.runDetailed(
                client, projectRoot, "hello", List.of(), 100_000, events::add);

        assertEquals(List.of(RunEvent.ReasoningDelta.class, RunEvent.MessageDelta.class),
                events.stream().map(Object::getClass).toList());
    }

    @Test
    void keepsFinalAssistantOutputWhenOnlyReasoningWasStreamed(@TempDir Path projectRoot) {
        LlmClient client = new RecordingLlmClient(new AtomicReference<>()) {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                listener.onReasoningDelta("analysis only");
                return new ChatResponse("assistant", "final answer", List.of(), 1, 1);
            }
        };

        HeadlessAgentRunner.RunResult result = HeadlessAgentRunner.runDetailed(
                client, projectRoot, "hello", List.of(), 100_000, event -> { });

        assertEquals("final answer", result.output());
    }

    @Test
    void detectsCompactionCreatedDuringTurn() {
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        List<LlmClient.Message> before = List.of(
                LlmClient.Message.user("plain"));
        List<LlmClient.Message> after = List.of(
                LlmClient.Message.user("[已压缩的历史对话摘要]\n"
                        + metadata.renderBoundaryBlock() + "\nsummary"));

        assertTrue(HeadlessAgentRunner.hasNewCompactionBoundary(before, after));
        assertEquals(false, HeadlessAgentRunner.hasNewCompactionBoundary(after, after));
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
