package com.devcli.budget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunBudgetPolicyTest {

    @Test
    void allProductTiersAreFiniteAndOrdered() {
        RunBudgetPolicy economy = RunBudgetPolicy.forTier(RunBudgetPolicy.Tier.ECONOMY);
        RunBudgetPolicy balanced = RunBudgetPolicy.forTier(RunBudgetPolicy.Tier.BALANCED);
        RunBudgetPolicy thorough = RunBudgetPolicy.forTier(RunBudgetPolicy.Tier.THOROUGH);

        assertTrue(economy.maxTotalTokens() > 0);
        assertTrue(economy.maxLlmCalls() > 0);
        assertTrue(economy.maxWallClockMillis() > 0);
        assertTrue(economy.maxTotalTokens() < balanced.maxTotalTokens());
        assertTrue(balanced.maxTotalTokens() < thorough.maxTotalTokens());
        assertTrue(economy.maxLlmCalls() < balanced.maxLlmCalls());
        assertTrue(balanced.maxLlmCalls() < thorough.maxLlmCalls());
    }

    @Test
    void resolvesTierFromConfigurationWithoutAllowingUnknownValues() {
        assertEquals(RunBudgetPolicy.Tier.BALANCED,
                RunBudgetPolicy.resolveTier("balanced"));
        assertThrows(IllegalArgumentException.class,
                () -> RunBudgetPolicy.resolveTier("unlimited"));
    }
}
