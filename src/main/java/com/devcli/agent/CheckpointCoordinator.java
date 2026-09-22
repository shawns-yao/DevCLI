package com.devcli.agent;

import com.devcli.memory.MemoryManager;
import com.devcli.plan.ExecutionArtifact;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 承载 checkpoint 的创建、恢复迁移、写前对账和终态持久化策略。
 * 编排器只决定何时进入恢复或执行，不直接操作 checkpoint 存储细节。
 */
final class CheckpointCoordinator {
    record OpenResult(AgentCheckpoint checkpoint, String error) {
        boolean opened() {
            return checkpoint != null && (error == null || error.isBlank());
        }
    }

    record RestoredPlan(List<AgentOrchestrator.ExecutionStep> steps,
                        List<AcceptanceCriterion> criteria,
                        Set<String> ordinaryStepIds) {
    }

    record DeferredPatchRestore(boolean present, boolean restored, String failure) {
        static DeferredPatchRestore absent() {
            return new DeferredPatchRestore(false, true, "");
        }
    }

    private final OrchestrationRunState runState;
    private final MemoryManager memoryManager;
    private final WorkspaceCommitCoordinator workspaceCommitCoordinator;

    CheckpointCoordinator(OrchestrationRunState runState,
                          MemoryManager memoryManager,
                          WorkspaceCommitCoordinator workspaceCommitCoordinator) {
        this.runState = Objects.requireNonNull(runState, "runState");
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.workspaceCommitCoordinator = Objects.requireNonNull(
                workspaceCommitCoordinator, "workspaceCommitCoordinator");
    }

    AgentCheckpoint create(String orchestrationId,
                           String goal,
                           List<AgentOrchestrator.ExecutionStep> steps,
                           List<AcceptanceCriterion> criteria,
                           List<AgentCheckpoint.AgentIdentityRecord> identities,
                           String plannerId,
                           String plannerSummary) {
        AgentCheckpoint checkpoint = new AgentCheckpoint(orchestrationId, goal);
        checkpoint.setPlanSteps(toPlanSteps(steps));
        checkpoint.setAcceptanceCriteria(toCriterionRecords(criteria));
        checkpoint.ensureAgentIdentities(identities);
        checkpoint.advanceAgentCursor(plannerId, "", plannerSummary);
        checkpoint.save();
        runState.setCheckpoint(checkpoint);
        return checkpoint;
    }

    OpenResult openForResume(String requestedId, Path projectRoot) {
        AgentCheckpoint.LoadResult loadResult = requestedId == null || requestedId.isBlank()
                ? AgentCheckpoint.loadLatestResult()
                : AgentCheckpoint.loadResult(requestedId.trim());
        if (loadResult.status() == AgentCheckpoint.LoadStatus.INCOMPATIBLE) {
            return new OpenResult(null, "❌ " + loadResult.message());
        }
        AgentCheckpoint loaded = loadResult.checkpoint();
        if (loaded == null) {
            return new OpenResult(null, formatNoCheckpointMessage(requestedId));
        }
        try {
            AgentCheckpoint.PatchReconcileResult reconcile =
                    workspaceCommitCoordinator.reconcile(loaded, projectRoot);
            if (!reconcile.failures().isEmpty()) {
                return new OpenResult(null, "❌ checkpoint [" + loaded.getOrchestrationId()
                        + "] 存在无法自动回滚的 PatchSet 写前日志：" + reconcile.failures());
            }
        } catch (Exception e) {
            return new OpenResult(null, "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] PatchSet 恢复对账保存失败：" + e.getMessage());
        }
        runState.setCheckpoint(loaded);
        return new OpenResult(loaded, "");
    }

    RestoredPlan restorePlan(AgentCheckpoint.RecoveryState recovery,
                             boolean migrateLegacyCriteria) {
        List<AgentOrchestrator.ExecutionStep> steps = rebuildSteps(recovery);
        List<AcceptanceCriterion> criteria = fromCriterionRecords(
                recovery.acceptanceCriteria(), migrateLegacyCriteria);
        Set<String> ordinaryStepIds = steps.stream()
                .filter(step -> !AgentOrchestrator.isFinalIntegrationStep(step))
                .map(AgentOrchestrator.ExecutionStep::id)
                .collect(Collectors.toUnmodifiableSet());
        runState.restore(recovery.goal(), criteria, ordinaryStepIds, recovery);
        return new RestoredPlan(steps, criteria, ordinaryStepIds);
    }

    List<AgentOrchestrator.ExecutionStep> rebuildSteps(AgentCheckpoint checkpoint) {
        return rebuildSteps(checkpoint.recoveryState());
    }

    List<AgentOrchestrator.ExecutionStep> rebuildSteps(AgentCheckpoint.RecoveryState recovery) {
        Set<String> deferredDependents = new java.util.HashSet<>(recovery.deferredPatchSteps());
        boolean changed;
        do {
            changed = false;
            for (AgentCheckpoint.PlanStep planStep : recovery.planSteps()) {
                if (!deferredDependents.contains(planStep.id())
                        && planStep.dependencies() != null
                        && planStep.dependencies().stream().anyMatch(deferredDependents::contains)) {
                    changed |= deferredDependents.add(planStep.id());
                }
            }
        } while (changed);
        return new java.util.ArrayList<>(recovery.planSteps().stream()
                .map(planStep -> restoreStep(
                        planStep, recovery, deferredDependents.contains(planStep.id())))
                .toList());
    }

