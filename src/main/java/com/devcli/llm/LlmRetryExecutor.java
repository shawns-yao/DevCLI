package com.devcli.llm;

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
        LlmRetryPolicy effectivePolicy = policy == null
                ? LlmRetryPolicy.fromSystemProperties() : policy;
        Sleeper effectiveSleeper = sleeper == null ? Thread::sleep : sleeper;
        LlmException last = null;
        for (int attempt = 1; attempt <= effectivePolicy.maxAttempts(); attempt++) {
            try {
                return operation.get();
            } catch (IOException error) {
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
                    throw new LlmException(LlmErrorCode.NETWORK, provider, model, 0,
                            "LLM retry interrupted", false, 0L, interrupted);
                }
            }
        }
        throw last == null
                ? new LlmException(LlmErrorCode.UNKNOWN, provider, model, 0,
                "LLM request failed without error", false, 0L, null)
                : last;
    }
}
