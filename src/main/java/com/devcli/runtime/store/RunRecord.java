package com.devcli.runtime.store;

import java.time.Instant;

/** RunStore 中的统一运行事实。 */
public record RunRecord(
        String id,
        SubmissionSource source,
        RunStatus status,
        String threadId,
        String projectPath,
        String prompt,
        String result,
        String error,
        String idempotencyKey,
        long version,
        long currentAttempt,
        Instant leaseExpiresAt,
        String budgetStateJson,
        String checkpointRef,
        String patchJournalRef,
        String snapshotRef,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        long durationMs
) {
    public RunRecord {
        source = source == null ? SubmissionSource.CLI : source;
        status = status == null ? RunStatus.ENQUEUED : status;
        threadId = text(threadId);
        projectPath = text(projectPath);
        prompt = text(prompt);
        result = text(result);
        error = text(error);
        idempotencyKey = text(idempotencyKey);
        budgetStateJson = text(budgetStateJson);
        checkpointRef = text(checkpointRef);
        patchJournalRef = text(patchJournalRef);
        snapshotRef = text(snapshotRef);
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public boolean terminal() {
        return status.terminal();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
