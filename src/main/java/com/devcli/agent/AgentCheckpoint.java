package com.devcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.devcli.plan.ExecutionArtifact;
import com.devcli.plan.ExecutionGraph;
import com.devcli.workspace.PatchSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

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
    public static final int CURRENT_PROTOCOL_VERSION = 3;

    private int protocolVersion = CURRENT_PROTOCOL_VERSION;
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
    private Map<String, PendingPatchCommit> pendingPatchCommits;
    private long timestamp;
    private int failedSteps;
    private String lastError;

    public record StepArtifact(String stepId, List<String> modifiedFiles, String summary,
                               ExecutionArtifact artifact) {
        public StepArtifact {
            stepId = stepId == null ? "" : stepId;
            modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
            summary = summary == null ? "" : summary;
        }

        public StepArtifact(String stepId, List<String> modifiedFiles, String summary) {
            this(stepId, modifiedFiles, summary, null);
        }

        ExecutionArtifact normalized(ExecutionGraph.NodeState fallbackState) {
            if (artifact != null) {
                return artifact;
            }
            return fallbackState == ExecutionGraph.NodeState.COMPLETED
                    ? ExecutionArtifact.completed(stepId, summary, summary, modifiedFiles)
                    : ExecutionArtifact.failed(stepId, summary, summary, modifiedFiles);
        }
    }

    public record PendingPatchEntry(String relativePath, PatchSet.ChangeType type,
                                    String beforeHash, String afterHash,
                                    boolean backupPresent) {
    }

    public record PendingPatchCommit(String stepId, List<PendingPatchEntry> entries,
                                     ExecutionArtifact intendedArtifact) {
        public PendingPatchCommit {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public enum PatchReconcileAction {
        PROMOTED_COMPLETED,
        CONTINUE_PENDING,
        ROLLED_BACK,
        ROLLBACK_FAILED
    }

    public record PatchReconcileResult(Map<String, PatchReconcileAction> actions,
                                       Map<String, List<String>> failures) {
        public PatchReconcileResult {
            actions = actions == null ? Map.of() : Map.copyOf(actions);
            failures = failures == null ? Map.of() : Map.copyOf(failures);
        }
    }

    public record RecoveryState(int protocolVersion, String orchestrationId, String goal,
                                List<PlanStep> planSteps,
                                List<CriterionRecord> acceptanceCriteria,
                                Map<String, ExecutionArtifact> artifacts) {
        public RecoveryState {
            planSteps = planSteps == null ? List.of() : List.copyOf(planSteps);
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        }
    }

    /** 计划层步骤快照：恢复时重建 ExecutionStep 所需的全部静态信息。 */
    public record PlanStep(String id, String description, String type, List<String> dependencies) {}

    /** 计划层验收点快照，与 AgentOrchestrator.AcceptanceCriterion 字段一一对应。 */
    public record CriterionRecord(String id, String category, String description, String testSignal, String severity) {}

    public AgentCheckpoint() {
        this.completedSteps = new ArrayList<>();
        this.artifacts = new HashMap<>();
        this.failedArtifacts = new HashMap<>();
        this.pendingPatchCommits = new HashMap<>();
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
        List<String> resources = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        artifacts.put(stepId, new StepArtifact(stepId, resources, bounded,
                ExecutionArtifact.completed(stepId, bounded, bounded, resources)));
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
        List<String> resources = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        failedArtifacts.put(stepId, new StepArtifact(stepId, resources, bounded,
                ExecutionArtifact.failed(stepId, bounded, bounded, resources)));
        recordFailure(stepId + ": " + bounded);
    }

    public synchronized void preparePatchCommit(String stepId, Path projectRoot,
                                                PatchSet patchSet,
                                                ExecutionArtifact intendedArtifact) throws IOException {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId is required");
        }
        Path root = normalizeProjectRoot(projectRoot);
        Path journal = patchJournalDir(stepId);
        deleteTree(journal);
        Files.createDirectories(journal);

        List<PendingPatchEntry> entries = new ArrayList<>();
        try {
            for (PatchSet.FileChange change : patchSet.changes()) {
                Path target = resolveSafe(root, change.relativePath());
                String currentHash = currentHash(target);
                if (!currentHash.equals(change.beforeHash())) {
                    throw new IOException("PatchSet 写前日志前置版本冲突: " + change.relativePath());
                }
                boolean backupPresent = !PatchSet.isMissingHash(change.beforeHash());
                if (backupPresent) {
                    Path backup = resolveSafe(journal, change.relativePath());
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                entries.add(new PendingPatchEntry(
                        change.relativePath(), change.type(), change.beforeHash(),
                        change.afterHash(), backupPresent));
            }
            ExecutionArtifact intended = intendedArtifact == null
                    ? ExecutionArtifact.pending(stepId)
                    : intendedArtifact.withModifiedResources(
                    entries.stream().map(PendingPatchEntry::relativePath).toList());
            pendingPatchCommits().put(stepId,
                    new PendingPatchCommit(stepId, entries, intended));
            timestamp = System.currentTimeMillis();
            saveOrThrow();
        } catch (Exception e) {
            pendingPatchCommits().remove(stepId);
            deleteTree(journal);
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("准备 PatchSet 写前日志失败: " + e.getMessage(), e);
        }
    }

    public synchronized void markPatchCommitTerminal(String stepId) {
        pendingPatchCommits().remove(stepId);
    }

    public synchronized void cleanupPatchJournal(String stepId) {
        try {
            deleteTree(patchJournalDir(stepId));
        } catch (IOException e) {
            log.warn("清理 PatchSet 写前日志失败: step={}, error={}", stepId, e.getMessage());
        }
    }

    public synchronized PatchReconcileResult reconcilePendingPatchCommits(Path projectRoot) throws IOException {
        Map<String, PatchReconcileAction> actions = new LinkedHashMap<>();
        Map<String, List<String>> failures = new LinkedHashMap<>();
        if (pendingPatchCommits().isEmpty()) {
            return new PatchReconcileResult(actions, failures);
        }

        Path root = normalizeProjectRoot(projectRoot);
        List<String> completedJournals = new ArrayList<>();
        for (PendingPatchCommit pending : new ArrayList<>(pendingPatchCommits().values())) {
            boolean allBefore = true;
            boolean allAfter = true;
            for (PendingPatchEntry entry : pending.entries()) {
                String current = currentHash(resolveSafe(root, entry.relativePath()));
                allBefore &= current.equals(entry.beforeHash());
                allAfter &= current.equals(entry.afterHash());
            }

            if (allAfter) {
                promoteCompletedArtifact(pending);
                pendingPatchCommits().remove(pending.stepId());
                completedJournals.add(pending.stepId());
                actions.put(pending.stepId(), PatchReconcileAction.PROMOTED_COMPLETED);
                continue;
            }
            if (allBefore) {
                pendingPatchCommits().remove(pending.stepId());
                completedJournals.add(pending.stepId());
                actions.put(pending.stepId(), PatchReconcileAction.CONTINUE_PENDING);
                continue;
            }

            List<String> rollbackFailures = rollbackPendingPatch(root, pending);
            if (rollbackFailures.isEmpty()) {
                pendingPatchCommits().remove(pending.stepId());
                completedJournals.add(pending.stepId());
                actions.put(pending.stepId(), PatchReconcileAction.ROLLED_BACK);
            } else {
                actions.put(pending.stepId(), PatchReconcileAction.ROLLBACK_FAILED);
                failures.put(pending.stepId(), rollbackFailures);
            }
        }

        timestamp = System.currentTimeMillis();
        saveOrThrow();
        completedJournals.forEach(this::cleanupPatchJournal);
        return new PatchReconcileResult(actions, failures);
    }

    private List<String> rollbackPendingPatch(Path projectRoot, PendingPatchCommit pending) {
        List<String> failures = new ArrayList<>();
        List<PendingPatchEntry> entries = new ArrayList<>(pending.entries());
        entries.sort(Comparator.comparingInt((PendingPatchEntry entry) ->
                Path.of(entry.relativePath()).getNameCount()).reversed());
        for (PendingPatchEntry entry : entries) {
            Path target = resolveSafe(projectRoot, entry.relativePath());
            try {
                if (entry.backupPresent()) {
                    Path backup = resolveSafe(patchJournalDir(pending.stepId()), entry.relativePath());
                    Files.createDirectories(target.getParent());
                    Path temporary = Files.createTempFile(
                            target.getParent(), ".devcli-recovery-", ".tmp");
                    try {
                        Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
                        try {
                            Files.move(temporary, target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException atomicFailure) {
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                } else {
                    Files.deleteIfExists(target);
                }
                String restored = currentHash(target);
                if (!restored.equals(entry.beforeHash())) {
                    failures.add(entry.relativePath() + ": 回滚后哈希不匹配");
                }
            } catch (Exception e) {
                failures.add(entry.relativePath() + ": "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return failures;
    }

    private void promoteCompletedArtifact(PendingPatchCommit pending) {
        ExecutionArtifact artifact = pending.intendedArtifact() == null
                ? ExecutionArtifact.completed(pending.stepId(), "", "",
                pending.entries().stream().map(PendingPatchEntry::relativePath).toList())
                : pending.intendedArtifact().withState(ExecutionGraph.NodeState.COMPLETED)
                .withModifiedResources(pending.entries().stream()
                        .map(PendingPatchEntry::relativePath).toList());
        if (!completedSteps.contains(pending.stepId())) {
            completedSteps.add(pending.stepId());
        }
        artifacts.put(pending.stepId(), new StepArtifact(
                pending.stepId(), artifact.modifiedResources(), artifact.summary(), artifact));
        failedArtifacts.remove(pending.stepId());
    }

    private Map<String, PendingPatchCommit> pendingPatchCommits() {
        if (pendingPatchCommits == null) {
            pendingPatchCommits = new HashMap<>();
        }
        return pendingPatchCommits;
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

    public RecoveryState recoveryState() {
        Map<String, ExecutionArtifact> normalized = new LinkedHashMap<>();
        Map<String, StepArtifact> completed = artifacts == null ? Map.of() : artifacts;
        Map<String, StepArtifact> failed = failedArtifacts == null ? Map.of() : failedArtifacts;
        completed.forEach((stepId, artifact) -> normalized.put(stepId,
                artifact.normalized(ExecutionGraph.NodeState.COMPLETED)));
        failed.forEach((stepId, artifact) -> normalized.put(stepId,
                artifact.normalized(ExecutionGraph.NodeState.FAILED)));
        return new RecoveryState(
                protocolVersion <= 0 ? 1 : protocolVersion,
                orchestrationId,
                goal,
                planSteps,
                acceptanceCriteria,
                normalized);
    }

    // ─────────────────────────────────────────────────────────
    // 持久化
    // ─────────────────────────────────────────────────────────

    /**
     * 保存 Checkpoint 到磁盘（临时文件 + 原子 move，崩溃瞬间不会留下半截 JSON）
     */
    public void save() {
        try {
            saveOrThrow();
        } catch (Exception e) {
            log.error("保存 Checkpoint 失败: {}", e.getMessage(), e);
        }
    }

    public void saveStrict() throws IOException {
        saveOrThrow();
    }

    private void saveOrThrow() throws IOException {
        Path checkpointDir = getCheckpointDir();
        Files.createDirectories(checkpointDir);

        Path checkpointFile = checkpointDir.resolve(orchestrationId + ".json");
        Path tempFile = checkpointDir.resolve(orchestrationId + ".json.tmp");
        mapper.writeValue(tempFile.toFile(), this);
        try {
            Files.move(tempFile, checkpointFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tempFile, checkpointFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Checkpoint 已保存: {} (已完成: {}/{} 步)",
                orchestrationId, completedSteps.size(), planSteps.isEmpty()
                        ? completedSteps.size() + failedSteps : planSteps.size());
    }

    /**
     * 从磁盘加载 Checkpoint
     */
    public static AgentCheckpoint load(String orchestrationId) {
        return loadResult(orchestrationId).checkpoint();
    }

    public static LoadResult loadResult(String orchestrationId) {
        try {
            Path checkpointFile = getCheckpointDir().resolve(orchestrationId + ".json");
            if (!Files.exists(checkpointFile)) {
                log.warn("Checkpoint 不存在: {}", orchestrationId);
                return new LoadResult(LoadStatus.NOT_FOUND, null, "checkpoint 不存在");
            }

            AgentCheckpoint checkpoint = mapper.readValue(checkpointFile.toFile(), AgentCheckpoint.class);
            int loadedVersion = checkpoint.protocolVersion <= 0 ? 1 : checkpoint.protocolVersion;
            if (loadedVersion > CURRENT_PROTOCOL_VERSION) {
                String message = "checkpoint 协议版本不兼容：文件版本 " + loadedVersion
                        + "，当前版本 " + CURRENT_PROTOCOL_VERSION;
                log.error("拒绝加载未来版本 Checkpoint: {} (文件版本: {}, 当前版本: {})",
                        orchestrationId, loadedVersion, CURRENT_PROTOCOL_VERSION);
                return new LoadResult(LoadStatus.INCOMPATIBLE, null, message);
            }
            checkpoint.normalizeState();
            log.info("Checkpoint 已加载: {} (协议版本: {}, 已完成: {} 步)",
                orchestrationId, loadedVersion, checkpoint.completedSteps.size());
            return new LoadResult(LoadStatus.LOADED, checkpoint, "");
        } catch (Exception e) {
            log.error("加载 Checkpoint 失败: {}", e.getMessage(), e);
            return new LoadResult(LoadStatus.INVALID, null,
                    e.getMessage() == null ? "checkpoint 无法解析" : e.getMessage());
        }
    }

    /**
     * 加载最近一次保存的 Checkpoint；目录为空或全部不可解析时返回 null。
     */
    public static AgentCheckpoint loadLatest() {
        return loadLatestResult().checkpoint();
    }

    public static LoadResult loadLatestResult() {
        List<CheckpointInfo> available = listAvailable();
        return available.stream()
                .max(Comparator.comparing(CheckpointInfo::timestamp))
                .map(info -> loadResult(info.orchestrationId()))
                .orElseGet(() -> new LoadResult(
                        LoadStatus.NOT_FOUND, null, "没有可恢复的 checkpoint"));
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
            deleteTree(patchJournalRoot());
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

    public enum LoadStatus {
        LOADED,
        NOT_FOUND,
        INCOMPATIBLE,
        INVALID
    }

    public record LoadResult(LoadStatus status, AgentCheckpoint checkpoint, String message) {
        public LoadResult {
            status = status == null ? LoadStatus.INVALID : status;
            message = message == null ? "" : message;
        }
    }

    private void normalizeState() {
        if (completedSteps == null) completedSteps = new ArrayList<>();
        if (supersededSteps == null) supersededSteps = new ArrayList<>();
        if (artifacts == null) artifacts = new HashMap<>();
        if (failedArtifacts == null) failedArtifacts = new HashMap<>();
        if (pendingPatchCommits == null) pendingPatchCommits = new HashMap<>();
        if (planSteps == null) planSteps = new ArrayList<>();
        if (acceptanceCriteria == null) acceptanceCriteria = new ArrayList<>();
    }

    private Path patchJournalRoot() {
        String safeId = orchestrationId == null || orchestrationId.isBlank()
                ? "checkpoint"
                : orchestrationId.replaceAll("[^a-zA-Z0-9._-]", "-");
        return getCheckpointDir().resolve(safeId + ".patch-journal");
    }

    private Path patchJournalDir(String stepId) {
        String safeStep = stepId == null || stepId.isBlank()
                ? "step"
                : stepId.replaceAll("[^a-zA-Z0-9._-]", "-");
        Path root = patchJournalRoot().normalize();
        Path journal = root.resolve("step-" + safeStep).normalize();
        if (journal.equals(root) || !journal.startsWith(root)) {
            throw new IllegalArgumentException("invalid patch journal step id");
        }
        return journal;
    }

    private static Path normalizeProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("projectRoot is required");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static Path resolveSafe(Path root, String relativePath) {
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("patch journal path must be relative: " + relativePath);
        }
        Path resolved = root.resolve(relative).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IllegalArgumentException("patch journal path escapes root: " + relativePath);
        }
        return resolved;
    }

    private static String currentHash(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return "<missing>";
            }
            return PatchSet.hash(Files.readAllBytes(path));
        } catch (IOException e) {
            return "<unreadable>";
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

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

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

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

    public Map<String, PendingPatchCommit> getPendingPatchCommits() {
        return pendingPatchCommits();
    }

    public void setPendingPatchCommits(Map<String, PendingPatchCommit> pendingPatchCommits) {
        this.pendingPatchCommits = pendingPatchCommits == null ? new HashMap<>() : pendingPatchCommits;
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
