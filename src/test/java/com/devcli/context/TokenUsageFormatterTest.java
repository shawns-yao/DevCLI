package com.devcli.context;

import com.devcli.llm.GLMClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenUsageFormatterTest {

    @Test
    void unknownModelDoesNotUseAProviderFallbackPrice() {
        assertEquals("cost=unknown",
                TokenUsageFormatter.estimatedCost(new GLMClient("test-key"), 1_000, 500, 0));
    }
}
