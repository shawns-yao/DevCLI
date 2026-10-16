package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 把计划 checkpoint 的恢复、写前日志和终态持久化接到统一委派的工作区生命周期。 */
record DelegationCheckpoint(CheckpointCoordinator coordinator, String stepId, Path projectRoot,
                            PreReviewVerifier verifier,
                            Consumer<com.devcli.tool.ToolContract.ToolExecutionResult> observeEvidence,
                            BiFunction<WorkspaceExecutionSession, ObjectNode, ExecutionArtifact> artifact,
                            Consumer<ExecutionArtifact> persist) {
    void restore(WorkspaceExecutionSession workspace, String scope) throws IOException {
        if (coordinator.hasDeferredPatch(stepId)) workspace.restorePatch(coordinator.loadDeferredPatch(stepId), scope);
    }

    PatchSet.ApplyResult commit(WorkspaceExecutionSession workspace, PatchSet patch, ObjectNode report,
                                WorkspaceExecutionSession.CommitPreparation validation) throws Exception {
        ExecutionArtifact intended = artifact.apply(workspace, report);
        return coordinator.commitWorkspace(workspace, patch, stepId, projectRoot, intended,
                validation, applied -> {
                    if (applied.applied()) {
                        coordinator.persistTerminalClearingDeferredPatch(stepId,
                                () -> persist.accept(intended.withModifiedResources(applied.modifiedResources())));
                    }
                });
    }

    void retainFailure(WorkspaceExecutionSession workspace, ObjectNode report) throws IOException {
        if ("INFRASTRUCTURE".equals(report.path("hard_check_failure_kind").asText())) {
            PatchSet patch = workspace.patchSet();
            if (!patch.isEmpty()) coordinator.preserveDeferredPatch(stepId, patch, report.toString());
        }
    }
}
