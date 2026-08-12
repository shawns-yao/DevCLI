package com.devcli.runtime;

import com.devcli.runtime.store.RunRecord;
import com.devcli.runtime.store.RunStatus;
import com.devcli.runtime.store.RunStore;
import com.devcli.runtime.store.RunSubmission;
import com.devcli.runtime.store.SubmissionSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Run 生命周期协调器：上下文绑定、预算恢复和 CAS 终态只从这里进入。 */
public final class RunCoordinator {
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
        RunRecord requested = store.find(runId).orElse(null);
        if (requested == null || requested.terminal() || requested.status() == RunStatus.RUNNING) {
            return Optional.empty();
        }
        return store.claimNextById(runId, workerId, leaseDuration).map(claimed -> {
            RunRecord run = claimed.run();
            Path projectPath = run.projectPath().isBlank()
                    ? Path.of(System.getProperty("user.dir")) : Path.of(run.projectPath());
            RunContext context = CancellationContext.startRunContext(
                    run.id(), projectPath, restoredBudgetState(run));
            return new ClaimedRunContext(claimed, context);
        });
    }

    public Optional<ClaimedRunContext> claimNext(SubmissionSource source, String workerId) {
        return store.claimNext(source, workerId, leaseDuration).map(claimed -> {
            RunRecord run = claimed.run();
            Path projectPath = run.projectPath().isBlank()
                    ? Path.of(System.getProperty("user.dir")) : Path.of(run.projectPath());
            RunContext.RunBudgetState budgetState = restoredBudgetState(run);
            RunContext context = CancellationContext.startRunContext(run.id(), projectPath, budgetState);
            return new ClaimedRunContext(claimed, context);
        });
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

    private static RunContext.RunBudgetState restoredBudgetState(RunRecord run) {
        if (run.budgetStateJson().isBlank()) return null;
        try {
            return com.devcli.runtime.store.SqliteRunStore.decodeBudget(run.budgetStateJson());
        } catch (Exception error) {
            throw new IllegalStateException("恢复 RunBudget 失败: " + error.getMessage(), error);
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
