package com.devcli.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务节点 - 表示一个可执行的任务单元。
 */
public class Task implements ExecutionNode {
    private final String id;
    private final String description;
    private final TaskType type;
    private volatile ExecutionArtifact artifact;
    private final List<String> dependencies;
    private final List<String> dependents;

    public enum TaskType {
        PLANNING,
        FILE_READ,
        FILE_WRITE,
        COMMAND,
        ANALYSIS,
        VERIFICATION
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public Task(String id, String description, TaskType type) {
        this.id = id;
        this.description = description;
        this.type = type;
        this.artifact = ExecutionArtifact.pending(id);
        this.dependencies = new ArrayList<>();
        this.dependents = new ArrayList<>();
    }

    public Task(String id, String description, TaskType type, List<String> dependencies) {
        this(id, description, type);
        if (dependencies != null) {
            this.dependencies.addAll(dependencies);
        }
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return taskStatus(artifact.state()); }
    public String getResult() { return artifact.output(); }
    public String getError() { return artifact.error(); }
    public List<String> getModifiedFiles() { return new ArrayList<>(artifact.modifiedResources()); }
    public String getResultSummary() { return artifact.summary(); }
    public List<String> getDependencies() { return new ArrayList<>(dependencies); }
    public List<String> getDependents() { return new ArrayList<>(dependents); }
    public long getStartTime() { return artifact.startedAt(); }
    public long getEndTime() { return artifact.finishedAt(); }
    public ExecutionArtifact getArtifact() { return artifact; }

    @Override
    public String id() { return id; }

    @Override
    public String description() { return description; }

    @Override
    public List<String> dependencies() { return List.copyOf(dependencies); }

    @Override
    public ExecutionArtifact artifact() { return artifact; }

    public void setStatus(TaskStatus status) {
        this.artifact = artifact.withState(graphState(status));
    }

    public void setResult(String result) {
        this.artifact = artifact.withOutput(result);
    }

    public void setError(String error) {
        this.artifact = artifact.withError(error);
    }

    public void setModifiedFiles(List<String> modifiedFiles) {
        this.artifact = artifact.withModifiedResources(modifiedFiles);
    }

    public void setResultSummary(String resultSummary) {
        this.artifact = artifact.withSummary(resultSummary == null ? "" : resultSummary.trim());
    }

    public void applyArtifact(ExecutionArtifact artifact) {
        if (artifact != null && id.equals(artifact.nodeId())) {
            this.artifact = artifact;
        }
    }

    public void addDependent(String taskId) {
        if (!dependents.contains(taskId)) {
            dependents.add(taskId);
        }
    }

    public void addDependency(String taskId) {
        if (!dependencies.contains(taskId)) {
            dependencies.add(taskId);
        }
    }

    public void markStarted() {
        this.artifact = artifact.start(System.currentTimeMillis());
    }

    public void markCompleted(String result) {
        this.artifact = artifact.complete(
                result, artifact.summary(), artifact.modifiedResources(), System.currentTimeMillis());
    }

    public void markFailed(String error) {
        this.artifact = artifact.fail(
                error, artifact.summary(), artifact.modifiedResources(), System.currentTimeMillis());
    }

    public long getDuration() {
        if (artifact.startedAt() == 0) return 0;
        if (artifact.finishedAt() == 0) return System.currentTimeMillis() - artifact.startedAt();
        return artifact.finishedAt() - artifact.startedAt();
    }

    public boolean isExecutable(Map<String, Task> allTasks) {
        if (getStatus() != TaskStatus.PENDING) return false;
        for (String depId : dependencies) {
            Task dep = allTasks.get(depId);
            if (dep == null || dep.getStatus() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private static ExecutionGraph.NodeState graphState(TaskStatus status) {
        return switch (status) {
            case PENDING -> ExecutionGraph.NodeState.PENDING;
            case RUNNING -> ExecutionGraph.NodeState.RUNNING;
            case COMPLETED -> ExecutionGraph.NodeState.COMPLETED;
            case FAILED -> ExecutionGraph.NodeState.FAILED;
        };
    }

    private static TaskStatus taskStatus(ExecutionGraph.NodeState state) {
        return switch (state) {
            case PENDING -> TaskStatus.PENDING;
            case RUNNING -> TaskStatus.RUNNING;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED -> TaskStatus.FAILED;
        };
    }

    @Override
    public String toString() {
        return String.format("Task[%s: %s] (%s)", id, description, getStatus());
    }
}
