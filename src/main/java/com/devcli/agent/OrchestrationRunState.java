package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;
import com.devcli.plan.ExecutionGraph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 当前一次 Plan/Team 编排的可变运行态，不持久化协议实现。 */
final class OrchestrationRunState {
    record RedoRecovery(Map<String, Integer> counts,
                        List<AgentCheckpoint.RedoAttemptRecord> attempts) {
    }

    private String userTask = "";
    private List<AcceptanceCriterion> acceptanceCriteria = List.of();
    private Set<String> planStepIds = Set.of();
    private AgentCheckpoint checkpoint;
    private final StepRedoTracker redoTracker;
    private Map<String, ExecutionArtifact> restoredFailedArtifacts = new HashMap<>();
    private Map<String, Integer> restoredRedoCounts = Map.of();
    private List<AgentCheckpoint.RedoAttemptRecord> restoredRedoAttempts = List.of();
    private List<AgentCheckpoint.AttemptDigestRecord> restoredAttemptDigests = List.of();

    OrchestrationRunState(int maxRedoPerStep) {
        this.redoTracker = new StepRedoTracker(maxRedoPerStep);
    }

    void beginNew(String task) {
        userTask = task == null ? "" : task;
        acceptanceCriteria = List.of();
        planStepIds = Set.of();
        checkpoint = null;
        restoredFailedArtifacts = new HashMap<>();
        restoredRedoCounts = Map.of();
        restoredRedoAttempts = List.of();
        restoredAttemptDigests = List.of();
    }

    void restore(String task,
                 List<AcceptanceCriterion> criteria,
                 Set<String> stepIds,
                 AgentCheckpoint.RecoveryState recovery) {
        userTask = task == null ? "" : task;
        acceptanceCriteria = criteria == null ? List.of() : List.copyOf(criteria);
        planStepIds = stepIds == null ? Set.of() : Set.copyOf(stepIds);
        restoreCheckpointArtifacts(recovery);
        restoredRedoCounts = recovery.redoCounts();
        restoredRedoAttempts = recovery.redoAttempts();
    }

    void restoreCheckpointArtifacts(AgentCheckpoint.RecoveryState recovery) {
        restoredFailedArtifacts = recovery.artifacts().entrySet().stream()
                .filter(entry -> entry.getValue().state() == ExecutionGraph.NodeState.FAILED)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        restoredAttemptDigests = recovery.attemptDigests();
    }

    RedoRecovery consumeRedoRecovery() {
        RedoRecovery recovery = new RedoRecovery(restoredRedoCounts, restoredRedoAttempts);
        restoredRedoCounts = Map.of();
        restoredRedoAttempts = List.of();
        return recovery;
    }

    String userTask() {
        return userTask;
    }

    void appendUserTaskRequirement(String requirement) {
        if (requirement != null && !requirement.isBlank()) {
            userTask = userTask + "\n执行前评审补充要求：" + requirement.trim();
        }
    }

    List<AcceptanceCriterion> acceptanceCriteria() {
        return acceptanceCriteria;
    }

    void setAcceptanceCriteria(List<AcceptanceCriterion> criteria) {
        acceptanceCriteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    Set<String> planStepIds() {
        return planStepIds;
    }

    void setPlanStepIds(Set<String> stepIds) {
        planStepIds = stepIds == null ? Set.of() : Set.copyOf(stepIds);
    }

    AgentCheckpoint checkpoint() {
        return checkpoint;
    }

    void setCheckpoint(AgentCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    void clearCheckpoint() {
        checkpoint = null;
    }

    StepRedoTracker redoTracker() {
        return redoTracker;
    }

    Map<String, ExecutionArtifact> restoredFailedArtifacts() {
        return restoredFailedArtifacts;
    }

    List<AgentCheckpoint.AttemptDigestRecord> restoredAttemptDigests() {
        return restoredAttemptDigests;
    }
}
