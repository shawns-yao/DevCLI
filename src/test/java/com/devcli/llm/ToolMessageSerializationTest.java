package com.devcli.llm;

import com.devcli.memory.TokenBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ToolMessageSerializationTest {
    @Test void anthropicToolContentAndEstimateIgnoreUnsentParts() throws Exception {
        var tool = new LlmClient.Message("tool", "actual result", null, null, "call", List.of(
                LlmClient.ContentPart.text("unused text".repeat(10_000)),
                LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")));
        assertEquals(TokenBudget.estimateMessagesTokens(List.of(LlmClient.Message.tool("call", "actual result"))),
                TokenBudget.estimateMessagesTokens(List.of(tool)));
        assertEquals("actual result", tool.withoutImageContent().content());
        assertNull(tool.withoutImageContent().contentParts());
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                    event: message_start
                    data: {"type":"message_start","message":{"role":"assistant","usage":{"input_tokens":10,"output_tokens":0}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """));
            new AnthropicClient("test-key", "test-model", server.url("/").toString()).chat(List.of(tool), List.of());
            var body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertEquals("actual result", body.path("messages").get(0).path("content").get(0).path("content").asText());
            assertFalse(body.toString().contains("aGVsbG8="));
            assertFalse(body.toString().contains("unused text"));
        }
    }
}
