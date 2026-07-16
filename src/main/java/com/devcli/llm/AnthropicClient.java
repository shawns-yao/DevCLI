package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Messages 原生接口 client，用于 Claude API 兼容端点。
 */
public class AnthropicClient implements LlmClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final String DEFAULT_VERSION = "2023-06-01";

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("devcli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("devcli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("devcli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("devcli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final int maxOutputTokens;

    public AnthropicClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public AnthropicClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, 8_192);
    }

    public AnthropicClient(String apiKey, String model, String baseUrl, int maxOutputTokens) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toMessagesUrl(baseUrl);
        this.maxOutputTokens = Math.max(1, Math.min(65_536, maxOutputTokens));
    }

    String getApiUrl() {
        return apiUrl;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        ToolChoice toolChoice = LlmToolChoiceContext.current();
        StreamListener delegate = listener == null ? StreamListener.NO_OP : listener;
        java.util.concurrent.atomic.AtomicBoolean streamed = new java.util.concurrent.atomic.AtomicBoolean();
        StreamListener tracking = new StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                streamed.set(true);
                delegate.onReasoningDelta(delta);
            }

            @Override
            public void onContentDelta(String delta) {
                streamed.set(true);
                delegate.onContentDelta(delta);
            }
        };
        return LlmRetryExecutor.execute(getProviderName(), getModelName(),
                LlmRetryPolicy.fromSystemProperties(), Thread::sleep, () -> {
                    try {
                        return chatOnce(messages, tools, tracking, toolChoice);
                    } catch (IOException error) {
                        LlmException normalized = LlmErrors.normalize(getProviderName(), getModelName(), error);
                        throw streamed.get() ? normalized.withoutRetry() : normalized;
                    }
                });
    }

    private ChatResponse chatOnce(List<Message> messages, List<Tool> tools,
                                  StreamListener streamListener,
                                  ToolChoice toolChoice) throws IOException {
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools, toolChoice).toString(),
                MediaType.parse("application/json")
        );

        Request.Builder requestBuilder = new Request.Builder()
                .url(apiUrl)
                .header("Content-Type", "application/json")
                .header("anthropic-version", anthropicVersion())
                .header("User-Agent", "ClaudeCode/1.0")
                .post(body);
        applyAuthHeader(requestBuilder);

        try (Response response = HTTP_CLIENT.newCall(requestBuilder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBody != null ? responseBody.string() : "无响应体";
                throw LlmErrors.fromHttp(getProviderName(), getModelName(), response.code(), errorBody,
                        LlmErrors.retryAfterMillis(response.header("Retry-After")));
            }
            if (responseBody == null) {
                throw LlmErrors.malformedResponse(getProviderName(), getModelName(), "empty response body", null);
            }
            return parseStream(responseBody.source(), streamListener);
        }
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public int maxContextWindow() {
        return 200_000;
    }

    @Override
    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "anthropic-messages";
    }

    ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools, ToolChoice toolChoice) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxOutputTokens());
        requestBody.put("stream", true);

        String systemPrompt = collectSystemPrompt(messages);
        if (!systemPrompt.isBlank()) {
            requestBody.put("system", systemPrompt);
        }

        ArrayNode messageArray = requestBody.putArray("messages");
        if (messages != null) {
            for (Message message : messages) {
                appendMessage(messageArray, message);
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolArray.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", tool.parameters());
            }
            if (toolChoice != null && toolChoice.required()) {
                ObjectNode choiceNode = requestBody.putObject("tool_choice");
                if (toolChoice.hasSpecificTool()) {
                    choiceNode.put("type", "tool");
                    choiceNode.put("name", toolChoice.toolName());
                } else {
                    choiceNode.put("type", "any");
                }
            }
        }
        return requestBody;
    }

    private String collectSystemPrompt(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (message != null && "system".equals(message.role()) && message.content() != null && !message.content().isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(message.content());
            }
        }
        return sb.toString();
    }

    private void appendMessage(ArrayNode messageArray, Message message) {
        if (message == null || "system".equals(message.role())) {
            return;
        }
        ObjectNode messageNode = messageArray.addObject();
        if ("assistant".equals(message.role())) {
            messageNode.put("role", "assistant");
            appendAssistantContent(messageNode, message);
        } else if ("tool".equals(message.role())) {
            messageNode.put("role", "user");
            ArrayNode content = messageNode.putArray("content");
            ObjectNode toolResult = content.addObject();
            toolResult.put("type", "tool_result");
            toolResult.put("tool_use_id", message.toolCallId() == null ? "" : message.toolCallId());
            toolResult.put("content", safeText(message.content()));
        } else {
            messageNode.put("role", "user");
            appendUserContent(messageNode, message);
        }
    }

    private void appendUserContent(ObjectNode messageNode, Message message) {
        if (!message.hasContentParts()) {
            messageNode.put("content", safeText(message.content()));
            return;
        }
        ArrayNode content = messageNode.putArray("content");
        for (ContentPart part : message.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                appendTextBlock(content, part.text());
            } else if ("image_base64".equals(part.type())) {
                ObjectNode image = content.addObject();
                image.put("type", "image");
                ObjectNode source = image.putObject("source");
                source.put("type", "base64");
                source.put("media_type", part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType());
                source.put("data", part.imageBase64());
            } else if ("image_url".equals(part.type())) {
                appendTextBlock(content, "[图片 URL: " + part.imageUrl() + "]");
            }
        }
        if (content.size() == 0) {
            appendTextBlock(content, safeText(message.content()));
        }
    }

    private void appendAssistantContent(ObjectNode messageNode, Message message) {
        ArrayNode content = messageNode.putArray("content");
        appendTextBlock(content, message.content());
        if (message.toolCalls() != null) {
            for (ToolCall toolCall : message.toolCalls()) {
                if (toolCall == null || toolCall.function() == null) {
                    continue;
                }
                ObjectNode toolUse = content.addObject();
                toolUse.put("type", "tool_use");
                toolUse.put("id", toolCall.id());
                toolUse.put("name", toolCall.function().name());
                toolUse.set("input", parseArguments(toolCall.function().arguments()));
            }
        }
        if (content.size() == 0) {
            appendTextBlock(content, safeText(message.content()));
        }
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(arguments);
        } catch (Exception ignored) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("value", arguments);
            return fallback;
        }
    }

    private void appendTextBlock(ArrayNode content, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
    }

    private ChatResponse parseStream(BufferedSource source, StreamListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolUseAccumulator> toolAccumulators = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;

        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }

            JsonNode root;
            try {
                root = mapper.readTree(payload);
            } catch (IOException malformed) {
                throw LlmErrors.malformedResponse(getProviderName(), getModelName(),
                        "invalid stream payload", malformed);
            }
            String type = root.path("type").asText("");
            if ("message_start".equals(type)) {
                inputTokens = root.path("message").path("usage").path("input_tokens").asInt(inputTokens);
                outputTokens = root.path("message").path("usage").path("output_tokens").asInt(outputTokens);
            } else if ("message_delta".equals(type)) {
                outputTokens = root.path("usage").path("output_tokens").asInt(outputTokens);
            } else if ("content_block_start".equals(type)) {
                handleContentBlockStart(root, toolAccumulators);
            } else if ("content_block_delta".equals(type)) {
                handleContentBlockDelta(root, content, reasoning, toolAccumulators, listener);
            } else if ("error".equals(type)) {
                JsonNode errorNode = root.path("error");
                String errorType = errorNode.path("type").asText("");
                String message = errorNode.path("message").asText(root.toString());
                int syntheticStatus = errorType.contains("rate_limit") ? 429
                        : errorType.contains("overloaded") ? 529 : 400;
                throw LlmErrors.fromHttp(getProviderName(), getModelName(), syntheticStatus,
                        errorType + ": " + message, 0L);
            } else if ("message_stop".equals(type)) {
                break;
            }
        }

        List<ToolCall> toolCalls = buildToolCalls(toolAccumulators);
        if (content.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty()) {
            throw LlmErrors.malformedResponse(getProviderName(), getModelName(),
                    "empty assistant response", null);
        }
        return new ChatResponse(
                "assistant",
                content.toString(),
                reasoning.toString(),
                toolCalls,
                inputTokens,
                outputTokens,
                0
        );
    }

    private void handleContentBlockStart(JsonNode root, List<ToolUseAccumulator> toolAccumulators) throws IOException {
        JsonNode block = root.path("content_block");
        if (!"tool_use".equals(block.path("type").asText(""))) {
            return;
        }
        int index = root.path("index").asInt(toolAccumulators.size());
        ToolUseAccumulator accumulator = ensureToolAccumulator(toolAccumulators, index);
        accumulator.id = block.path("id").asText(accumulator.id);
        accumulator.name = block.path("name").asText(accumulator.name);
        JsonNode input = block.path("input");
        if (!input.isMissingNode() && !input.isNull()) {
            accumulator.inputJson = mapper.writeValueAsString(input);
        }
    }

    private void handleContentBlockDelta(JsonNode root, StringBuilder content, StringBuilder reasoning,
                                         List<ToolUseAccumulator> toolAccumulators, StreamListener listener) {
        JsonNode delta = root.path("delta");
        String type = delta.path("type").asText("");
        if ("text_delta".equals(type)) {
            String text = delta.path("text").asText("");
            if (!text.isEmpty()) {
                content.append(text);
                listener.onContentDelta(text);
            }
        } else if ("thinking_delta".equals(type)) {
            String thinking = delta.path("thinking").asText("");
            if (!thinking.isEmpty()) {
                reasoning.append(thinking);
                listener.onReasoningDelta(thinking);
            }
        } else if ("input_json_delta".equals(type)) {
            int index = root.path("index").asInt(toolAccumulators.size());
            ToolUseAccumulator accumulator = ensureToolAccumulator(toolAccumulators, index);
            accumulator.partialJson.append(delta.path("partial_json").asText(""));
        }
    }

    private ToolUseAccumulator ensureToolAccumulator(List<ToolUseAccumulator> accumulators, int index) {
        while (accumulators.size() <= index) {
            accumulators.add(new ToolUseAccumulator());
        }
        return accumulators.get(index);
    }

    private List<ToolCall> buildToolCalls(List<ToolUseAccumulator> accumulators) {
        List<ToolCall> calls = new ArrayList<>();
        for (ToolUseAccumulator accumulator : accumulators) {
            if (accumulator.id == null || accumulator.id.isBlank() || accumulator.name == null || accumulator.name.isBlank()) {
                continue;
            }
            String arguments = accumulator.partialJson.length() > 0
                    ? accumulator.partialJson.toString()
                    : accumulator.inputJson;
            if (arguments == null || arguments.isBlank()) {
                arguments = "{}";
            }
            calls.add(new ToolCall(accumulator.id, new ToolCall.Function(accumulator.name, arguments)));
        }
        return calls.isEmpty() ? null : calls;
    }

    private void applyAuthHeader(Request.Builder builder) {
        String configuredAuthToken = DevCliConfig.getEnvOrDotEnv("ANTHROPIC_AUTH_TOKEN");
        if (configuredAuthToken != null && configuredAuthToken.equals(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
            return;
        }
        builder.header("x-api-key", apiKey);
    }

    private String anthropicVersion() {
        String version = DevCliConfig.getEnvOrDotEnv("ANTHROPIC_VERSION");
        return version == null || version.isBlank() ? DEFAULT_VERSION : version;
    }

    private static String toMessagesUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/v1/messages")) {
            return withoutTrailingSlash;
        }
        if (withoutTrailingSlash.endsWith("/messages")) {
            return withoutTrailingSlash;
        }
        if (withoutTrailingSlash.endsWith("/v1")) {
            return withoutTrailingSlash + "/messages";
        }
        return withoutTrailingSlash + "/v1/messages";
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "[empty]" : value;
    }

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class ToolUseAccumulator {
        private String id;
        private String name;
        private String inputJson;
        private final StringBuilder partialJson = new StringBuilder();
    }
}
