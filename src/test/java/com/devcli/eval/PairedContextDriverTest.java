package com.devcli.eval;

import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PairedContextDriverTest {
    @TempDir Path dir;

    @Test void chunkingPreservesEveryCharacterAndFinalInstruction() {
        String context = "x".repeat(7999) + "\uD83D\uDE00" + "y".repeat(16000);
        var messages = PairedContextDriver.history("PREFIX", context, "QUESTION");
        String joined = messages.subList(1, messages.size() - 1).stream()
                .filter(m -> m.role().equals("user")).map(LlmClient.Message::content).reduce("", String::concat);
        assertEquals("PREFIX" + context, joined);
        assertEquals("QUESTION", messages.get(messages.size() - 1).content());
    }

    @Test void rawMakesOneCallAndReportsActualUsage() throws Exception {
        var job = new ObjectMapper().createObjectNode().put("prefix", "p").put("context", "c").put("suffix", "q");
        AtomicInteger calls = new AtomicInteger();
        var result = PairedContextDriver.run(job, "raw", fake(calls), dir);
        assertEquals(1, calls.get());
        assertEquals(0, result.path("summary_calls").asInt());
        assertEquals(100, result.path("answer_input_tokens").asInt());
        assertEquals(100, result.path("total_input_tokens").asInt());
        assertFalse(result.path("context_changed").asBoolean());
    }

    @Test void usageWrapperPreservesModelBudgets() {
        LlmClient client = fake(new AtomicInteger());
        var counted = new PairedContextDriver.CountingClient(client);
        assertEquals(client.maxContextWindow(), counted.maxContextWindow());
        assertEquals(client.maxOutputTokens(), counted.maxOutputTokens());
    }

    @Test void treatmentInvokesProductionCompactorAndCountsSummarySeparately() throws Exception {
        var job = new ObjectMapper().createObjectNode().put("prefix", "p")
                .put("context", "Historical evidence. ".repeat(3000)).put("suffix", "What happened?");
        AtomicInteger calls = new AtomicInteger();
        var result = PairedContextDriver.run(job, "compact", fake(calls), dir);
        assertTrue(result.path("context_changed").asBoolean());
        assertTrue(result.path("history_summarized").asBoolean());
        assertTrue(result.path("summary_calls").asInt() > 0);
        assertEquals(calls.get() * 100, result.path("total_input_tokens").asInt());
        assertTrue(result.path("after_estimated_tokens").asInt() < result.path("before_estimated_tokens").asInt());
    }

    private static LlmClient fake(AtomicInteger calls) {
        return new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                calls.incrementAndGet();
                return new ChatResponse("assistant", "Historical evidence remains available.", null, 100, 10);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public String getModelName() { return "offline-test"; }
            public String getProviderName() { return "test"; }
            public int maxContextWindow() { return 64_000; }
            public int maxOutputTokens() { return 2_048; }
        };
    }
}