    private AgentOrchestrator.ExecutionStep restoreStep(
            AgentCheckpoint.PlanStep planStep,
            AgentCheckpoint.RecoveryState recovery,
            boolean retryDeferredDependency) {
        List<String> dependencies = planStep.dependencies() == null
                ? List.of()
                : planStep.dependencies();
        ExecutionArtifact artifact = recovery.artifacts().get(planStep.id());
        if (retryDeferredDependency) {
            return AgentOrchestrator.ExecutionStep.pending(
                    planStep.id(), planStep.description(), planStep.type(), dependencies);
        }
        if (artifact != null
                && (artifact.successful() || !recovery.redoPendingSteps().contains(planStep.id()))) {
            return new AgentOrchestrator.ExecutionStep(
                    planStep.id(), planStep.description(), planStep.type(), dependencies, artifact);
        }
        return AgentOrchestrator.ExecutionStep.pending(
                planStep.id(), planStep.description(), planStep.type(), dependencies);
    }

    void recordStep(String stepId, AgentOrchestrator.ExecutionStep updated) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null) {
            return;
        }
        checkpoint.recordAttemptDigests(memoryManager.getSessionMemory().snapshot()
                .attemptDigests().stream()
                .filter(attempt -> stepId.equals(attempt.stepId()))
                .map(attempt -> new AgentCheckpoint.AttemptDigestRecord(
                        attempt.stepId(), attempt.digest(), attempt.reference(), attempt.sequence()))
                .toList());
        if (updated.status() == AgentOrchestrator.StepStatus.COMPLETED) {
            checkpoint.addCompletedStep(stepId, updated.modifiedFiles(), updated.result());
            saveStrict();
        } else if (updated.status() == AgentOrchestrator.StepStatus.FAILED) {
            checkpoint.addFailedStep(stepId, updated.modifiedFiles(), updated.result());
            saveStrict();
        }
    }

    void recordRedo(String stepId, int attempt, String failureReason, List<String> modifiedFiles) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null) {
            return;
        }
        checkpoint.recordRedoAttempt(stepId, attempt, failureReason, modifiedFiles);
        saveStrict();
    }

    boolean hasDeferredPatch(String stepId) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        return checkpoint != null && checkpoint.hasDeferredPatch(stepId);
    }

    void preserveDeferredPatch(String stepId, PatchSet patchSet, String failureReason) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null || patchSet == null || patchSet.isEmpty()) {
            return;
        }
        try {
            checkpoint.preserveDeferredPatch(stepId, patchSet, failureReason);
        } catch (IOException e) {
            throw new IllegalStateException("待验证 PatchSet 持久化失败: " + e.getMessage(), e);
        }
    }

    DeferredPatchRestore restoreDeferredPatch(String stepId, Path workspaceRoot) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null || !checkpoint.hasDeferredPatch(stepId)) {
            return DeferredPatchRestore.absent();
        }
        try {
            PatchSet.ApplyResult result = checkpoint.loadDeferredPatch(stepId).apply(workspaceRoot);
            return new DeferredPatchRestore(true, result.applied(),
                    result.applied() ? "" : result.failureDescription());
        } catch (IOException e) {
            return new DeferredPatchRestore(true, false,
                    "待验证 PatchSet 读取失败: " + e.getMessage());
        }
    }

    void persistTerminalClearingDeferredPatch(String stepId, Runnable terminalPersistence) {
        Objects.requireNonNull(terminalPersistence, "terminalPersistence");
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null) {
            terminalPersistence.run();
            return;
        }
        AgentCheckpoint.DeferredPatch removed = checkpoint.markDeferredPatchTerminal(stepId);
        try {
            terminalPersistence.run();
        } catch (RuntimeException e) {
            checkpoint.restoreDeferredPatchMetadata(removed);
            throw e;
        }
        checkpoint.cleanupDeferredPatchFiles(removed);
    }

    String assignedWorkerId(String stepId) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null) {
            return "";
        }
        AgentCheckpoint.StepAssignmentRecord assignment = checkpoint.getStepAssignments().get(stepId);
        return assignment == null ? "" : assignment.workerAgentId();
    }

    void assignStep(String stepId, String workerId, String reviewerId) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint != null) {
            checkpoint.assignStep(stepId, workerId, reviewerId);
            saveStrict();
        }
    }

    void recordAgentMessage(String agentId, String stepId, String phase, AgentMessage message) {
        String type = message == null || message.type() == null ? "UNKNOWN" : message.type().name();
        String content = message == null || message.content() == null ? "" : message.content().trim();
        advanceAgentCursor(agentId, stepId,
                phase + " [" + type + "]" + (content.isBlank() ? "" : " " + content));
    }

    void recordAgentEvent(String agentId, String stepId, String phase, String summary) {
        advanceAgentCursor(agentId, stepId,
                phase + (summary == null || summary.isBlank() ? "" : ": " + summary.trim()));
    }

    private synchronized void advanceAgentCursor(String agentId, String stepId, String summary) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint != null && checkpoint.advanceAgentCursor(agentId, stepId, summary)) {
            saveStrict();
        }
    }

    synchronized void saveStrict() {
        AgentCheckpoint checkpoint = Objects.requireNonNull(runState.checkpoint(), "checkpoint");
        try {
            checkpoint.saveStrict();
        } catch (IOException e) {
            throw new IllegalStateException("Checkpoint 持久化失败: " + e.getMessage(), e);
        }
    }

    void finish(List<AgentOrchestrator.ExecutionStep> steps) {
        AgentCheckpoint checkpoint = runState.checkpoint();
        if (checkpoint == null) {
            return;
        }
        boolean allCompleted = steps.stream().allMatch(
                step -> step.status() == AgentOrchestrator.StepStatus.COMPLETED);
        if (allCompleted) {
            checkpoint.delete();
            runState.clearCheckpoint();
        } else {
            checkpoint.save();
        }
    }

    PatchSet.ApplyResult commitWorkspace(WorkspaceExecutionSession session,
                                         PatchSet patchSet,
                                         String stepId,
                                         Path projectRoot,
                                         ExecutionArtifact intendedArtifact,
                                         Consumer<PatchSet.ApplyResult> terminalPersistence)
            throws Exception {
        return workspaceCommitCoordinator.commit(session, patchSet, runState.checkpoint(), stepId,
                projectRoot, intendedArtifact, terminalPersistence);
    }

    static List<AgentCheckpoint.PlanStep> toPlanSteps(
            List<AgentOrchestrator.ExecutionStep> steps) {
        return steps.stream()
                .map(step -> new AgentCheckpoint.PlanStep(
                        step.id(), step.description(), step.type(), step.dependencies()))
                .toList();
    }

    static List<AgentCheckpoint.CriterionRecord> toCriterionRecords(
            List<AcceptanceCriterion> criteria) {
        return criteria.stream()
                .map(criterion -> new AgentCheckpoint.CriterionRecord(
                        criterion.id(), criterion.category(), criterion.description(),
                        criterion.testSignal(), criterion.severity(),
                        criterion.verificationMethod() == null
                                ? ""
                                : criterion.verificationMethod().name(),
                        criterion.verifier(), criterion.appliesTo()))
                .toList();
    }

    static List<AcceptanceCriterion> fromCriterionRecords(
            List<AgentCheckpoint.CriterionRecord> records,
            boolean migrateLegacyCriteria) {
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(record -> migrateCriterion(record, migrateLegacyCriteria))
                .filter(AcceptanceCriterion::isValid)
                .toList();
    }

    private static AcceptanceCriterion migrateCriterion(
            AgentCheckpoint.CriterionRecord record,
            boolean migrateLegacyCriteria) {
        AcceptanceCriterion.VerificationMethod method =
                AcceptanceCriterion.VerificationMethod.parse(record.verificationMethod());
        String verifier = record.verifier() == null ? "" : record.verifier();
        if (migrateLegacyCriteria && method == null) {
            method = AcceptanceCriterion.VerificationMethod.HUMAN;
            verifier = "人工核对旧验收标准："
                    + (record.description() == null ? "" : record.description());
        }
        List<String> appliesTo = record.appliesTo();
        if (appliesTo == null || appliesTo.isEmpty()) {
            appliesTo = List.of("FINAL");
        }
        return new AcceptanceCriterion(
                record.id() == null ? "" : record.id(),
                record.category() == null ? "" : record.category(),
                record.description() == null ? "" : record.description(),
                record.testSignal() == null ? "" : record.testSignal(),
                record.severity() == null || record.severity().isBlank()
                        ? "high"
                        : record.severity(),
                method,
                verifier,
                appliesTo);
    }

    private static String formatNoCheckpointMessage(String requestedId) {
        StringBuilder message = new StringBuilder();
        if (requestedId == null || requestedId.isBlank()) {
            message.append("❌ 没有可恢复的 checkpoint。");
        } else {
            message.append("❌ 未找到 checkpoint [").append(requestedId.trim()).append("]。");
        }
        List<AgentCheckpoint.CheckpointInfo> available = AgentCheckpoint.listAvailable();
        if (!available.isEmpty()) {
            message.append("\n可用的 checkpoint：\n");
            for (AgentCheckpoint.CheckpointInfo info : available) {
                message.append("  - ").append(info.orchestrationId())
                        .append("（完成 ").append(info.completedSteps())
                        .append(" 步，").append(info.timestamp()).append("）：")
                        .append(abbreviate(info.goal(), 80)).append('\n');
            }
            message.append("使用 /plan resume <id> 恢复指定任务。");
        }
        return message.toString();
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "\n...<truncated>";
    }
}
