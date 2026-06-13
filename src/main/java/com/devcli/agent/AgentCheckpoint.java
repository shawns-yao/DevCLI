package com.devcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-Agent orchestration checkpoint for failure recovery.
 *
 * <p>落盘内容分两层：
 * <ul>
 *   <li>计划层：完整任务文本（goal）、解析后的步骤列表（{@link PlanStep}，含依赖关系）
 *       和验收点（{@link CriterionRecord}），在计划解析完成时一次性写入；</li>
 *   <li>进度层：步骤完成/失败即落盘（{@code AgentOrchestrator.updateStep} 触发），
 *       完成步骤保留完整 result（{@value #MAX_SUMMARY_LENGTH} 字符上限）和本步骤实际修改的文件列表。</li>
 * </ul>
 *
 * <p>恢复路径：{@code AgentOrchestrator.resume} 凭计划层重建步骤列表、凭进度层跳过已完成步骤。
 * 全部成功后删除文件；失败/崩溃后文件保留在 {@code ~/.devcli/checkpoints/} 供恢复或排查。
 * 写入采用临时文件 + 原子 move，避免崩溃瞬间留下半截 JSON。
 */
public class AgentCheckpoint {
    private static final Logger log = LoggerFactory.getLogger(AgentCheckpoint.class);
    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    /** 步骤 result 落盘上限：buildStepContext 注入依赖结果最多 800 字符，8KB 足够保真。 */
    public static final int MAX_SUMMARY_LENGTH = 8 * 1024;

    private String orchestrationId;
    private String goal;
    private List<PlanStep> planSteps;
    private List<CriterionRecord> acceptanceCriteria;
    private List<String> completedSteps;
    /** 遗留兼容字段：旧版"平行重规划"会写入被接管步骤 id。在位重做模型下不再写入，仅为反序列化旧 checkpoint 保留。 */
    private List<String> supersededSteps;
    private Map<String, StepArtifact> artifacts;
    /**
     * 失败步骤的产物账本：失败步骤可能已写入文件（副作用不可逆），记录其 modifiedFiles + 失败摘要。
     * resume 后注入对应步骤上下文，让重做的 Worker 知道上次失败已留下哪些文件，不要假设它们不存在。
     */
    private Map<String, StepArtifact> failedArtifacts;
    private long timestamp;
    private int failedSteps;
    private String lastError;

    public record StepArtifact(String stepId, List<String> modifiedFiles, String summary) {}

    /** 计划层步骤快照：恢复时重建 ExecutionStep 所需的全部静态信息。 */
    public record PlanStep(String id, String description, String type, List<String> dependencies) {}

    /** 计划层验收点快照，与 AgentOrchestrator.AcceptanceCriterion 字段一一对应。 */
    public record CriterionRecord(String id, String category, String description, String testSignal) {}

    public AgentCheckpoint() {
        this.completedSteps = new ArrayList<>();
        this.artifacts = new HashMap<>();
        this.failedArtifacts = new HashMap<>();
        this.planSteps = new ArrayList<>();
        this.acceptanceCriteria = new ArrayList<>();
        this.supersededSteps = new ArrayList<>();
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
        if (!completedSteps.contains(stepId)) {
            completedSteps.add(stepId);
        }
        String bounded = summary == null ? "" : (summary.length() > MAX_SUMMARY_LENGTH
                ? summary.substring(0, MAX_SUMMARY_LENGTH) + "...(截断)"
                : summary);
        artifacts.put(stepId, new StepArtifact(stepId,
                modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles), bounded));
        // 重做成功：清理同 step 的旧失败 artifact，避免成功与失败记录并存导致状态不一致
        failedArtifacts.remove(stepId);
        timestamp = System.currentTimeMillis();
    }

    public void recordFailure(String error) {
        this.failedSteps++;
        this.lastError = error;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 记录失败步骤的产物：失败步骤可能已写入文件，保留其 modifiedFiles 供 resume 后对位。
     * 内部已调用 {@link #recordFailure(String)}，调用方不应再单独调用，避免 failedSteps 重复计数。
     */
    public void addFailedStep(String stepId, List<String> modifiedFiles, String summary) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        String bounded = summary == null ? "" : (summary.length() > MAX_SUMMARY_LENGTH
                ? summary.substring(0, MAX_SUMMARY_LENGTH) + "...(截断)"
                : summary);
        failedArtifacts.put(stepId, new StepArtifact(stepId,
                modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles), bounded));
        recordFailure(stepId + ": " + bounded);
    }

    public boolean isStepCompleted(String stepId) {
        return completedSteps.contains(stepId);
    }

    public boolean isStepSuperseded(String stepId) {
        return supersededSteps.contains(stepId);
    }

    public List<String> getCompletedSteps() {
        return new ArrayList<>(completedSteps);
    }

    // ─────────────────────────────────────────────────────────
    // 持久化
    // ─────────────────────────────────────────────────────────

    /**
     * 保存 Checkpoint 到磁盘（临时文件 + 原子 move，崩溃瞬间不会留下半截 JSON）
     */
    public void save() {
        try {
            Path checkpointDir = getCheckpointDir();
            Files.createDirectories(checkpointDir);

            Path checkpointFile = checkpointDir.resolve(orchestrationId + ".json");
            Path tempFile = checkpointDir.resolve(orchestrationId + ".json.tmp");
            mapper.writeValue(tempFile.toFile(), this);
            try {
                Files.move(tempFile, checkpointFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                // 部分文件系统不支持原子 move，退化为普通替换
                Files.move(tempFile, checkpointFile, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Checkpoint 已保存: {} (已完成: {}/{} 步)",
                orchestrationId, completedSteps.size(), planSteps.isEmpty()
                        ? completedSteps.size() + failedSteps : planSteps.size());
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
     * 加载最近一次保存的 Checkpoint；目录为空或全部不可解析时返回 null。
     */
    public static AgentCheckpoint loadLatest() {
        List<CheckpointInfo> available = listAvailable();
        return available.stream()
                .max(Comparator.comparing(CheckpointInfo::timestamp))
                .map(info -> load(info.orchestrationId()))
                .orElse(null);
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
        // 测试与多实例场景可通过 -Ddevcli.checkpoint.dir 重定向，避免写用户主目录
        String override = System.getProperty("devcli.checkpoint.dir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home");
        return Paths.get(home, ".devcli", "checkpoints");
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

    public List<PlanStep> getPlanSteps() {
        return planSteps;
    }

    public void setPlanSteps(List<PlanStep> planSteps) {
        this.planSteps = planSteps == null ? new ArrayList<>() : planSteps;
    }

    public List<CriterionRecord> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public void setAcceptanceCriteria(List<CriterionRecord> acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria == null ? new ArrayList<>() : acceptanceCriteria;
    }

    public void setCompletedSteps(List<String> completedSteps) {
        this.completedSteps = completedSteps;
    }

    public List<String> getSupersededSteps() {
        return supersededSteps;
    }

    public void setSupersededSteps(List<String> supersededSteps) {
        this.supersededSteps = supersededSteps == null ? new ArrayList<>() : supersededSteps;
    }

    public Map<String, StepArtifact> getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(Map<String, StepArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    public Map<String, StepArtifact> getFailedArtifacts() {
        return failedArtifacts;
    }

    public void setFailedArtifacts(Map<String, StepArtifact> failedArtifacts) {
        this.failedArtifacts = failedArtifacts == null ? new HashMap<>() : failedArtifacts;
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
