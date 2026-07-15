package com.devcli.runtime.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeCheckpointPolicyTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("devcli.runtime.checkpoint.trigger.tokens");
    }

    @Test
    void usesDefaultForMissingOrInvalidValue() {
        assertEquals(RuntimeCheckpointPolicy.DEFAULT_TRIGGER_TOKENS,
                RuntimeCheckpointPolicy.configuredTriggerTokens());
        System.setProperty("devcli.runtime.checkpoint.trigger.tokens", "bad");
        assertEquals(RuntimeCheckpointPolicy.DEFAULT_TRIGGER_TOKENS,
                RuntimeCheckpointPolicy.configuredTriggerTokens());
    }

    @Test
    void enforcesMinimumTrigger() {
        System.setProperty("devcli.runtime.checkpoint.trigger.tokens", "100");
        assertEquals(4_000, RuntimeCheckpointPolicy.configuredTriggerTokens());
    }
}
