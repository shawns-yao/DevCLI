package com.devcli.runtime.store;

import com.devcli.runtime.AttemptPersistence;
import com.devcli.runtime.RunContext;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 本地 Run、Attempt、预算与恢复引用的唯一持久化接口。 */
public interface RunStore extends AutoCloseable {
    RunRecord submit(RunSubmission submission);

    Optional<RunRecord> find(String runId);

    List<RunRecord> list(SubmissionSource source, int limit);

    Optional<ClaimedRun> claimNext(SubmissionSource source, String workerId, Duration leaseDuration);

    Optional<ClaimedRun> claimNextById(String runId, String workerId, Duration leaseDuration);

    Optional<ClaimedRun> claimRecoveryById(String runId, String workerId, Duration leaseDuration);

    boolean renewLease(String runId, long expectedVersion, String attemptId, Duration leaseDuration);

    boolean complete(String runId, long expectedVersion, String attemptId,
                     RunStatus terminalStatus, String result, String error,
                     RunContext.RunBudgetState budgetState);

    boolean cancel(String runId, long expectedVersion, String reason,
                   RunContext.RunBudgetState budgetState);

    boolean saveBudgetState(String runId, long expectedVersion,
                            RunContext.RunBudgetState budgetState);

    boolean saveRecoveryReferences(String runId, long expectedVersion,
                                   String checkpointRef, String patchJournalRef,
                                   String snapshotRef);

    boolean linkCheckpointByThread(String threadId, String checkpointRef);

    boolean clearRecoveryReferences(String runId, long expectedVersion,
                                    boolean checkpoint, boolean patchJournal,
                                    boolean snapshot);

    List<RunRecord> reconcileExpiredLeases();

    Optional<AttemptRecord> currentAttempt(String runId);

    List<AttemptRecord> attempts(String runId);

    void startNestedAttempt(AttemptPersistence.AttemptData attempt);

    void finishNestedAttempt(String attemptId, AttemptStatus status, String outcome);

    default Optional<String> latestActiveRunId() {
        return Optional.empty();
    }

    record ClaimedRun(RunRecord run, AttemptRecord attempt) {
    }
}
