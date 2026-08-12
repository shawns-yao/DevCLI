package com.devcli.runtime;

import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.runtime.store.AttemptStatus;

import java.util.Objects;
import java.util.UUID;

/** 为基础设施重试、Reviewer 修复与崩溃恢复生成统一 attempt 标识和事件。 */
public final class AttemptCoordinator {
    private final String runId;
    private final RunEventSink events;
    private final AttemptPersistence persistence;
    private final String parentAttemptId;

    public AttemptCoordinator(String runId, RunEventSink events) {
        this(runId, events, AttemptPersistence.NO_OP, "");
    }

    public AttemptCoordinator(String runId, RunEventSink events,
                              AttemptPersistence persistence, String parentAttemptId) {
        this.runId = requireText(runId, "runId");
        this.events = events == null ? RunEventSink.NO_OP : events;
        this.persistence = persistence == null ? AttemptPersistence.NO_OP : persistence;
        this.parentAttemptId = text(parentAttemptId);
    }

    public static AttemptCoordinator currentOrLocal(String fallbackRunId) {
        RunContext context = CancellationContext.currentRun();
        return context == null
                ? new AttemptCoordinator(fallbackRunId, RunEventSink.NO_OP)
                : new AttemptCoordinator(context.runId(), context.eventSink(),
                context.attemptPersistence(), context.parentAttemptId());
    }

    public AttemptScope start(AttemptKind kind, String scope, String reason,
                              int sequence, long backoffMillis) {
        AttemptKind normalized = Objects.requireNonNullElse(kind, AttemptKind.INITIAL);
        int normalizedSequence = Math.max(1, sequence);
        String attemptId = runId + ":attempt:" + UUID.randomUUID();
        AttemptPersistence.AttemptData attempt = new AttemptPersistence.AttemptData(
                attemptId, runId, parentAttemptId, normalized, text(scope), text(reason),
                normalizedSequence, Math.max(0, backoffMillis));
        persistence.started(attempt);
        events.emit(new RunEvent.AttemptStarted(
                runId, attemptId, parentAttemptId, normalized.name(), text(scope), text(reason),
                normalizedSequence, Math.max(0, backoffMillis)));
        return new AttemptScope(attemptId, normalized, text(scope), normalizedSequence);
    }

    public void scheduled(AttemptKind kind, String scope, String reason,
                          int nextSequence, long backoffMillis) {
        events.emit(new RunEvent.RetryScheduled(
                runId, Objects.requireNonNullElse(kind, AttemptKind.INFRASTRUCTURE_RETRY).name(),
                text(scope), text(reason), Math.max(1, nextSequence), Math.max(0, backoffMillis)));
    }

    public final class AttemptScope implements AutoCloseable {
        private final String attemptId;
        private final AttemptKind kind;
        private final String scope;
        private final int sequence;
        private boolean finished;

        private AttemptScope(String attemptId, AttemptKind kind, String scope, int sequence) {
            this.attemptId = attemptId;
            this.kind = kind;
            this.scope = scope;
            this.sequence = sequence;
        }

        public String attemptId() { return attemptId; }

        public void complete(String outcome) {
            finish("COMPLETED", outcome);
        }

        public void fail(String outcome) {
            finish("FAILED", outcome);
        }

        private void finish(String status, String outcome) {
            if (finished) return;
            finished = true;
            AttemptStatus attemptStatus = "COMPLETED".equals(status)
                    ? AttemptStatus.COMPLETED : AttemptStatus.FAILED;
            persistence.finished(attemptId, attemptStatus, text(outcome));
            events.emit(new RunEvent.AttemptFinished(
                    runId, attemptId, parentAttemptId, kind.name(), scope,
                    sequence, status, text(outcome)));
        }

        @Override
        public void close() {
            if (!finished) fail("scope_closed_without_outcome");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
