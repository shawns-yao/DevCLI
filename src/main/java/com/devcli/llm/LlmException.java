package com.devcli.llm;

import java.io.IOException;

public final class LlmException extends IOException {
    private final LlmErrorCode code;
    private final String provider;
    private final String model;
    private final int statusCode;
    private final boolean retryable;
    private final long retryAfterMillis;

    public LlmException(LlmErrorCode code, String provider, String model, int statusCode,
                        String message, boolean retryable, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.code = code == null ? LlmErrorCode.UNKNOWN : code;
        this.provider = provider == null ? "unknown" : provider;
        this.model = model == null ? "unknown" : model;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
    }

    public LlmErrorCode code() {
        return code;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public LlmException withoutRetry() {
        if (!retryable) {
            return this;
        }
        return new LlmException(code, provider, model, statusCode, getMessage(),
                false, retryAfterMillis, getCause());
    }
}
