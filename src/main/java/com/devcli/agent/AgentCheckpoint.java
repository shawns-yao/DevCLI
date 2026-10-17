package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;
import com.devcli.plan.ExecutionGraph;
import com.devcli.workspace.FileModeSnapshot;
import com.devcli.workspace.PatchSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

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
    /** 步骤 result 落盘上限：buildStepContext 注入依赖结果最多 800 字符，8KB 足够保真。 */
    public static final int MAX_SUMMARY_LENGTH = 8 * 1024;
    public static final int CURRENT_PROTOCOL_VERSION = 10;
    private static final int MAX_AGENT_SUMMARY_LENGTH = 2 * 1024;
    private static final int MAX_ATTEMPT_DIGESTS = 32;
    private static final int MAX_ATTEMPT_DIGEST_LENGTH = 1_024;

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
    /** 在位重做的累计次数，跨进程恢复后继续沿用，避免恢复重新获得一次重做额度。 */
    private Map<String, Integer> redoCounts;
    /** 每次在位重做的失败现场摘要，用于恢复、审计和避免把重做次数与失败产物混为一谈。 */
    private List<RedoAttemptRecord> redoAttempts;
    /** 有界、按步骤归属的失败尝试摘要，恢复后用于避免重复已排除方案。 */
    private List<AttemptDigestRecord> attemptDigests;
    /** 已批准且尚未形成成功或失败终态的重做步骤，用于区分中途崩溃与额度耗尽。 */
    private Set<String> redoPendingSteps;
    private Map<String, PendingPatchCommit> pendingPatchCommits;
    /** 硬验证环境故障时保留的未验证补丁；内容存放在 checkpoint 私有旁路目录。 */
    private Map<String, DeferredPatch> deferredPatches;
    private List<AgentIdentityRecord> agentIdentities;
    private Map<String, AgentCursorRecord> agentCursors;
    private Map<String, StepAssignmentRecord> stepAssignments;
    private long messageSequence;
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

    public record RedoAttemptRecord(String stepId, int attempt, String failureReason,
                                    List<String> modifiedFiles, long recordedAt) {
        public RedoAttemptRecord {
            stepId = stepId == null ? "" : stepId.trim();
            attempt = Math.max(1, attempt);
            failureReason = boundStepSummary(failureReason);
            modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
            recordedAt = recordedAt <= 0 ? System.currentTimeMillis() : recordedAt;
        }
    }

    public record AttemptDigestRecord(String stepId, String digest,
                                      String reference, long sequence) {
        public AttemptDigestRecord {
            stepId = stepId == null ? "" : stepId.trim();
            digest = digest == null ? "" : digest.trim();
            if (digest.length() > MAX_ATTEMPT_DIGEST_LENGTH) {
                digest = digest.substring(0, MAX_ATTEMPT_DIGEST_LENGTH) + "...(截断)";
            }
            reference = reference == null ? "" : reference.trim();
            sequence = Math.max(0, sequence);
        }
    }

    public record PendingPatchEntry(String relativePath, PatchSet.ChangeType type,
                                    String beforeHash, String afterHash,
                                    boolean backupPresent,
                                    FileModeSnapshot beforeMode,
                                    FileModeSnapshot afterMode) {
        public PendingPatchEntry(String relativePath, PatchSet.ChangeType type,
                                 String beforeHash, String afterHash,
                                 boolean backupPresent) {
            this(relativePath, type, beforeHash, afterHash, backupPresent, null, null);
        }
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
                                Map<String, ExecutionArtifact> artifacts,
                                List<AgentIdentityRecord> agentIdentities,
                                Map<String, AgentCursorRecord> agentCursors,
                                Map<String, StepAssignmentRecord> stepAssignments,
                                long messageSequence,
                                Map<String, Integer> redoCounts,
                                List<RedoAttemptRecord> redoAttempts,
                                Set<String> redoPendingSteps,
                                List<AttemptDigestRecord> attemptDigests,
                                Set<String> deferredPatchSteps) {
        public RecoveryState {
            planSteps = planSteps == null ? List.of() : List.copyOf(planSteps);
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
            artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
            agentIdentities = agentIdentities == null ? List.of() : List.copyOf(agentIdentities);
            agentCursors = agentCursors == null ? Map.of() : Map.copyOf(agentCursors);
            stepAssignments = stepAssignments == null ? Map.of() : Map.copyOf(stepAssignments);
            redoCounts = redoCounts == null ? Map.of() : Map.copyOf(redoCounts);
            redoAttempts = redoAttempts == null ? List.of() : List.copyOf(redoAttempts);
            redoPendingSteps = redoPendingSteps == null ? Set.of() : Set.copyOf(redoPendingSteps);
            attemptDigests = attemptDigests == null ? List.of() : List.copyOf(attemptDigests);
            deferredPatchSteps = deferredPatchSteps == null
                    ? Set.of() : Set.copyOf(deferredPatchSteps);
        }
    }

    public record DeferredPatchEntry(String relativePath, PatchSet.ChangeType type,
                                     String beforeHash, String afterHash,
                                     FileModeSnapshot beforeMode,
                                     FileModeSnapshot afterMode) {
    }

    public record DeferredPatch(String stepId, String storageKey,
                                List<DeferredPatchEntry> entries,
                                String failureReason, long recordedAt) {
        public DeferredPatch {
            stepId = stepId == null ? "" : stepId;
            storageKey = storageKey == null ? "" : storageKey;
            entries = entries == null ? List.of() : List.copyOf(entries);
            failureReason = failureReason == null ? "" : failureReason;
            recordedAt = recordedAt <= 0 ? System.currentTimeMillis() : recordedAt;
        }
    }

    public record AgentIdentityRecord(String agentId, String role, String displayName,
                                      int contextSchemaVersion, long createdAt, long updatedAt) {
        public AgentIdentityRecord {
            agentId = normalizeRequired(agentId, "agentId");
            role = normalizeRequired(role, "role");
            displayName = displayName == null || displayName.isBlank() ? agentId : displayName.trim();
            contextSchemaVersion = Math.max(1, contextSchemaVersion);
            createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
            updatedAt = updatedAt <= 0 ? createdAt : updatedAt;
        }
    }

    public record AgentCursorRecord(String agentId, long lastMessageSeq, String summary,
                                    String lastStepId, long updatedAt) {
        public AgentCursorRecord {
            agentId = normalizeRequired(agentId, "agentId");
            lastMessageSeq = Math.max(0, lastMessageSeq);
            summary = boundAgentSummary(summary);
            lastStepId = lastStepId == null ? "" : lastStepId.trim();
            updatedAt = updatedAt <= 0 ? System.currentTimeMillis() : updatedAt;
        }
    }

    public record StepAssignmentRecord(String stepId, String workerAgentId, String reviewerAgentId) {
        public StepAssignmentRecord {
            stepId = normalizeRequired(stepId, "stepId");
            workerAgentId = normalizeRequired(workerAgentId, "workerAgentId");
            reviewerAgentId = normalizeRequired(reviewerAgentId, "reviewerAgentId");
        }
    }

    /** 计划层步骤快照：恢复时重建 ExecutionStep 所需的全部静态信息。 */
    public record PlanStep(String id, String description, String type, List<String> dependencies) {}

    /** 计划层验收点快照，与 AcceptanceCriterion 字段一一对应。 */
    public record CriterionRecord(
            String id,
            String category,
            String description,
            String testSignal,
            String severity,
            String verificationMethod,
            String verifier,
            List<String> appliesTo
    ) {
        public CriterionRecord {
            id = id == null ? "" : id;
            category = category == null ? "" : category;
            description = description == null ? "" : description;
            testSignal = testSignal == null ? "" : testSignal;
            severity = severity == null ? "" : severity;
            verificationMethod = verificationMethod == null ? "" : verificationMethod;
            verifier = verifier == null ? "" : verifier;
            appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
        }

        public CriterionRecord(String id, String category, String description,
                               String testSignal, String severity) {
            this(id, category, description, testSignal, severity, "", "", List.of());
        }

        public CriterionRecord(String id, String category, String description,
                               String testSignal, String severity,
                               String verificationMethod, String verifier) {
            this(id, category, description, testSignal, severity,
                    verificationMethod, verifier, List.of("FINAL"));
        }
    }

    public AgentCheckpoint() {
        PatchJournalPolicy.maintain(getCheckpointDir());
        this.completedSteps = new ArrayList<>();
        this.artifacts = new HashMap<>();
        this.failedArtifacts = new HashMap<>();
        this.redoCounts = new HashMap<>();
        this.redoAttempts = new ArrayList<>();
        this.attemptDigests = new ArrayList<>();
        this.redoPendingSteps = new HashSet<>();
        this.pendingPatchCommits = new HashMap<>();
        this.deferredPatches = new HashMap<>();
        this.planSteps = new ArrayList<>();
        this.acceptanceCriteria = new ArrayList<>();
        this.supersededSteps = new ArrayList<>();
        this.agentIdentities = new ArrayList<>();
        this.agentCursors = new HashMap<>();
        this.stepAssignments = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public AgentCheckpoint(String orchestrationId, String goal) {
        this();
        this.orchestrationId = orchestrationId;
        this.goal = goal;
    }

    public synchronized void ensureAgentIdentities(List<AgentIdentityRecord> identities) {
        List<AgentIdentityRecord> requested = identities == null ? List.of() : List.copyOf(identities);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("agent identities are required");
        }
        if (agentIdentities == null || agentIdentities.isEmpty()) {
            agentIdentities = new ArrayList<>(requested);
            agentCursors = agentCursors == null ? new HashMap<>() : agentCursors;
            long now = System.currentTimeMillis();
            for (AgentIdentityRecord identity : requested) {
                agentCursors.putIfAbsent(identity.agentId(),
                        new AgentCursorRecord(identity.agentId(), 0, "", "", now));
            }
            protocolVersion = CURRENT_PROTOCOL_VERSION;
            validateAgentState();
            return;
        }
        Set<String> existingIds = agentIdentities.stream()
                .map(AgentIdentityRecord::agentId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> requestedIds = requested.stream()
                .map(AgentIdentityRecord::agentId)
                .collect(java.util.stream.Collectors.toSet());
        if (!existingIds.equals(requestedIds)) {
            throw new IllegalStateException("checkpoint agent topology does not match runtime topology");
        }
        validateAgentState();
    }

    public synchronized StepAssignmentRecord assignStep(String stepId, String workerAgentId,
                                                        String reviewerAgentId) {
        StepAssignmentRecord requested = new StepAssignmentRecord(stepId, workerAgentId, reviewerAgentId);
        StepAssignmentRecord existing = stepAssignments().get(requested.stepId());
        if (existing != null && !existing.equals(requested)) {
            throw new IllegalStateException("step " + stepId + " already assigned to "
                    + existing.workerAgentId());
        }
        stepAssignments().putIfAbsent(requested.stepId(), requested);
        validateAgentState();
        return stepAssignments().get(requested.stepId());
    }

    public synchronized boolean advanceAgentCursor(String agentId, String stepId, String summary) {
        String normalizedAgentId = normalizeRequired(agentId, "agentId");
        String normalizedStepId = stepId == null ? "" : stepId.trim();
        String boundedSummary = boundAgentSummary(summary);
        boolean knownAgent = agentIdentities != null && agentIdentities.stream()
                .anyMatch(identity -> identity.agentId().equals(normalizedAgentId));
        if (!knownAgent) {
            throw new IllegalArgumentException("unknown agent identity: " + normalizedAgentId);
        }
        AgentCursorRecord current = agentCursors().get(normalizedAgentId);
        if (current != null
                && current.lastStepId().equals(normalizedStepId)
                && current.summary().equals(boundedSummary)) {
            return false;
        }
        messageSequence++;
        agentCursors().put(normalizedAgentId, new AgentCursorRecord(
                normalizedAgentId, messageSequence, boundedSummary,
                normalizedStepId, System.currentTimeMillis()));
        validateAgentState();
        return true;
    }

    static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String boundAgentSummary(String summary) {
        String normalized = summary == null ? "" : summary.trim();
        return normalized.length() <= MAX_AGENT_SUMMARY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_AGENT_SUMMARY_LENGTH) + "...(截断)";
    }

    // ─────────────────────────────────────────────────────────
    // Checkpoint 操作
    // ─────────────────────────────────────────────────────────

    public synchronized void addCompletedStep(String stepId, List<String> modifiedFiles, String summary) {
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
        redoPendingSteps().remove(stepId);
        timestamp = System.currentTimeMillis();
    }

    public synchronized void recordFailure(String error) {
        this.failedSteps++;
        this.lastError = error;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 记录失败步骤的产物：失败步骤可能已写入文件，保留其 modifiedFiles 供 resume 后对位。
     * 内部已调用 {@link #recordFailure(String)}，调用方不应再单独调用，避免 failedSteps 重复计数。
     */
    public synchronized void addFailedStep(String stepId, List<String> modifiedFiles, String summary) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        String bounded = summary == null ? "" : (summary.length() > MAX_SUMMARY_LENGTH
                ? summary.substring(0, MAX_SUMMARY_LENGTH) + "...(截断)"
                : summary);
        List<String> resources = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        failedArtifacts.put(stepId, new StepArtifact(stepId, resources, bounded,
                ExecutionArtifact.failed(stepId, bounded, bounded, resources)));
        redoPendingSteps().remove(stepId);
        recordFailure(stepId + ": " + bounded);
    }

    public synchronized void recordRedoAttempt(String stepId, int attempt,
                                                String failureReason, List<String> modifiedFiles) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        String normalizedStepId = stepId.trim();
        int normalizedAttempt = Math.max(1, attempt);
        redoCounts().merge(normalizedStepId, normalizedAttempt, Math::max);
        redoAttempts().add(new RedoAttemptRecord(
                normalizedStepId, normalizedAttempt, failureReason, modifiedFiles,
                System.currentTimeMillis()));
        redoPendingSteps().add(normalizedStepId);
        timestamp = System.currentTimeMillis();
    }

    public synchronized void recordAttemptDigests(List<AttemptDigestRecord> records) {
        if (records == null || records.isEmpty()) return;
        LinkedHashMap<String, AttemptDigestRecord> merged = new LinkedHashMap<>();
        for (AttemptDigestRecord existing : attemptDigests()) {
            merged.put(attemptKey(existing), existing);
        }
        for (AttemptDigestRecord record : records) {
            if (record == null || record.digest().isBlank()) continue;
            merged.remove(attemptKey(record));
            merged.put(attemptKey(record), record);
        }
        while (merged.size() > MAX_ATTEMPT_DIGESTS) {
            merged.remove(merged.keySet().iterator().next());
        }
        attemptDigests = new ArrayList<>(merged.values());
        timestamp = System.currentTimeMillis();
    }

    private static String attemptKey(AttemptDigestRecord record) {
        return record.reference().isBlank()
                ? record.stepId() + "#" + record.sequence() + "#" + record.digest().hashCode()
                : record.reference();
    }

    private synchronized List<AttemptDigestRecord> attemptDigests() {
        if (attemptDigests == null) attemptDigests = new ArrayList<>();
        return attemptDigests;
    }


    public synchronized void markPatchCommitTerminal(String stepId) {
        pendingPatchCommits().remove(stepId);
    }



    public synchronized boolean hasDeferredPatch(String stepId) {
        return stepId != null && deferredPatches().containsKey(stepId);
    }

    synchronized DeferredPatch markDeferredPatchTerminal(String stepId) {
        return stepId == null ? null : deferredPatches().remove(stepId);
    }

    synchronized void restoreDeferredPatchMetadata(DeferredPatch deferredPatch) {
        if (deferredPatch != null && !deferredPatch.stepId().isBlank()) {
            deferredPatches().put(deferredPatch.stepId(), deferredPatch);
        }
    }






    synchronized void promoteCompletedArtifact(PendingPatchCommit pending) {
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

    synchronized Map<String, PendingPatchCommit> pendingPatchCommits() {
        if (pendingPatchCommits == null) {
            pendingPatchCommits = new HashMap<>();
        }
        return pendingPatchCommits;
    }

    synchronized Map<String, DeferredPatch> deferredPatches() {
        if (deferredPatches == null) {
            deferredPatches = new HashMap<>();
        }
        return deferredPatches;
    }

    private synchronized Map<String, AgentCursorRecord> agentCursors() {
        if (agentCursors == null) {
            agentCursors = new HashMap<>();
        }
        return agentCursors;
    }

    private synchronized Map<String, StepAssignmentRecord> stepAssignments() {
        if (stepAssignments == null) {
            stepAssignments = new HashMap<>();
        }
        return stepAssignments;
    }

    synchronized void validateAgentState() {
        List<AgentIdentityRecord> identities = agentIdentities == null ? List.of() : agentIdentities;
        if (identities.isEmpty() && agentCursors().isEmpty() && stepAssignments().isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        Map<String, String> roles = new HashMap<>();
        for (AgentIdentityRecord identity : identities) {
            if (!ids.add(identity.agentId())) {
                throw new IllegalArgumentException("duplicate agent identity: " + identity.agentId());
            }
            roles.put(identity.agentId(), identity.role());
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("agent identities are required when cursor or assignment state exists");
        }
        long maxSequence = 0;
        for (Map.Entry<String, AgentCursorRecord> entry : agentCursors().entrySet()) {
            AgentCursorRecord cursor = entry.getValue();
            if (cursor == null || !entry.getKey().equals(cursor.agentId()) || !ids.contains(cursor.agentId())) {
                throw new IllegalArgumentException("invalid agent cursor: " + entry.getKey());
            }
            maxSequence = Math.max(maxSequence, cursor.lastMessageSeq());
        }
        messageSequence = Math.max(messageSequence, maxSequence);
        for (Map.Entry<String, StepAssignmentRecord> entry : stepAssignments().entrySet()) {
            StepAssignmentRecord assignment = entry.getValue();
            if (assignment == null || !entry.getKey().equals(assignment.stepId())) {
                throw new IllegalArgumentException("invalid step assignment: " + entry.getKey());
            }
            if (!"WORKER".equalsIgnoreCase(roles.get(assignment.workerAgentId()))) {
                throw new IllegalArgumentException("step assignment references invalid worker: "
                        + assignment.workerAgentId());
            }
            if (!"REVIEWER".equalsIgnoreCase(roles.get(assignment.reviewerAgentId()))) {
                throw new IllegalArgumentException("step assignment references invalid reviewer: "
                        + assignment.reviewerAgentId());
            }
        }
    }

    public synchronized boolean isStepCompleted(String stepId) {
        return completedSteps.contains(stepId);
    }

    public synchronized boolean isStepSuperseded(String stepId) {
        return supersededSteps.contains(stepId);
    }

    public synchronized List<String> getCompletedSteps() {
        return new ArrayList<>(completedSteps);
    }

    public synchronized RecoveryState recoveryState() {
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
                normalized,
                agentIdentities,
                agentCursors,
                stepAssignments,
                messageSequence,
                redoCounts(),
                redoAttempts(),
                redoPendingSteps(),
                attemptDigests(),
                Set.copyOf(deferredPatches().keySet()));
    }

    // ─────────────────────────────────────────────────────────
    // 持久化
    // ─────────────────────────────────────────────────────────

    /**
     * 保存 Checkpoint 到磁盘（临时文件 + 原子 move，崩溃瞬间不会留下半截 JSON）
     */
    public synchronized void save() {
        try {
            CheckpointFiles.save(this);
        } catch (Exception e) {
            log.error("保存 Checkpoint 失败: {}", e.getMessage(), e);
        }
    }

    public synchronized void saveStrict() throws IOException {
        CheckpointFiles.save(this);
    }


    /**
     * 从磁盘加载 Checkpoint
     */
    public static AgentCheckpoint load(String orchestrationId) {
        return loadResult(orchestrationId).checkpoint();
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
    public synchronized void delete() {
        try {
            Path checkpointFile = getCheckpointDir().resolve(orchestrationId + ".json");
            if (Files.exists(checkpointFile)) {
                Files.delete(checkpointFile);
                log.info("Checkpoint 已删除: {}", orchestrationId);
            }
            CheckpointPatchJournal.deleteTree(new CheckpointPatchJournal(this).patchJournalRoot());
        } catch (Exception e) {
            log.warn("删除 Checkpoint 失败: {}", e.getMessage());
        }
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

    synchronized void normalizeState() {
        if (completedSteps == null) completedSteps = new ArrayList<>();
        if (supersededSteps == null) supersededSteps = new ArrayList<>();
        if (artifacts == null) artifacts = new HashMap<>();
        if (failedArtifacts == null) failedArtifacts = new HashMap<>();
        if (redoCounts == null) redoCounts = new HashMap<>();
        if (redoAttempts == null) redoAttempts = new ArrayList<>();
        if (redoPendingSteps == null) redoPendingSteps = new HashSet<>();
        if (pendingPatchCommits == null) pendingPatchCommits = new HashMap<>();
        if (deferredPatches == null) deferredPatches = new HashMap<>();
        if (planSteps == null) planSteps = new ArrayList<>();
        if (acceptanceCriteria == null) acceptanceCriteria = new ArrayList<>();
        if (agentIdentities == null) agentIdentities = new ArrayList<>();
        if (agentCursors == null) agentCursors = new HashMap<>();
        if (stepAssignments == null) stepAssignments = new HashMap<>();
        validateAgentState();
    }








    static Path getCheckpointDir() {
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

    public synchronized int getProtocolVersion() {
        return protocolVersion;
    }

    public synchronized void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public synchronized String getOrchestrationId() {
        return orchestrationId;
    }

    public synchronized void setOrchestrationId(String orchestrationId) {
        this.orchestrationId = orchestrationId;
    }

    public synchronized String getGoal() {
        return goal;
    }

    public synchronized void setGoal(String goal) {
        this.goal = goal;
    }

    public synchronized List<PlanStep> getPlanSteps() {
        return planSteps;
    }

    public synchronized void setPlanSteps(List<PlanStep> planSteps) {
        this.planSteps = planSteps == null ? new ArrayList<>() : planSteps;
    }

    public synchronized List<CriterionRecord> getAcceptanceCriteria() {
        return acceptanceCriteria;
    }

    public synchronized void setAcceptanceCriteria(List<CriterionRecord> acceptanceCriteria) {
        this.acceptanceCriteria = acceptanceCriteria == null ? new ArrayList<>() : acceptanceCriteria;
    }

    public synchronized void setCompletedSteps(List<String> completedSteps) {
        this.completedSteps = completedSteps;
    }

    public synchronized List<String> getSupersededSteps() {
        return supersededSteps;
    }

    public synchronized void setSupersededSteps(List<String> supersededSteps) {
        this.supersededSteps = supersededSteps == null ? new ArrayList<>() : supersededSteps;
    }

    public synchronized Map<String, StepArtifact> getArtifacts() {
        return artifacts;
    }

    public synchronized void setArtifacts(Map<String, StepArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    public synchronized Map<String, StepArtifact> getFailedArtifacts() {
        return failedArtifacts;
    }

    public synchronized void setFailedArtifacts(Map<String, StepArtifact> failedArtifacts) {
        this.failedArtifacts = failedArtifacts == null ? new HashMap<>() : failedArtifacts;
    }


    public synchronized Map<String, Integer> getRedoCounts() {
        return Map.copyOf(redoCounts());
    }

    public synchronized void setRedoCounts(Map<String, Integer> redoCounts) {
        this.redoCounts = redoCounts == null ? new HashMap<>() : new HashMap<>(redoCounts);
    }

    public synchronized List<RedoAttemptRecord> getRedoAttempts() {
        return List.copyOf(redoAttempts());
    }

    public synchronized List<AttemptDigestRecord> getAttemptDigests() {
        return List.copyOf(attemptDigests());
    }

    public synchronized void setRedoAttempts(List<RedoAttemptRecord> redoAttempts) {
        this.redoAttempts = redoAttempts == null ? new ArrayList<>() : new ArrayList<>(redoAttempts);
    }

    public synchronized Set<String> getRedoPendingSteps() {
        return Set.copyOf(redoPendingSteps());
    }

    public synchronized void setRedoPendingSteps(Set<String> redoPendingSteps) {
        this.redoPendingSteps = redoPendingSteps == null ? new HashSet<>() : new HashSet<>(redoPendingSteps);
    }

    public synchronized Map<String, PendingPatchCommit> getPendingPatchCommits() {
        return pendingPatchCommits();
    }

    public synchronized void setPendingPatchCommits(Map<String, PendingPatchCommit> pendingPatchCommits) {
        this.pendingPatchCommits = pendingPatchCommits == null ? new HashMap<>() : pendingPatchCommits;
    }

    public synchronized Map<String, DeferredPatch> getDeferredPatches() {
        return Map.copyOf(deferredPatches());
    }

    public synchronized void setDeferredPatches(Map<String, DeferredPatch> deferredPatches) {
        this.deferredPatches = deferredPatches == null
                ? new HashMap<>() : new HashMap<>(deferredPatches);
    }

    public synchronized List<AgentIdentityRecord> getAgentIdentities() {
        return agentIdentities == null ? List.of() : List.copyOf(agentIdentities);
    }

    public synchronized void setAgentIdentities(List<AgentIdentityRecord> agentIdentities) {
        this.agentIdentities = agentIdentities == null ? new ArrayList<>() : new ArrayList<>(agentIdentities);
    }

    public synchronized Map<String, AgentCursorRecord> getAgentCursors() {
        return Map.copyOf(agentCursors());
    }

    public synchronized void setAgentCursors(Map<String, AgentCursorRecord> agentCursors) {
        this.agentCursors = agentCursors == null ? new HashMap<>() : new HashMap<>(agentCursors);
    }

    public synchronized Map<String, StepAssignmentRecord> getStepAssignments() {
        return Map.copyOf(stepAssignments());
    }

    public synchronized void setStepAssignments(Map<String, StepAssignmentRecord> stepAssignments) {
        this.stepAssignments = stepAssignments == null ? new HashMap<>() : new HashMap<>(stepAssignments);
    }

    public synchronized long getMessageSequence() {
        return messageSequence;
    }

    public synchronized void setMessageSequence(long messageSequence) {
        this.messageSequence = Math.max(0, messageSequence);
    }

    public synchronized long getTimestamp() {
        return timestamp;
    }

    public synchronized void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public synchronized int getFailedSteps() {
        return failedSteps;
    }

    public synchronized void setFailedSteps(int failedSteps) {
        this.failedSteps = failedSteps;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized void setLastError(String lastError) {
        this.lastError = lastError;
    }

    private synchronized Map<String, Integer> redoCounts() {
        if (redoCounts == null) {
            redoCounts = new HashMap<>();
        }
        return redoCounts;
    }

    private synchronized List<RedoAttemptRecord> redoAttempts() {
        if (redoAttempts == null) {
            redoAttempts = new ArrayList<>();
        }
        return redoAttempts;
    }

    private synchronized Set<String> redoPendingSteps() {
        if (redoPendingSteps == null) {
            redoPendingSteps = new HashSet<>();
        }
        return redoPendingSteps;
    }

    private static String boundStepSummary(String summary) {
        String normalized = summary == null ? "" : summary;
        return normalized.length() <= MAX_SUMMARY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SUMMARY_LENGTH) + "...(截断)";
    }
    public synchronized void preparePatchCommit(String stepId, Path projectRoot,
                                                PatchSet patchSet,
                                                ExecutionArtifact intendedArtifact) throws IOException {
        new CheckpointPatchJournal(this).preparePatchCommit(stepId, projectRoot, patchSet, intendedArtifact);
    }

    public synchronized void preserveDeferredPatch(String stepId, PatchSet patchSet,
                                                   String failureReason) throws IOException {
        new CheckpointPatchJournal(this).preserveDeferredPatch(stepId, patchSet, failureReason);
    }

    public synchronized PatchSet loadDeferredPatch(String stepId) throws IOException {
        return new CheckpointPatchJournal(this).loadDeferredPatch(stepId);
    }

    synchronized void cleanupDeferredPatchFiles(DeferredPatch deferredPatch) {
        new CheckpointPatchJournal(this).cleanupDeferredPatchFiles(deferredPatch);
    }

    public synchronized void clearDeferredPatch(String stepId) throws IOException {
        new CheckpointPatchJournal(this).clearDeferredPatch(stepId);
    }

    public synchronized void cleanupPatchJournal(String stepId) {
        new CheckpointPatchJournal(this).cleanupPatchJournal(stepId);
    }

    public synchronized PatchReconcileResult reconcilePendingPatchCommits(Path projectRoot) throws IOException {
        return new CheckpointPatchJournal(this).reconcilePendingPatchCommits(projectRoot);
    }

    public static LoadResult loadResult(String orchestrationId) {
        return CheckpointFiles.loadResult(orchestrationId);
    }

    public static List<CheckpointInfo> listAvailable() {
        return CheckpointFiles.listAvailable();
    }
}
