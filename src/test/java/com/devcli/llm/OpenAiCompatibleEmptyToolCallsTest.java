package com.devcli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAiCompatibleEmptyToolCallsTest {

    @Test
    void acceptsContentOnlyStreamWithoutToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}\n\n"
                            + "data: [DONE]\n\n"));
            server.start();

            OpenAiClient client = new OpenAiClient(
                    "test-key", "test-model", server.url("/v1").toString());
            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("ping")), null);

            assertEquals("OK", response.content());
            assertNull(response.toolCalls());
        }
    }
}
