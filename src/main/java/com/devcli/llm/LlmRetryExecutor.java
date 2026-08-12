package com.devcli.llm;

import com.devcli.budget.RunBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class LlmRetryExecutor {
    private static final Logger log = LoggerFactory.getLogger(LlmRetryExecutor.class);
    private LlmRetryExecutor() {
    }

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public static <T> T execute(String provider, String model, LlmRetryPolicy policy,
                                Sleeper sleeper, IoSupplier<T> operation) throws IOException {
        return execute(provider, model, policy, sleeper, null, "llm", "llm", operation);
    }

    public static <T> T execute(String provider, String model, LlmRetryPolicy policy,
                                Sleeper sleeper, RunBudget runBudget,
                                String phase, String agent, IoSupplier<T> operation) throws IOException {
        LlmRetryPolicy effectivePolicy = policy == null
                ? LlmRetryPolicy.fromSystemProperties() : policy;
        Sleeper effectiveSleeper = sleeper == null ? Thread::sleep : sleeper;
        LlmBudgetContext.Scope scopedBudget = runBudget == null ? LlmBudgetContext.current() : null;
        LlmException last = null;
        for (int attempt = 1; attempt <= effectivePolicy.maxAttempts(); attempt++) {
            if (SamplingRequestCoordinator.isCurrentCancelled()) {
                throw cancelled(provider, model, null);
            }
            RunBudget.Admission admission = runBudget != null
                    ? runBudget.tryStartLlmRequest(phase, agent, "attempt-" + attempt)
                    : (scopedBudget == null ? null : scopedBudget.admissionForAttempt(attempt));
            if (admission != null && !admission.allowed()) {
                throw new LlmException(LlmErrorCode.BUDGET_EXHAUSTED, provider, model, 0,
                        "Run budget exhausted: " + admission.reason(), false, 0L, last);
            }
            try {
                T result = operation.get();
                if (runBudget != null) runBudget.releaseReservation(admission);
                return result;
            } catch (IOException error) {
                if (runBudget != null) runBudget.releaseReservation(admission);
                else if (scopedBudget != null) scopedBudget.failed(admission);
                if (SamplingRequestCoordinator.isCurrentCancelled()) {
                    throw cancelled(provider, model, error);
                }
                last = LlmErrors.normalize(provider, model, error);
                if (!last.retryable() || attempt >= effectivePolicy.maxAttempts()) {
                    throw last;
                }
                long delay = effectivePolicy.delayMillis(attempt, last.retryAfterMillis());
                log.warn("retry LLM request: provider={}, model={}, code={}, attempt={}/{}, delayMs={}",
                        provider, model, last.code(), attempt + 1, effectivePolicy.maxAttempts(), delay);
                try {
                    effectiveSleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw cancelled(provider, model, interrupted);
                }
            }
        }
        throw last == null
                ? new LlmException(LlmErrorCode.UNKNOWN, provider, model, 0,
                "LLM request failed without error", false, 0L, null)
                : last;
    }

    private static LlmException cancelled(String provider, String model, Throwable cause) {
        return new LlmException(LlmErrorCode.CANCELLED, provider, model, 0,
                "LLM request cancelled", false, 0L, cause);
    }
}
