package com.devcli.llm;

import java.util.concurrent.ThreadLocalRandom;

public record LlmRetryPolicy(int maxAttempts, long initialDelayMillis,
                             long maxDelayMillis, double jitterRatio) {
    public LlmRetryPolicy {
        maxAttempts = Math.max(1, maxAttempts);
        initialDelayMillis = Math.max(0L, initialDelayMillis);
        maxDelayMillis = Math.max(initialDelayMillis, maxDelayMillis);
        jitterRatio = Math.max(0.0, Math.min(1.0, jitterRatio));
    }

    public static LlmRetryPolicy fromSystemProperties() {
        return new LlmRetryPolicy(
                readInt("devcli.llm.retry.max.attempts", "DEVCLI_LLM_RETRY_MAX_ATTEMPTS", 3),
                readLong("devcli.llm.retry.initial.delay.ms", "DEVCLI_LLM_RETRY_INITIAL_DELAY_MS", 500L),
                readLong("devcli.llm.retry.max.delay.ms", "DEVCLI_LLM_RETRY_MAX_DELAY_MS", 8_000L),
                readDouble("devcli.llm.retry.jitter.ratio", "DEVCLI_LLM_RETRY_JITTER_RATIO", 0.2));
    }

    long delayMillis(int failedAttempt, long retryAfterMillis) {
        if (retryAfterMillis > 0) {
            return Math.min(maxDelayMillis, retryAfterMillis);
        }
        long exponential;
        try {
            exponential = Math.multiplyExact(initialDelayMillis,
                    1L << Math.min(20, Math.max(0, failedAttempt - 1)));
        } catch (ArithmeticException e) {
            exponential = maxDelayMillis;
        }
        long base = Math.min(maxDelayMillis, exponential);
        if (base == 0 || jitterRatio == 0.0) return base;
        long spread = Math.max(1L, Math.round(base * jitterRatio));
        return Math.max(0L, Math.min(maxDelayMillis,
                base + ThreadLocalRandom.current().nextLong(-spread, spread + 1)));
    }

    private static int readInt(String key, String env, int fallback) {
        try {
            return Integer.parseInt(configured(key, env, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long readLong(String key, String env, long fallback) {
        try {
            return Long.parseLong(configured(key, env, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double readDouble(String key, String env, double fallback) {
        try {
            return Double.parseDouble(configured(key, env, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String configured(String key, String env, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) value = System.getenv(env);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
