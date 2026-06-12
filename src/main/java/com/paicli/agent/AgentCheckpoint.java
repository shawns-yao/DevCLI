package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-Agent orchestration checkpoint for failure recovery.
 *
 * <p>当前实现范围：步骤完成/失败即落盘（{@code AgentOrchestrator.updateStep} 触发），
 * 全部成功后删除文件，崩溃后凭 {@code ~/.paicli/checkpoints/} 下的残留文件做事后排查。
 * <b>自动断点续跑未实现</b>——重新 run 会生成新计划和新步骤 id，无法直接对位旧 checkpoint。
 */
public class AgentCheckpoint {
    private static final Logger log = LoggerFactory.getLogger(AgentCheckpoint.class);
    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private String orchestrationId;
    private String goal;
    private List<String> completedSteps;
    private Map<String, StepArtifact> artifacts;
    private long timestamp;
    private int failedSteps;
    private String lastError;

    public record StepArtifact(String stepId, List<String> modifiedFiles, String summary) {}

    public AgentCheckpoint() {
        this.completedSteps = new ArrayList<>();
        this.artifacts = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public AgentCheckpoint(String orchestrationId, String goal) {
        this();
        this.orchestrationId = orchestrationId;
        this.goal = goal;
    }

    // ─────────────────────────────────────────────────────────
    // Checkpoint 操作
    // ─────────────────────────────────────────────────────────

    public void addCompletedStep(String stepId, List<String> modifiedFiles, String summary) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        completedSteps.add(stepId);
        artifacts.put(stepId, new StepArtifact(stepId, modifiedFiles, summary));
        timestamp = System.currentTimeMillis();
    }

    public void recordFailure(String error) {
        this.failedSteps++;
        this.lastError = error;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isStepCompleted(String stepId) {
        return completedSteps.contains(stepId);
    }

    public List<String> getCompletedSteps() {
        return new ArrayList<>(completedSteps);
    }

    // ─────────────────────────────────────────────────────────
    // 持久化
    // ─────────────────────────────────────────────────────────

    /**
     * 保存 Checkpoint 到磁盘
     */
    public void save() {
        try {
            Path checkpointDir = getCheckpointDir();
            Files.createDirectories(checkpointDir);

            Path checkpointFile = checkpointDir.resolve(orchestrationId + ".json");
            mapper.writeValue(checkpointFile.toFile(), this);

            log.info("Checkpoint 已保存: {} (已完成: {}/{} 步)",
                orchestrationId, completedSteps.size(), completedSteps.size() + failedSteps);
        } catch (Exception e) {
            log.error("保存 Checkpoint 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从磁盘加载 Checkpoint
     */
    public static AgentCheckpoint load(String orchestrationId) {
        try {
            Path checkpointFile = getCheckpointDir().resolve(orchestrationId + ".json");
            if (!Files.exists(checkpointFile)) {
                log.warn("Checkpoint 不存在: {}", orchestrationId);
                return null;
            }

            AgentCheckpoint checkpoint = mapper.readValue(checkpointFile.toFile(), AgentCheckpoint.class);
            log.info("Checkpoint 已加载: {} (已完成: {} 步)",
                orchestrationId, checkpoint.completedSteps.size());
            return checkpoint;
        } catch (Exception e) {
            log.error("加载 Checkpoint 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除 Checkpoint（orchestration 成功完成后）
     */
    public void delete() {
        try {
            Path checkpointFile = getCheckpointDir().resolve(orchestrationId + ".json");
            if (Files.exists(checkpointFile)) {
                Files.delete(checkpointFile);
                log.info("Checkpoint 已删除: {}", orchestrationId);
            }
        } catch (Exception e) {
            log.warn("删除 Checkpoint 失败: {}", e.getMessage());
        }
    }

    /**
     * 列出所有可恢复的 Checkpoint
     */
    public static List<CheckpointInfo> listAvailable() {
        List<CheckpointInfo> checkpoints = new ArrayList<>();
        try {
            Path checkpointDir = getCheckpointDir();
            if (!Files.exists(checkpointDir)) {
                return checkpoints;
            }

            try (var paths = Files.list(checkpointDir)) {
                paths.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            AgentCheckpoint cp = mapper.readValue(p.toFile(), AgentCheckpoint.class);
                            checkpoints.add(new CheckpointInfo(
                                cp.orchestrationId,
                                cp.goal,
                                cp.completedSteps.size(),
                                cp.failedSteps,
                                Instant.ofEpochMilli(cp.timestamp)
                            ));
                        } catch (Exception e) {
                            log.warn("读取 Checkpoint 失败: {}", p.getFileName());
                        }
                    });
            }
        } catch (Exception e) {
            log.error("列出 Checkpoint 失败: {}", e.getMessage());
        }
        return checkpoints;
    }

    public record CheckpointInfo(
        String orchestrationId,
        String goal,
        int completedSteps,
        int failedSteps,
        Instant timestamp
    ) {}

    private static Path getCheckpointDir() {
        // 测试与多实例场景可通过 -Dpaicli.checkpoint.dir 重定向，避免写用户主目录
        String override = System.getProperty("paicli.checkpoint.dir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home");
        return Paths.get(home, ".paicli", "checkpoints");
    }

    // ─────────────────────────────────────────────────────────
    // Getters / Setters
    // ─────────────────────────────────────────────────────────

    public String getOrchestrationId() {
        return orchestrationId;
    }

    public void setOrchestrationId(String orchestrationId) {
        this.orchestrationId = orchestrationId;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public void setCompletedSteps(List<String> completedSteps) {
        this.completedSteps = completedSteps;
    }

    public Map<String, StepArtifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(Map<String, StepArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getFailedSteps() {
        return failedSteps;
    }

    public void setFailedSteps(int failedSteps) {
        this.failedSteps = failedSteps;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
