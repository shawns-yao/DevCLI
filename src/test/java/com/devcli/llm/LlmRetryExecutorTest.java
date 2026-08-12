package com.devcli.llm;

import com.devcli.budget.PricingCatalog;
import com.devcli.budget.RunBudget;
import com.devcli.budget.RunBudgetPolicy;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.RunCoordinator;
import com.devcli.runtime.store.RunSubmission;
import com.devcli.runtime.store.SqliteRunStore;
import com.devcli.runtime.store.SubmissionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
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

    @Test
    void doesNotRetryTransientCodeAfterStreamingDisablesRetry() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(LlmException.class, () -> LlmRetryExecutor.execute(
                "openai", "model", new LlmRetryPolicy(3, 1, 4, 0.0),
                millis -> { }, () -> {
                    calls.incrementAndGet();
                    throw new LlmException(LlmErrorCode.SERVER_ERROR, "openai", "model",
                            500, "stream failed", true, 0L, null).afterResponseStarted();
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void persistsInfrastructureAttemptsInCurrentRun(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            var submitted = store.submit(new RunSubmission(
                    "run_retry_persist", SubmissionSource.CLI, "", tempDir,
                    "retry", "", ""));
            RunCoordinator coordinator = new RunCoordinator(store);
            try (RunCoordinator.ClaimedRunContext claimed = coordinator
                    .claim(submitted.id(), "test-worker").orElseThrow()) {
                AtomicInteger calls = new AtomicInteger();
                String result = LlmRetryExecutor.execute(
                        "openai", "model", new LlmRetryPolicy(2, 1, 2, 0.0),
                        millis -> { }, () -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new LlmException(LlmErrorCode.NETWORK, "openai", "model",
                                        0, "network", true, 0L, null);
                            }
                            return "ok";
                        });
                assertEquals("ok", result);
                assertEquals(3, store.attempts(submitted.id()).size());
                assertEquals("INFRASTRUCTURE_RETRY",
                        store.attempts(submitted.id()).get(2).kind().name());
                assertEquals(claimed.attempt().id(),
                        store.attempts(submitted.id()).get(2).parentAttemptId());
            }
            assertEquals(null, CancellationContext.currentRun());
        }
    }
}
