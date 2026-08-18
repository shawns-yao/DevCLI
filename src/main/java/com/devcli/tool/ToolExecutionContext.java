package com.devcli.tool;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.CancellationToken;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** 单次工具调用的期限与协作式取消上下文。 */
public record ToolExecutionContext(
        String invocationId,
        CancellationToken cancellationToken,
        long startedAtNanos,
        long deadlineNanos) {

    public ToolExecutionContext {
        invocationId = invocationId == null ? "" : invocationId;
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        startedAtNanos = startedAtNanos <= 0 ? System.nanoTime() : startedAtNanos;
        deadlineNanos = deadlineNanos <= 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    public static ToolExecutionContext current(String invocationId) {
        CancellationToken current = CancellationContext.current();
        return unbounded(invocationId, current == null ? new CancellationToken() : current);
    }

    public static ToolExecutionContext unbounded(String invocationId, CancellationToken token) {
        return new ToolExecutionContext(invocationId, token, System.nanoTime(), Long.MAX_VALUE);
    }

    public boolean isCancelled() {
        return cancellationToken.isCancelled();
    }

    public Optional<CancellationToken.Cancellation> cancellation() {
        return cancellationToken.cancellation();
    }

    public long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    public long remainingNanos() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    public void throwIfCancelled() {
        cancellation().ifPresent(value -> {
            throw new CancellationException(value.message().isBlank()
                    ? "工具调用已取消"
                    : value.message());
        });
    }
}
