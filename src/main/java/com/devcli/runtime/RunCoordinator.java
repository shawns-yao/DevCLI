package com.devcli.runtime;

import com.devcli.runtime.store.RunRecord;
import com.devcli.runtime.store.RunStatus;
import com.devcli.runtime.store.RunStore;
import com.devcli.runtime.store.RunSubmission;
import com.devcli.runtime.store.SubmissionSource;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.observability.RunTelemetry;
import com.devcli.runtime.store.AttemptStatus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Run 生命周期协调器：上下文绑定、预算恢复和 CAS 终态只从这里进入。 */
public final class RunCoordinator {
    private final RetryPolicy retryPolicy = new RetryPolicy();
    private final RunStore store;
    private final Duration leaseDuration;

    public RunCoordinator(RunStore store) {
        this(store, Duration.ofMinutes(5));
    }

    public RunCoordinator(RunStore store, Duration leaseDuration) {
        this.store = Objects.requireNonNull(store, "store");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    public RunRecord submit(SubmissionSource source, Path projectPath, String prompt,
                            String threadId, String idempotencyKey) {
        return store.submit(new RunSubmission(
                "", source, threadId, projectPath, prompt, idempotencyKey, ""));
    }

    public RunRecord ensureSubmitted(String runId, SubmissionSource source,
                                     Path projectPath, String prompt,
                                     String threadId, String idempotencyKey) {
        return store.find(runId).orElseGet(() -> store.submit(new RunSubmission(
                runId, source, threadId, projectPath, prompt, idempotencyKey, "")));
    }

    public Optional<ClaimedRunContext> claim(String runId, String workerId) {
        return claim(runId, workerId, RunEventSink.NO_OP);
    }

    public Optional<ClaimedRunContext> claim(String runId, String workerId, RunEventSink events) {
        RunRecord requested = store.find(runId).orElse(null);
        if (requested == null || requested.terminal() || requested.status() == RunStatus.RUNNING) {
            return Optional.empty();
        }
        if (requested.status() == RunStatus.RECOVERY_REQUIRED) {
            return Optional.empty();
        }
        return store.claimNextById(runId, workerId, leaseDuration)
                .map(claimed -> bind(claimed, events));
    }

    public Optional<ClaimedRunContext> claimRecovery(
            String runId, String workerId, RecoveryProof proof, RunEventSink events) {
        RunRecord requested = store.find(runId).orElse(null);
        if (requested == null || requested.status() != RunStatus.RECOVERY_REQUIRED) {
            return Optional.empty();
        }
        RecoveryProof normalized = proof == null ? RecoveryProof.unsafe() : proof;
        RetryPolicy.Decision recovery = retryPolicy.recovery(
                normalized.patchJournalReconciled(),
                normalized.checkpointValid() && checkpointRequiredAndSafe(requested),
                normalized.budgetRestored() && budgetReferenceSafe(requested));
        RunEventSink eventSink = events == null ? RunEventSink.NO_OP : events;
        eventSink.emit(new RunEvent.RecoveryReconciled(
                requested.id(), requested.checkpointRef(), normalized.patchJournalAction(),
                recovery.retry() ? "ALLOW" : "DENY", recovery.reason()));
        if (!recovery.retry()) return Optional.empty();
        return store.claimRecoveryById(runId, workerId, leaseDuration)
                .map(claimed -> bind(claimed, eventSink));
    }

    public Optional<ClaimedRunContext> claimNext(SubmissionSource source, String workerId) {
        return store.claimNext(source, workerId, leaseDuration)
                .map(claimed -> bind(claimed, RunEventSink.NO_OP));
    }

    public boolean complete(ClaimedRunContext claimed, RunStatus status,
                            String result, String error) {
        Objects.requireNonNull(claimed, "claimed");
        if (status == null || !status.terminal()) {
            throw new IllegalArgumentException("terminal status is required");
        }
        RunRecord current = store.find(claimed.run().id()).orElse(claimed.run());
        if (current.status() != RunStatus.RUNNING) return false;
        return store.complete(current.id(), current.version(), claimed.attempt().id(),
                status, result, error, claimed.context().budgetState());
    }

    public boolean cancel(String runId, String reason, RunContext context) {
        RunRecord current = store.find(runId).orElse(null);
        return current != null && store.cancel(runId, current.version(), reason,
                context == null ? null : context.budgetState());
    }

    public List<RunRecord> reconcileStartup() {
        return store.reconcileExpiredLeases();
    }

    public RunStore store() {
        return store;
    }

    private ClaimedRunContext bind(RunStore.ClaimedRun claimed, RunEventSink events) {
        RunRecord run = claimed.run();
        RunEventSink eventSink = events == null ? RunEventSink.NO_OP : events;
        Path projectPath = run.projectPath().isBlank()
                ? Path.of(System.getProperty("user.dir")) : Path.of(run.projectPath());
        RunContext context = CancellationContext.startRunContext(
                run.id(), projectPath, restoredBudgetState(run));
        RunPersistenceSink persistence = new RunPersistenceSink() {
            @Override
            public boolean saveRecoveryReferences(String checkpointRef, String patchJournalRef,
                                                  String snapshotRef) {
                RunRecord current = store.find(run.id()).orElse(null);
                boolean saved = current != null && store.saveRecoveryReferences(
                        current.id(), current.version(),
                        preserveBlank(checkpointRef, current.checkpointRef()),
                        preserveBlank(patchJournalRef, current.patchJournalRef()),
                        preserveBlank(snapshotRef, current.snapshotRef()));
                if (saved) {
                    eventSink.emit(new RunEvent.RecoveryReferenceUpdated(
                            run.id(), checkpointRef, patchJournalRef, snapshotRef, "saved"));
                }
                return saved;
            }

            @Override
            public boolean clearRecoveryReferences(boolean checkpoint, boolean patchJournal,
                                                   boolean snapshot) {
                RunRecord current = store.find(run.id()).orElse(null);
                boolean cleared = current != null && store.clearRecoveryReferences(
                        current.id(), current.version(), checkpoint, patchJournal, snapshot);
                if (cleared) {
                    eventSink.emit(new RunEvent.RecoveryReferenceUpdated(
                            run.id(), checkpoint ? "<cleared>" : current.checkpointRef(),
                            patchJournal ? "<cleared>" : current.patchJournalRef(),
                            snapshot ? "<cleared>" : current.snapshotRef(), "cleared"));
                }
                return cleared;
            }
        };
        AttemptPersistence attempts = new AttemptPersistence() {
            @Override
            public void started(AttemptData attempt) {
                try {
                    store.startNestedAttempt(attempt);
                } catch (RuntimeException error) {
                    if (attempt.kind() == AttemptKind.INITIAL) return;
                    throw error;
                }
            }

            @Override
            public void finished(String attemptId, AttemptStatus status, String outcome) {
                store.finishNestedAttempt(attemptId, status, outcome);
            }
        };
        context.configureObservability(new RunTelemetry(
                run.id(), "", "", "", claimed.attempt().id(), run.id()),
                com.devcli.observability.MetricRecorder.NO_OP);
        context.configureRuntimeServices(eventSink, attempts, persistence, claimed.attempt().id());
        return new ClaimedRunContext(claimed, context);
    }

    private static String preserveBlank(String candidate, String existing) {
        return candidate == null || candidate.isBlank() ? existing : candidate;
    }

    private static RunContext.RunBudgetState restoredBudgetState(RunRecord run) {
        if (run.budgetStateJson().isBlank()) return null;
        try {
            return com.devcli.runtime.store.SqliteRunStore.decodeBudget(run.budgetStateJson());
        } catch (Exception error) {
            throw new IllegalStateException("恢复 RunBudget 失败: " + error.getMessage(), error);
        }
    }

    private static boolean checkpointRequiredAndSafe(RunRecord run) {
        return !run.checkpointRef().isBlank()
                && (run.checkpointRef().startsWith("runtime-checkpoint:")
                || run.checkpointRef().startsWith("agent-checkpoint:"));
    }

    private static boolean budgetReferenceSafe(RunRecord run) {
        return run.budgetStateJson().isBlank() || restoredBudgetState(run) != null;
    }

    public record RecoveryProof(boolean patchJournalReconciled,
                                boolean checkpointValid,
                                boolean budgetRestored,
                                String patchJournalAction) {
        public RecoveryProof {
            patchJournalAction = patchJournalAction == null ? "" : patchJournalAction;
        }

        public static RecoveryProof unsafe() {
            return new RecoveryProof(false, false, false, "not_reconciled");
        }
    }

    public record ClaimedRunContext(RunStore.ClaimedRun claimed, RunContext context)
            implements AutoCloseable {
        public ClaimedRunContext {
            Objects.requireNonNull(claimed, "claimed");
            Objects.requireNonNull(context, "context");
        }

        public RunRecord run() {
            return claimed.run();
        }

        public com.devcli.runtime.store.AttemptRecord attempt() {
            return claimed.attempt();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
