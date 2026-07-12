package com.devcli.plan;

import java.util.List;

/**
 * Plan、Multi-Agent 与 checkpoint 共用的任务执行产物。
 */
public record ExecutionArtifact(
        String nodeId,
        ExecutionGraph.NodeState state,
        String output,
        String summary,
        List<String> modifiedResources,
        String error,
        int attempt,
        long startedAt,
        long finishedAt
) {
    public ExecutionArtifact {
        nodeId = nodeId == null ? "" : nodeId;
        state = state == null ? ExecutionGraph.NodeState.PENDING : state;
        output = output == null ? "" : output;
        summary = summary == null ? "" : summary;
        modifiedResources = modifiedResources == null ? List.of() : List.copyOf(modifiedResources);
        error = error == null ? "" : error;
        attempt = Math.max(1, attempt);
        startedAt = Math.max(0, startedAt);
        finishedAt = Math.max(0, finishedAt);
    }

    public static ExecutionArtifact pending(String nodeId) {
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.PENDING,
                "", "", List.of(), "", 1, 0, 0);
    }

    public static ExecutionArtifact completed(String nodeId, String output, String summary,
                                              List<String> modifiedResources) {
        long now = System.currentTimeMillis();
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.COMPLETED,
                output, summary, modifiedResources, "", 1, now, now);
    }

    public static ExecutionArtifact failed(String nodeId, String error, String summary,
                                           List<String> modifiedResources) {
        long now = System.currentTimeMillis();
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.FAILED,
                "", summary, modifiedResources, error, 1, now, now);
    }

    public ExecutionArtifact start(long timestamp) {
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.RUNNING,
                output, summary, modifiedResources, "", attempt, timestamp, 0);
    }

    public ExecutionArtifact complete(String output, String summary,
                                      List<String> modifiedResources, long timestamp) {
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.COMPLETED,
                output, summary, modifiedResources, "", attempt,
                startedAt == 0 ? timestamp : startedAt, timestamp);
    }

    public ExecutionArtifact fail(String error, String summary,
                                  List<String> modifiedResources, long timestamp) {
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.FAILED,
                output, summary, modifiedResources, error, attempt,
                startedAt == 0 ? timestamp : startedAt, timestamp);
    }

    public ExecutionArtifact resetForRetry() {
        return new ExecutionArtifact(nodeId, ExecutionGraph.NodeState.PENDING,
                "", "", List.of(), error, attempt + 1, 0, 0);
    }

    public ExecutionArtifact withState(ExecutionGraph.NodeState state) {
        return new ExecutionArtifact(nodeId, state, output, summary, modifiedResources,
                error, attempt, startedAt, finishedAt);
    }

    public ExecutionArtifact withOutput(String output) {
        return new ExecutionArtifact(nodeId, state, output, summary, modifiedResources,
                error, attempt, startedAt, finishedAt);
    }

    public ExecutionArtifact withSummary(String summary) {
        return new ExecutionArtifact(nodeId, state, output, summary, modifiedResources,
                error, attempt, startedAt, finishedAt);
    }

    public ExecutionArtifact withModifiedResources(List<String> resources) {
        return new ExecutionArtifact(nodeId, state, output, summary, resources,
                error, attempt, startedAt, finishedAt);
    }

    public ExecutionArtifact withError(String error) {
        return new ExecutionArtifact(nodeId, state, output, summary, modifiedResources,
                error, attempt, startedAt, finishedAt);
    }

    public boolean terminal() {
        return state == ExecutionGraph.NodeState.COMPLETED
                || state == ExecutionGraph.NodeState.FAILED;
    }

    public boolean successful() {
        return state == ExecutionGraph.NodeState.COMPLETED;
    }
}
