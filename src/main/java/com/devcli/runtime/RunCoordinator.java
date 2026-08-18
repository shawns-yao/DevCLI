package com.devcli.runtime;

import com.devcli.runtime.store.RunStore;

import java.util.List;
import java.util.Optional;

/** 统一交互、Runtime API 与后台提交来源的 Run 状态迁移入口。 */
public final class RunCoordinator {
    private final RunStore store;

    public RunCoordinator(RunStore store) {
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    public RunStore.RunRecord submitBackground(String prompt) {
        return store.create(new RunStore.Submission(
                "", "", "main", RunStore.Source.BACKGROUND, "react", prompt));
    }

    public RunStore.RunRecord submitRuntime(
            String runId, String threadId, String branchId, String prompt) {
        return store.create(new RunStore.Submission(
                runId, threadId, branchId, RunStore.Source.RUNTIME_API, "react", prompt));
    }

    public RunStore.RunRecord submitInteractive(
            String runId, String threadId, String branchId, String executionPolicy, String prompt) {
        return store.create(new RunStore.Submission(
                runId, threadId, branchId, RunStore.Source.INTERACTIVE, executionPolicy, prompt));
    }

    public Optional<RunStore.RunRecord> claimBackground() {
        return store.claimNext(RunStore.Source.BACKGROUND);
    }

    public boolean start(String runId) {
        return store.start(runId);
    }

    public boolean complete(String runId, String result) {
        return store.complete(runId, result);
    }

    public boolean fail(String runId, String error) {
        return store.fail(runId, error);
    }

    public boolean reject(String runId, String reason) {
        return store.reject(runId, reason);
    }

    public boolean cancel(String runId, String reason) {
        return store.cancel(runId, reason);
    }

    public boolean cancelActive(String threadId, String reason) {
        return store.activeRun(threadId)
                .map(run -> store.cancel(run.id(), reason))
                .orElse(false);
    }

    public Optional<RunStore.RunRecord> find(String runId) {
        return store.find(runId);
    }

    public List<RunStore.RunRecord> listBackground(int limit) {
        return store.list(RunStore.Source.BACKGROUND, limit);
    }

    public int recoverBackgroundRuns() {
        return store.recoverRunning(
                RunStore.Source.BACKGROUND,
                RunStore.Status.ENQUEUED,
                "process_restarted_before_terminal_state");
    }
}
