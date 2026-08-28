package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DelegationModelTest {
    @Test
    void inheritsPrimaryUnlessRoleModelIsExplicitlyConfigured() {
        DevCliConfig config = new DevCliConfig();
        config.getProviders().put("openai", new DevCliConfig.ProviderConfig("test-key", "http://localhost:1", "primary"));
        LlmClient primary = LlmClientFactory.create("openai", config);
        assertSame(primary, LlmClientFactory.createDelegatedAgent(config, primary, "worker"));
        System.setProperty("devcli.delegate.reviewer.model", "review-model");
        try {
            LlmClient reviewer = LlmClientFactory.createDelegatedAgent(config, primary, "reviewer");
            assertNotSame(primary, reviewer);
            assertEquals("review-model", reviewer.getModelName());
            assertSame(primary, LlmClientFactory.createDelegatedAgent(config, primary, "worker"));
        } finally { System.clearProperty("devcli.delegate.reviewer.model"); }
    }

    @Test
    void explicitlyUnavailableModelDoesNotSilentlyFallBack() {
        System.setProperty("devcli.delegate.planner.provider", "unavailable");
        try {
            assertThrows(IllegalArgumentException.class, () -> LlmClientFactory.createDelegatedAgent(
                    new DevCliConfig(), new OpenAiClient("test-key", "primary", "http://localhost:1"), "planner"));
        } finally { System.clearProperty("devcli.delegate.planner.provider"); }
    }
}
