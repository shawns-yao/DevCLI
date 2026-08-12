package com.devcli.llm;

import com.devcli.budget.PricingCatalog;
import com.devcli.budget.RunBudget;
import com.devcli.budget.RunBudgetPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmRetryExecutorTest {

    @Test
    void retriesRateLimitAndReturnsSuccessfulValue() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmRetryPolicy policy = new LlmRetryPolicy(3, 1, 4, 0.0);

        String result = LlmRetryExecutor.execute("openai", "model", policy, millis -> { }, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new LlmException(LlmErrorCode.RATE_LIMITED, "openai", "model",
                        429, "rate limited", true, 0L, null);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void retryAttemptsConsumeTheSharedRunCallBudget() {
        AtomicInteger calls = new AtomicInteger();
        RunBudget budget = RunBudget.create("run_retry", RunBudgetPolicy.builder()
                .maxLlmCalls(2)
                .maxTotalTokens(10_000)
                .build(), PricingCatalog.empty());

        LlmException error = assertThrows(LlmException.class, () -> LlmRetryExecutor.execute(
                "openai", "model", new LlmRetryPolicy(3, 1, 4, 0.0),
                millis -> { }, budget, "react", "agent", () -> {
                    calls.incrementAndGet();
                    throw new LlmException(LlmErrorCode.RATE_LIMITED, "openai", "model",
                            429, "rate limited", true, 0L, null);
                }));

        assertEquals(LlmErrorCode.BUDGET_EXHAUSTED, error.code());
        assertEquals(2, calls.get());
        assertEquals(2, budget.snapshot().llmCalls());
    }

    @Test
    void doesNotRetryInvalidRequest() {
        AtomicInteger calls = new AtomicInteger();
        LlmRetryPolicy policy = new LlmRetryPolicy(3, 1, 4, 0.0);

        assertThrows(LlmException.class, () -> LlmRetryExecutor.execute(
                "anthropic", "model", policy, millis -> { }, () -> {
                    calls.incrementAndGet();
                    throw new LlmException(LlmErrorCode.INVALID_REQUEST, "anthropic", "model",
                            400, "bad request", false, 0L, null);
                }));

        assertEquals(1, calls.get());
    }

    @Test
    void classifiesHttpErrorsWithConsistentRetrySemantics() {
        LlmException rateLimit = LlmErrors.fromHttp("openai", "model", 429, "rate limit", 1_000L);
        LlmException context = LlmErrors.fromHttp("anthropic", "model", 400,
                "maximum context length exceeded", 0L);
        LlmException auth = LlmErrors.fromHttp("glm", "model", 401, "unauthorized", 0L);

        assertEquals(LlmErrorCode.RATE_LIMITED, rateLimit.code());
        assertEquals(true, rateLimit.retryable());
        assertEquals(LlmErrorCode.CONTEXT_LENGTH, context.code());
        assertEquals(false, context.retryable());
        assertEquals(LlmErrorCode.AUTHENTICATION, auth.code());
        assertEquals(false, auth.retryable());
    }

    @Test
    void cancelledSamplingRequestDoesNotEnterRetryLoop() {
        SamplingRequestCoordinator coordinator = new SamplingRequestCoordinator();
        AtomicInteger calls = new AtomicInteger();
        try (SamplingRequestCoordinator.RequestScope scope = coordinator.begin("cancelled")) {
            coordinator.cancel(scope.requestId());

            LlmException error = assertThrows(LlmException.class, () -> LlmRetryExecutor.execute(
                    "openai", "model", new LlmRetryPolicy(3, 1, 4, 0.0),
                    millis -> { }, () -> {
                        calls.incrementAndGet();
                        return "unexpected";
                    }));

            assertEquals(LlmErrorCode.CANCELLED, error.code());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void mapsTransportIOExceptionToRetryableNetworkError() {
        LlmException error = LlmErrors.normalize(
                "deepseek", "model", new IOException("connection reset"));

        assertEquals(LlmErrorCode.NETWORK, error.code());
        assertEquals(true, error.retryable());
    }
}
