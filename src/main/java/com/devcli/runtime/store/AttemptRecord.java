package com.devcli.runtime.store;

import com.devcli.runtime.AttemptKind;

import java.time.Instant;

/** Run 的一次有租约执行尝试。 */
public record AttemptRecord(
        String id,
        String runId,
        long sequence,
        int logicalSequence,
        String parentAttemptId,
        AttemptKind kind,
        String scope,
        AttemptStatus status,
        String workerId,
        String reason,
        String outcome,
        long backoffMillis,
        Instant leaseExpiresAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt
) {
    public AttemptRecord {
        logicalSequence = Math.max(1, logicalSequence);
        parentAttemptId = parentAttemptId == null ? "" : parentAttemptId;
        kind = kind == null ? AttemptKind.INITIAL : kind;
        scope = scope == null ? "" : scope;
        status = status == null ? AttemptStatus.RUNNING : status;
        workerId = workerId == null ? "" : workerId;
        reason = reason == null ? "" : reason;
        outcome = outcome == null ? "" : outcome;
        backoffMillis = Math.max(0, backoffMillis);
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
    }
}
