package com.devcli.llm;

import java.io.IOException;

public final class LlmException extends IOException {
    private final LlmErrorCode code;
    private final String provider;
    private final String model;
    private final int statusCode;
    private final boolean retryable;
    private final long retryAfterMillis;
    private final boolean responseStarted;

    public LlmException(LlmErrorCode code, String provider, String model, int statusCode,
                        String message, boolean retryable, long retryAfterMillis, Throwable cause) {
        this(code, provider, model, statusCode, message, retryable,
                retryAfterMillis, false, cause);
    }

    public LlmException(LlmErrorCode code, String provider, String model, int statusCode,
                        String message, boolean retryable, long retryAfterMillis,
                        boolean responseStarted, Throwable cause) {
        super(message, cause);
        this.code = code == null ? LlmErrorCode.UNKNOWN : code;
        this.provider = provider == null ? "unknown" : provider;
        this.model = model == null ? "unknown" : model;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
        this.responseStarted = responseStarted;
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

    public LlmException afterResponseStarted() {
        if (responseStarted) {
            return this;
        }
        return new LlmException(code, provider, model, statusCode, getMessage(),
                false, retryAfterMillis, true, getCause());
    }

    public boolean responseStarted() {
        return responseStarted;
    }
}
