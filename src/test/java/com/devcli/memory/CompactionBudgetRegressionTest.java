package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionBudgetRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void oversizedPreSummaryRequestIsRejectedBeforeLlmCall() {
        AtomicInteger calls = new AtomicInteger();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new RecordingClient(calls, new AtomicReference<>()),
                     4_096, 8_000, longTermMemory)) {
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.system("S"));
            for (int i = 0; i < 100; i++) {
                history.add(LlmClient.Message.user("Q" + i + " " + "x".repeat(2_000)));
                history.add(LlmClient.Message.assistant("A" + i));
            }

            MemoryManager.SessionPreSummaryMaintenanceResult result =
                    memoryManager.maintainSessionPreSummaryAfterTurn(history, 4, 0);

            assertEquals(0, calls.get());
            assertTrue(result != MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED);
        }
    }

    @Test
    void preSummaryIncludesToolCallArguments() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<LlmClient.Message>> captured = new AtomicReference<>();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new RecordingClient(calls, captured),
                     4_096, 128_000, longTermMemory)) {
            List<LlmClient.Message> history = List.of(
                    LlmClient.Message.system("S"),
                    LlmClient.Message.user("检查文件"),
                    LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                            "call-1", new LlmClient.ToolCall.Function(
                            "read_file", "{\"path\":\"src/App.java\"}")))));

            memoryManager.maintainSessionPreSummaryAfterTurn(history, 4, 0);

            assertEquals(1, calls.get());
            assertTrue(captured.get().get(1).content().contains("src/App.java"),
                    captured.get().get(1).content());
        }
    }

    @Test
    void summaryCallGuardPreventsUnbudgetedCompactionCall() {
        AtomicInteger calls = new AtomicInteger();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(
                new RecordingClient(calls, new AtomicReference<>()), 2, true);
        compactor.setSummaryCallGuard(() -> false);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("q" + i + " " + "x".repeat(2_000)));
            history.add(LlmClient.Message.assistant("a" + i));
        }

        assertTrue(!compactor.compactIfNeeded(history, 100));
        assertEquals(0, calls.get());
    }

    private static final class RecordingClient implements LlmClient {
        private final AtomicInteger calls;
        private final AtomicReference<List<LlmClient.Message>> captured;

        private RecordingClient(AtomicInteger calls,
                                AtomicReference<List<LlmClient.Message>> captured) {
            this.calls = calls;
            this.captured = captured;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls.incrementAndGet();
            captured.set(List.copyOf(messages));
            return new ChatResponse("assistant", "摘要", List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() { return "recording"; }

        @Override
        public String getProviderName() { return "test"; }

        @Override
        public int maxContextWindow() { return 128_000; }
    }
}
