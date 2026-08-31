package com.devcli.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Locale;

public final class LlmErrors {
    private LlmErrors() {
    }

    public static LlmException fromHttp(String provider, String model, int statusCode,
                                        String responseBody, long retryAfterMillis) {
        String body = responseBody == null ? "" : responseBody;
        String lower = body.toLowerCase(Locale.ROOT);
        LlmErrorCode code;
        boolean retryable;
        if (statusCode == 401 || statusCode == 403) {
            code = LlmErrorCode.AUTHENTICATION;
            retryable = false;
        } else if (statusCode == 429) {
            code = LlmErrorCode.RATE_LIMITED;
            retryable = true;
        } else if (statusCode == 408 || statusCode == 504) {
            code = LlmErrorCode.TIMEOUT;
            retryable = true;
        } else if (statusCode == 529 || lower.contains("overloaded")) {
            code = LlmErrorCode.OVERLOADED;
            retryable = true;
        } else if (lower.contains("upstream_stream_break")
                || lower.contains("upstream stream ended prematurely")
                || lower.contains("safe to retry")) {
            code = LlmErrorCode.SERVER_ERROR;
            retryable = true;
        } else if (lower.contains("context_length") || lower.contains("context length")
                || lower.contains("maximum context") || lower.contains("too many tokens")) {
            code = LlmErrorCode.CONTEXT_LENGTH;
            retryable = false;
        } else if (lower.contains("content_filter") || lower.contains("safety")) {
            code = LlmErrorCode.CONTENT_FILTER;
            retryable = false;
        } else if (statusCode >= 500) {
            code = LlmErrorCode.SERVER_ERROR;
            retryable = true;
        } else if (statusCode >= 400) {
            code = LlmErrorCode.INVALID_REQUEST;
            retryable = false;
        } else {
            code = LlmErrorCode.UNKNOWN;
            retryable = false;
        }
        String message = "LLM request failed: provider=" + provider + ", model=" + model
                + ", status=" + statusCode + ", code=" + code
                + (body.isBlank() ? "" : ", detail=" + sanitize(body));
        return new LlmException(code, provider, model, statusCode, message,
                retryable, retryAfterMillis, null);
    }

    public static LlmException normalize(String provider, String model, IOException error) {
        if (error instanceof LlmException llmException) {
            return llmException;
        }
        if (Thread.currentThread().isInterrupted()) {
            return new LlmException(LlmErrorCode.NETWORK, provider, model, 0,
                    "LLM transport interrupted", false, 0L, error);
        }
        boolean timeout = error instanceof SocketTimeoutException
                || error instanceof InterruptedIOException
                || contains(error.getMessage(), "timeout", "timed out");
        LlmErrorCode code = timeout ? LlmErrorCode.TIMEOUT : LlmErrorCode.NETWORK;
        return new LlmException(code, provider, model, 0,
                "LLM transport failed: provider=" + provider + ", model=" + model
                        + ", code=" + code + ", detail=" + sanitize(error.getMessage()),
                true, 0L, error);
    }

    public static long retryAfterMillis(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Math.multiplyExact(Long.parseLong(value.trim()), 1_000L));
        } catch (NumberFormatException | ArithmeticException e) {
            return 0L;
        }
    }

    public static LlmException malformedResponse(String provider, String model, String message, Throwable cause) {
        return new LlmException(LlmErrorCode.MALFORMED_RESPONSE, provider, model, 0,
                "LLM response malformed: provider=" + provider + ", model=" + model
                        + ", detail=" + sanitize(message), false, 0L, cause);
    }

    private static boolean contains(String value, String... needles) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
