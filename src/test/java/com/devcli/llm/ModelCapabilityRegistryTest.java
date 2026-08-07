package com.devcli.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCapabilityRegistryTest {

    @AfterEach
    void clearOverrides() {
        ModelCapabilityRegistry.clearCustom();
    }

    @Test
    void resolvesBuiltInCapabilitiesByProvider() {
        ModelCapabilityRegistry.Capabilities capabilities =
                ModelCapabilityRegistry.resolve("moonshot-ai", "kimi-k2.6");

        assertEquals("kimi", capabilities.provider());
        assertEquals(256_000, capabilities.contextWindow());
        assertTrue(capabilities.promptCaching());
        assertTrue(capabilities.toolCalls());
    }

    @Test
    void customModelRuleOverridesProviderDefault() {
        ModelCapabilityRegistry.register(new ModelCapabilityRegistry.Capabilities(
                "openai", "gpt-test-*", 32_000, 4_096,
                false, "none", true, false, false));

        ModelCapabilityRegistry.Capabilities capabilities =
                ModelCapabilityRegistry.resolve("openai-compatible", "gpt-test-1");

        assertEquals(32_000, capabilities.contextWindow());
        assertEquals(4_096, capabilities.maxOutputTokens());
        assertFalse(capabilities.promptCaching());
    }

    @Test
    void unknownProviderGetsSafeGenericDefaults() {
        ModelCapabilityRegistry.Capabilities capabilities =
                ModelCapabilityRegistry.resolve("private-gateway", "custom-model");

        assertEquals(128_000, capabilities.contextWindow());
        assertTrue(capabilities.toolCalls());
        assertFalse(capabilities.promptCaching());
        assertFalse(capabilities.vision());
    }
}
