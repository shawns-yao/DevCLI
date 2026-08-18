package com.devcli.runtime.store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 本地 Run 生命周期的唯一持久化契约。 */
public interface RunStore extends AutoCloseable {

    enum Source {
        INTERACTIVE,
        RUNTIME_API,
        BACKGROUND
    }

    enum Status {
        ENQUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELED,
        REJECTED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
        }
    }

    record Submission(String id, String threadId, String branchId, Source source,
                      String executionPolicy, String prompt) {
        public Submission {
            id = id == null ? "" : id.trim();
            threadId = threadId == null ? "" : threadId.trim();
            branchId = branchId == null || branchId.isBlank() ? "main" : branchId.trim();
            source = source == null ? Source.INTERACTIVE : source;
            executionPolicy = executionPolicy == null || executionPolicy.isBlank()
                    ? "react" : executionPolicy.trim();
            prompt = prompt == null ? "" : prompt.trim();
        }
    }

    record RunRecord(String id, String threadId, String branchId, Source source,
                     String executionPolicy, Status status, String prompt,
                     String result, String error, String recoveryReason,
                     Instant createdAt, Instant startedAt, Instant finishedAt,
                     Instant updatedAt, long durationMs, int attempt, long version) {
        public RunRecord {
            id = id == null ? "" : id;
            threadId = threadId == null ? "" : threadId;
            branchId = branchId == null || branchId.isBlank() ? "main" : branchId;
            source = source == null ? Source.INTERACTIVE : source;
            executionPolicy = executionPolicy == null || executionPolicy.isBlank()
                    ? "react" : executionPolicy;
            status = status == null ? Status.ENQUEUED : status;
            prompt = prompt == null ? "" : prompt;
            result = result == null ? "" : result;
            error = error == null ? "" : error;
            recoveryReason = recoveryReason == null ? "" : recoveryReason;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
            durationMs = Math.max(0, durationMs);
            attempt = Math.max(0, attempt);
            version = Math.max(0, version);
        }

        public boolean terminal() {
            return status.terminal();
        }
    }

    RunRecord create(Submission submission);

    Optional<RunRecord> find(String id);

    List<RunRecord> list(Source source, int limit);

    Optional<RunRecord> claimNext(Source source);

    boolean start(String id);

    boolean complete(String id, String result);

    boolean fail(String id, String error);

    boolean reject(String id, String reason);

    boolean cancel(String id, String reason);

    Optional<RunRecord> activeRun(String threadId);

    int recoverRunning(Source source, Status target, String reason);

    Path dbPath();

    @Override
    void close();
}
