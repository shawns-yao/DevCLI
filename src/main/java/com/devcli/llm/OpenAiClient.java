package com.devcli.llm;

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

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
