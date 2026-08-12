package com.devcli.runtime.store;

import java.time.Instant;

/** Run 的一次有租约执行尝试。 */
public record AttemptRecord(
        String id,
        String runId,
        long sequence,
        AttemptStatus status,
        String workerId,
        String reason,
        Instant leaseExpiresAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt
) {
    public AttemptRecord {
        status = status == null ? AttemptStatus.RUNNING : status;
        workerId = workerId == null ? "" : workerId;
        reason = reason == null ? "" : reason;
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
    }
}
