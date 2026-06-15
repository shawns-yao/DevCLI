package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmClientFactoryTest {

    @Test
    void createsGlm5vTurboClientWithMultimodalEndpoint() {
        DevCliConfig config = new DevCliConfig();
        config.getProviders().put("glm",
                new DevCliConfig.ProviderConfig("test-glm-key", null, "glm-5v-turbo"));

        LlmClient client = LlmClientFactory.create("glm", config);

        GLMClient glmClient = assertInstanceOf(GLMClient.class, client);
        assertEquals("glm", glmClient.getProviderName());
        assertEquals("glm-5v-turbo", glmClient.getModelName());
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", glmClient.getApiUrl());
    }

    @Test
    void createsStepClientFromConfiguredProvider() {
        DevCliConfig config = new DevCliConfig();
        config.getProviders().put("step",
                new DevCliConfig.ProviderConfig("test-step-key", null, "step-3.5-flash-2603"));

        LlmClient client = LlmClientFactory.create("step", config);

        StepClient stepClient = assertInstanceOf(StepClient.class, client);
        assertEquals("step", stepClient.getProviderName());
        assertEquals("step-3.5-flash-2603", stepClient.getModelName());
        assertEquals(256_000, stepClient.maxContextWindow());
        assertEquals(expectedStepChatUrl(config.getBaseUrl("step")), stepClient.getApiUrl());
    }

    @Test
    void createsStepClientFromStepfunAliasAndCustomBaseUrl() {
        DevCliConfig config = new DevCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("step",
                new DevCliConfig.ProviderConfig(
                        "test-step-key",
                        "https://api.stepfun.com/step_plan/v1",
                        "step-router-v1"));

        LlmClient client = LlmClientFactory.create("stepfun", config);

        StepClient stepClient = assertInstanceOf(StepClient.class, client);
        assertEquals("step-router-v1", stepClient.getModelName());
        assertEquals("https://api.stepfun.com/step_plan/v1/chat/completions", stepClient.getApiUrl());
    }

    @Test
    void createsKimiClientFromMoonshotAliasAndCustomBaseUrl() {
        DevCliConfig config = new DevCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("kimi",
                new DevCliConfig.ProviderConfig(
                        "test-kimi-key",
                        "https://api.moonshot.ai/v1",
                        "kimi-k2.6"));

        LlmClient client = LlmClientFactory.create("moonshot", config);

        KimiClient kimiClient = assertInstanceOf(KimiClient.class, client);
        assertEquals("kimi", kimiClient.getProviderName());
        assertEquals("kimi-k2.6", kimiClient.getModelName());
        assertEquals(256_000, kimiClient.maxContextWindow());
    }

    @Test
    void returnsNullForUnknownProvider() {
        DevCliConfig config = new DevCliConfig();
        config.getProviders().put("unknown", new DevCliConfig.ProviderConfig("test-key", null, "unknown-model"));

        assertNull(LlmClientFactory.create("unknown", config));
    }

    @Test
    void createsOpenAiClientWithCustomBaseUrl() {
        DevCliConfig config = new DevCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("openai",
                new DevCliConfig.ProviderConfig(
                        "test-openai-key",
                        "https://my-gateway.example.com/v1",
                        "gpt-4o-mini"));

        LlmClient client = LlmClientFactory.create("openai", config);

        OpenAiClient openAiClient = assertInstanceOf(OpenAiClient.class, client);
        assertEquals("openai", openAiClient.getProviderName());
        assertEquals("gpt-4o-mini", openAiClient.getModelName());
        assertEquals("https://my-gateway.example.com/v1/chat/completions", openAiClient.getApiUrl());
    }

    @Test
    void createsOpenAiClientFromGptAlias() {
        DevCliConfig config = new DevCliConfig();
        config.setProviders(new LinkedHashMap<>());
        config.getProviders().put("openai",
                new DevCliConfig.ProviderConfig("test-key", null, "gpt-4o"));

        assertInstanceOf(OpenAiClient.class, LlmClientFactory.create("gpt", config));
    }

    private static String expectedStepChatUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl.trim()
                : "https://api.stepfun.com/v1";
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
