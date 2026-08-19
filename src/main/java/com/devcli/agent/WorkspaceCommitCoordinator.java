package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.ProjectCommitCoordinator;
import com.devcli.workspace.WorkspaceExecutionSession;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 协调隔离工作区补丁、checkpoint 写前日志和步骤终态持久化。
 */
final class WorkspaceCommitCoordinator {

    static RecoveryEvidenceRef.State evidenceState(PatchSet.ApplyResult applyResult) {
        Objects.requireNonNull(applyResult, "applyResult");
        if (applyResult.applied()) {
            return RecoveryEvidenceRef.State.COMPLETED;
        }
        if (!applyResult.conflicts().isEmpty()) {
            return RecoveryEvidenceRef.State.FAILED;
        }
        return applyResult.rollbackFailures().isEmpty()
                ? RecoveryEvidenceRef.State.ROLLED_BACK
                : RecoveryEvidenceRef.State.FAILED;
    }

    static boolean shouldFinalizeJournal(PatchSet.ApplyResult applyResult) {
        Objects.requireNonNull(applyResult, "applyResult");
        return applyResult.applied() || applyResult.rollbackComplete();
    }

    AgentCheckpoint.PatchReconcileResult reconcile(AgentCheckpoint checkpoint,
                                                    Path projectRoot) throws Exception {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        return ProjectCommitCoordinator.withProjectLock(root,
                () -> checkpoint.reconcilePendingPatchCommits(root));
    }

    PatchSet.ApplyResult commit(WorkspaceExecutionSession session,
                                PatchSet patchSet,
                                AgentCheckpoint checkpoint,
                                String stepId,
                                Path projectRoot,
                                ExecutionArtifact intendedArtifact,
                                Consumer<PatchSet.ApplyResult> terminalPersistence) throws Exception {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(patchSet, "patchSet");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Consumer<PatchSet.ApplyResult> persistence = terminalPersistence == null
                ? result -> { }
                : terminalPersistence;

        return session.commit(patchSet,
                () -> {
                    if (checkpoint != null) {
                        checkpoint.preparePatchCommit(
                                stepId, projectRoot, patchSet, intendedArtifact);
                    }
                },
                applyResult -> {
                    RecoveryEvidenceRef.State state = evidenceState(applyResult);
                    boolean finalizeJournal = shouldFinalizeJournal(applyResult);
                    if (checkpoint != null) {
                        checkpoint.recordPatchTerminal(stepId, state);
                        if (finalizeJournal) {
                            checkpoint.markPatchCommitTerminal(stepId);
                        }
                    }
                    persistence.accept(applyResult);
                    if (checkpoint != null && finalizeJournal) {
                        checkpoint.cleanupPatchJournal(stepId);
                    }
                });
    }
}
