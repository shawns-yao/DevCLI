package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionSummaryCacheTest {

    @Test
    void imageContentParticipatesInPreSummaryFingerprint() {
        CompactionSummaryCache cache = new CompactionSummaryCache();
        List<LlmClient.Message> original = List.of(
                LlmClient.Message.user(List.of(LlmClient.ContentPart.imageBase64("AQ==", "image/png"))));
        List<LlmClient.Message> changed = List.of(
                LlmClient.Message.user(List.of(LlmClient.ContentPart.imageBase64("Ag==", "image/png"))));

        cache.recordPreSummary(original, "summary");

        assertTrue(cache.findReusablePreSummary(original).isPresent());
        assertTrue(cache.findReusablePreSummary(changed).isEmpty());
    }

    @Test
    void modelIdentityParticipatesInPreSummaryReuse() {
        CompactionSummaryCache cache = new CompactionSummaryCache();
        List<LlmClient.Message> messages = List.of(LlmClient.Message.user("same"));
        LlmClient first = new TestClient("provider-a", "model-a");
        LlmClient second = new TestClient("provider-b", "model-b");

        cache.recordPreSummary(messages, "summary", first);

        assertTrue(cache.findReusablePreSummary(messages, first).isPresent());
        assertTrue(cache.findReusablePreSummary(messages, second).isEmpty());
    }

    private record TestClient(String provider, String model) implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return model;
        }

        @Override
        public String getProviderName() {
            return provider;
        }
    }
}
