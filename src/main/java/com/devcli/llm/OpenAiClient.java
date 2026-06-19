package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * OpenAI 兼容 client：对接 OpenAI 官方及任意 OpenAI Chat Completions 兼容端点
 * （vLLM / Ollama OpenAI 模式 / OneAPI / 各类代理网关）。base URL 与 model 由用户经
 * OPENAI_BASE_URL / OPENAI_MODEL 配置，默认指向 OpenAI 官方。
 */
public class OpenAiClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o";
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public OpenAiClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public OpenAiClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public int maxContextWindow() {
        return 128_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "openai-automatic-prefix-cache";
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        // 某些网关(如 runanytime.hxi.me)要求指定分组/渠道名，否则默认分到 default 分组可能无权限
        // 通过 OPENAI_CHANNEL 或 OPENAI_GROUP 配置，常见值: Other / ClaudeCode / Codex
        String channel = DevCliConfig.getEnvOrDotEnv("OPENAI_CHANNEL");
        if (channel == null || channel.isBlank()) {
            channel = DevCliConfig.getEnvOrDotEnv("OPENAI_GROUP");
        }
        if (channel != null && !channel.isBlank()) {
            // 同时添加多个可能的字段名，网关会自动识别
            requestBody.put("channel", channel);
            requestBody.put("group", channel);
        }
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
