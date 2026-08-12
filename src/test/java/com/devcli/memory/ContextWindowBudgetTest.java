package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextWindowBudgetTest {

    @Test
    void onlyRepresentsOneRequestContextCapacity() {
        ContextWindowBudget budget = new ContextWindowBudget(128_000);

        assertEquals(124_700, budget.availableConversationTokens());
        assertTrue(budget.fits(List.of(LlmClient.Message.user("small request"))));
    }
}
