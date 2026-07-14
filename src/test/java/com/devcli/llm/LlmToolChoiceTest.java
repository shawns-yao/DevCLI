package com.devcli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmToolChoiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<LlmClient.Message> MESSAGES = List.of(
            LlmClient.Message.system("system"),
            LlmClient.Message.user("execute"));
    private static final List<LlmClient.Tool> TOOLS = List.of(
            new LlmClient.Tool("write_file", "write", MAPPER.createObjectNode()));

    @Test
    void defaultOverloadScopesChoiceWithoutBypassingExistingChatOverride() throws IOException {
        RecordingClient client = new RecordingClient();

        client.chat(MESSAGES, TOOLS, LlmClient.StreamListener.NO_OP, LlmClient.ToolChoice.REQUIRED);

        assertEquals(List.of(LlmClient.ToolChoice.REQUIRED), client.observedChoices);
        assertEquals(LlmClient.ToolChoice.AUTO, LlmToolChoiceContext.current());
    }

    @Test
    void anthropicRequiredChoiceUsesAnyToolPolicy() {
        AnthropicClient client = new AnthropicClient("test-key", "test-model", "https://example.com");

        ObjectNode required = client.buildRequestBody(MESSAGES, TOOLS, LlmClient.ToolChoice.REQUIRED);
        ObjectNode automatic = client.buildRequestBody(MESSAGES, TOOLS, LlmClient.ToolChoice.AUTO);

        assertEquals("any", required.path("tool_choice").path("type").asText());
        assertTrue(automatic.path("tool_choice").isMissingNode());
    }

    @Test
    void openAiRequiredChoiceUsesRequiredPolicy() {
        TestOpenAiClient client = new TestOpenAiClient();

        ObjectNode required = client.requestBody(LlmClient.ToolChoice.REQUIRED);
        ObjectNode automatic = client.requestBody(LlmClient.ToolChoice.AUTO);

        assertEquals("required", required.path("tool_choice").asText());
        assertTrue(automatic.path("tool_choice").isMissingNode());
    }

    private static final class RecordingClient implements LlmClient {
        private final List<LlmClient.ToolChoice> observedChoices = new ArrayList<>();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            observedChoices.add(LlmToolChoiceContext.current());
            return new ChatResponse("assistant", "done", null, 1, 1);
        }

        @Override
        public String getModelName() {
            return "recording";
        }

        @Override
        public String getProviderName() {
            return "recording";
        }
    }

    private static final class TestOpenAiClient extends AbstractOpenAiCompatibleClient {
        private ObjectNode requestBody(LlmClient.ToolChoice choice) {
            return buildRequestBody(MESSAGES, TOOLS, choice);
        }

        @Override
        protected String getApiUrl() {
            return "https://example.com/v1/chat/completions";
        }

        @Override
        protected String getModel() {
            return "test-model";
        }

        @Override
        protected String getApiKey() {
            return "test-key";
        }

        @Override
        public String getModelName() {
            return getModel();
        }

        @Override
        public String getProviderName() {
            return "test-openai";
        }
    }
}
