package com.devcli.runtime;

import com.devcli.llm.LlmErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy();

    @Test
    void retriesOnlyTransientLlmErrorsBeforeOutputStarts() {
        assertTrue(policy.llm(LlmErrorCode.RATE_LIMITED, false, 1, 3).retry());
        assertTrue(policy.llm(LlmErrorCode.NETWORK, false, 1, 3).retry());
        assertFalse(policy.llm(LlmErrorCode.AUTHENTICATION, false, 1, 3).retry());
        assertFalse(policy.llm(LlmErrorCode.CONTENT_FILTER, false, 1, 3).retry());
        assertFalse(policy.llm(LlmErrorCode.SERVER_ERROR, true, 1, 3).retry());
        assertFalse(policy.llm(LlmErrorCode.SERVER_ERROR, false, 3, 3).retry());
    }

    @Test
    void sideEffectsRequireIdempotencyKeyAndReconciliation() {
        assertTrue(policy.tool(RetryPolicy.Effect.READ_ONLY, true, false, false).retry());
        assertFalse(policy.tool(RetryPolicy.Effect.PROJECT_MUTATION, true, false, true).retry());
        assertFalse(policy.tool(RetryPolicy.Effect.PROJECT_MUTATION, true, true, false).retry());
        assertTrue(policy.tool(RetryPolicy.Effect.PROJECT_MUTATION, true, true, true).retry());
        assertFalse(policy.tool(RetryPolicy.Effect.EXTERNAL_MUTATION, false, true, true).retry());
    }

    @Test
    void recoveryRequiresPatchCheckpointAndBudgetSafety() {
        assertFalse(policy.recovery(false, true, true).retry());
        assertFalse(policy.recovery(true, false, true).retry());
        assertFalse(policy.recovery(true, true, false).retry());
        assertTrue(policy.recovery(true, true, true).retry());
    }
}
