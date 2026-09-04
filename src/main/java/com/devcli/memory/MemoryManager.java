package com.devcli.memory;

import com.devcli.llm.LlmClient;
import com.devcli.context.ContextProfile;
import com.devcli.config.ConfigResolver;
import com.devcli.policy.SensitiveDataRedactor;
import com.devcli.tool.ToolSideChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Memory 管理器 —— Memory 系统的门面类。
 *
 * <p>记忆只分两层：{@link SessionMemory} 保存当前任务的工作状态与工具证据，
 * {@link LongTermMemory} 保存跨会话稳定事实。Conversation History / Summary 属于上下文治理，
 * RuleContext 属于规则系统，不计入记忆层。
 *
 * <p>历史包袱已清理：
 * <ul>
 *   <li>删除 {@code ConversationMemory}（旧短期记忆笔记本，与 conversationHistory 职责重叠）</li>
 *   <li>删除 {@code ContextCompressor.compress()}（压完摘要无人消费的死代码）</li>
 *   <li>删除 {@code compressIfNeeded()}（压缩职责已交给 ConversationHistoryCompactor）</li>
 *   <li>删除 {@code MemoryRetriever.retrieve()}（混合短期+长期检索的死代码，主路径只用 retrieveLongTerm）</li>
 * </ul>
 */
public class MemoryManager implements AutoCloseable {
    /** 预摘要是可选的会话缓存优化，不属于阈值触发的语义压缩本身。 */
    public static final String SESSION_PRE_SUMMARY_ENABLED_PROPERTY =
            "devcli.context.pre-summary.enabled";
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static final int SESSION_PRE_SUMMARY_TOKEN_DELTA = 2_000;
    private static final int SESSION_PRE_SUMMARY_TOOL_CALLS = 4;
    private static final int SESSION_PRE_SUMMARY_LARGE_TOOL_CHARS = 12_000;
    private static final int SESSION_PRE_SUMMARY_MAX_RENDER_CHARS = 64_000;
    private final SessionMemory sessionMemory;
    private final CompactionSummaryCache compactionSummaryCache;
    private final LongTermMemory longTermMemory;
    private final MemoryRetriever retriever;
    private final ExecutorService sessionPreSummaryExecutor;
    private final AtomicLong preSummaryFullCount = new AtomicLong();
    private final AtomicLong preSummaryIncrementalCount = new AtomicLong();
    private final AtomicLong preSummaryFailureCount = new AtomicLong();
    private final AtomicLong sessionEventSequence = new AtomicLong();
    private volatile SessionPreSummaryMetrics lastPreSummaryMetrics = SessionPreSummaryMetrics.empty();
    private final MemoryPromotionQueue memoryPromotionQueue;
    private final ExecutorService memoryPromotionExecutor;
    private volatile MemoryPromotionPipeline memoryPromotionPipeline;
    private static final long MEMORY_CONFIRMATION_TTL_SECONDS = ConfigResolver.longValue(
            "devcli.memory.confirmation.ttl.seconds", "DEVCLI_MEMORY_CONFIRMATION_TTL_SECONDS",
            86_400, 600, 2_592_000);
    private final MemoryConfirmationStore memoryConfirmationStore;
    private LlmClient llmClient;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    /** 当前会话显式忽略记忆 flag。用户说"忘记记忆"/"别管记忆"时设为 true。 */
    private volatile boolean memoryIgnored = false;
    private volatile Supplier<String> ruleContextSupplier = () -> "";
    private volatile java.util.Set<String> activeScopeKeys = java.util.Set.of();

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient      LLM 客户端（v2 不再需要——压缩走 ConversationHistoryCompactor，留参数兼容旧测试）
     * @param shortTermBudget 会话记忆 Prompt 预算
     * @param contextWindow  模型上下文窗口大小
     */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.llmClient = llmClient;
        this.contextProfile = contextProfile;
        this.sessionMemory = new SessionMemory();
        this.compactionSummaryCache = new CompactionSummaryCache();
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.memoryConfirmationStore = new MemoryConfirmationStore(this.longTermMemory.storageDir());
        this.memoryPromotionQueue = new MemoryPromotionQueue(this.longTermMemory.storageDir());
        this.retriever = new MemoryRetriever(this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.sessionPreSummaryExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "devcli-session-pre-summary");
            t.setDaemon(true);
            return t;
        });
        this.memoryPromotionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "devcli-memory-promotion");
            t.setDaemon(true);
            return t;
        });
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        applyContextProfile(ContextProfile.from(llmClient));
    }

    /** 显式注入独立 Curator 客户端；主 Agent 客户端绝不隐式复用。 */
    public void setMemoryCuratorClient(LlmClient curatorClient) {
        memoryPromotionPipeline = curatorClient == null ? null
                : new MemoryPromotionPipeline(memoryPromotionQueue,
                new IsolatedMemoryCurator(curatorClient), longTermMemory,
                entry -> notifyAutoSaved(entry, entry.getMetadata()));
        schedulePendingPromotions();
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setRuleContextSupplier(Supplier<String> ruleContextSupplier) {
        this.ruleContextSupplier = ruleContextSupplier == null ? () -> "" : ruleContextSupplier;
    }

    // ─────────────────────────────────────────────────────────
    // 写入 SessionMemory（不进 LLM messages，作为 system prompt 派生视图）
    // ─────────────────────────────────────────────────────────

    /**
     * 添加用户消息——v2 不再写笔记本，仅添加为 volatile fact 标注「最近一次用户输入」便于
     * LLM 在长会话里识别用户最新请求。Conversation History 由 Agent 直接维护。
     */
    public void addUserMessage(String content) {
        if (content == null || content.isBlank()) return;
        if (hasIgnoreMemoryIntent(content)) {
            memoryIgnored = true;
            // “忽略记忆”是当前任务级开关；不要把触发该开关的消息本身再写入工作记忆。
            return;
        }
        if (memoryIgnored) {
            return;
        }
        // 取首 60 字符做 fact，避免 prompt 膨胀
        String preview = content.length() > 60 ? content.substring(0, 60) + "..." : content;
        sessionMemory.accept(new SessionMemory.KeyEvent(
                "用户最新输入: " + preview, 90, "user", "",
                sessionEventSequence.incrementAndGet()));
        sessionMemory.addProtectedConstraints(CompactionSemanticGuard.extractConstraints(
                List.of(LlmClient.Message.user(content))));
    }

    /**
     * 添加助手回复——v2 不再写笔记本。conversationHistory 已经是真实记录。
     * 保留方法签名是为了兼容 Agent / SubAgent 的调用约定。
     */
    public void addAssistantMessage(String content) {
        // no-op：assistant 内容已在 conversationHistory 里，重复存到会话记忆没有用
    }

    /**
     * 添加工具执行结果到 SessionMemory EvidenceJournal。
     * 注意：完整 result 不再截断到 500 字符；摘要不会保留的精确实体（路径/数字/错误码）
     * 在这里以原文形式保留，作为 system prompt "## 最近工具调用证据" 段注入 LLM。
     */
    public void addToolResult(String toolName, String result) {
        addToolResult(toolName, "", result, List.of());
    }

    /**
     * 带 args 的版本：让 LLM 能识别"刚刚 read_file 读的是哪个路径"。
     */
    public void addToolResult(String toolName, String argsJson, String result) {
        addToolResult(toolName, argsJson, result, List.of());
    }

    public void addToolResult(String toolName, String argsJson, String result,
                              List<ToolSideChannel> sideChannels) {
        if (memoryIgnored || toolName == null || result == null) return;
        sessionMemory.accept(new SessionMemory.ToolResultObserved(
                toolName, argsJson, result, sideChannels, currentAgentId(), currentEvidenceScope(),
                currentEvidenceOrigin(), currentContextEpoch(),
                sessionEventSequence.incrementAndGet()));
        recordCurrentStateInvalidations(toolName, argsJson, result, sideChannels);
    }

    private void recordCurrentStateInvalidations(String toolName, String argsJson, String result,
                                                 List<ToolSideChannel> sideChannels) {
        Map<String, MemoryObservationConflictDetector.Observation> observations = new java.util.LinkedHashMap<>();
        if (sideChannels != null) {
            for (ToolSideChannel sideChannel : sideChannels) {
                if (sideChannel instanceof CurrentStateObservationSideChannel current) {
                    MemoryObservationConflictDetector.Observation observation =
                            MemoryObservationConflictDetector.fromSideChannel(current);
                    observations.put(observation.subject(), observation);
                }
            }
        }
        MemoryObservationConflictDetector.observe(toolName, argsJson, result)
                .ifPresent(observation -> observations.putIfAbsent(observation.subject(), observation));
        for (MemoryObservationConflictDetector.Observation observation : observations.values()) {
            recordCurrentStateInvalidation(toolName, observation);
        }
    }

    private void recordCurrentStateInvalidation(
            String toolName, MemoryObservationConflictDetector.Observation observation) {
        RuleCurrentStateConflictDetector.detect(ruleContextSupplier.get(), observation)
                .ifPresent(notice -> recordCurrentStateNotice(notice));
        List<MemoryEntry> conflicts = MemoryObservationConflictDetector.conflictingEntries(
                observation, longTermMemory.getAll());
        if (conflicts.isEmpty()) {
            return;
        }

        if (observation.strength() != CurrentStateObservationSideChannel.ObservationStrength.HIGH) {
            recordCurrentStateNotice("低强度观察与长期记忆冲突，已降低本轮决策置信度但未覆盖长期记忆："
                    + observation.evidence() + "（strength=" + observation.strength() + "）");
            return;
        }

        Map<String, List<MemoryEntry>> bySubject = new HashMap<>();
        for (MemoryEntry conflict : conflicts) {
            String subject = MemoryObservationConflictDetector.subjectFor(conflict, observation);
            bySubject.computeIfAbsent(subject, ignored -> new ArrayList<>()).add(conflict);
        }
        for (Map.Entry<String, List<MemoryEntry>> group : bySubject.entrySet()) {
            List<String> conflictIds = group.getValue().stream().map(MemoryEntry::getId).toList();
            String negativeFact = "NegativeFact（负向事实）: 当前状态已推翻长期记忆 "
                    + String.join(",", conflictIds) + "；" + observation.evidence()
                    + "。本次及后续检索不得继续依赖被推翻记忆。";
            Map<String, String> metadata = new HashMap<>();
            metadata.put("source", "tool_observation");
            metadata.put("memory_type", "fact");
            metadata.put("subject", group.getKey());
            metadata.put("negative_fact", "true");
            metadata.put("observed_value", observation.value());
            metadata.put("observation_tool", toolName);
            metadata.put("reason_code", "CURRENT_STATE_CONFLICT");
            metadata.put("confidence", observation.strength().name());
            metadata.put("review_state", "REVIEWED");
            MemoryEvidence evidence = MemoryEvidence.fromPolicy(metadata, observation.evidence());
            MemoryEntry entry = new MemoryEntry(
                    "negative-" + UUID.randomUUID().toString().substring(0, 8),
                    negativeFact,
                    MemoryEntry.MemoryType.FACT,
                    Instant.now(),
                    metadata,
                    MemoryEntry.estimateTokens(negativeFact),
                    group.getKey(),
                    true,
                    "",
                    MemoryEntry.CURRENT_SCHEMA_VERSION,
                    1,
                    null,
                    evidence
            );
            if (longTermMemory.storeObservationInvalidation(entry, conflictIds)) {
                recordCurrentStateNotice(negativeFact);
                notifyAutoSaved(entry, metadata);
            }
        }
    }

    private void recordCurrentStateNotice(String notice) {
        sessionMemory.accept(new SessionMemory.KeyEvent(
                notice, 100, "system", currentEvidenceScope(),
                sessionEventSequence.incrementAndGet()));
        currentStateConflictNotices.get().add(notice);
    }

    /**
     * 取出本执行线程刚产生的状态冲突指令。执行内核把它追加到工具结果之后，
     * 覆盖当轮早先注入但已被当前证据推翻的长期记忆快照。
     */
    public String drainCurrentStateConflictInstruction() {
        List<String> notices = currentStateConflictNotices.get();
        if (notices.isEmpty()) {
            currentStateConflictNotices.remove();
            return "";
        }
        String instruction = "程序已处理当前状态证据；程序已确认当前状态推翻旧记忆时，"
                + "本轮推理不得继续依赖被 supersede 的旧记忆。低强度观察只作为冲突警告：\n- "
                + String.join("\n- ", notices);
        currentStateConflictNotices.remove();
        return instruction;
    }

    // ─────────────────────────────────────────────────────────
    // 工具证据的出处范围（Multi-Agent 步骤隔离）
    // ─────────────────────────────────────────────────────────

    /**
     * 当前线程的证据出处范围。Multi-Agent 并行 Worker 各占一个线程，
     * 由 {@code AgentOrchestrator} 在步骤执行前后设置，使证据能标出产生它的步骤。
     * 单 Agent / Plan 路径为空串。
     */
    private final ThreadLocal<String> evidenceScope = ThreadLocal.withInitial(() -> "");
    private final ThreadLocal<String> evidenceAgentId = ThreadLocal.withInitial(() -> "react");
    private final ThreadLocal<Long> evidenceOrigin = ThreadLocal.withInitial(() -> 0L);
    private final ThreadLocal<Long> evidenceContextEpoch = ThreadLocal.withInitial(() -> 0L);
    private final ThreadLocal<List<String>> currentStateConflictNotices =
            ThreadLocal.withInitial(ArrayList::new);

    private String currentEvidenceScope() {
        String scope = evidenceScope.get();
        return scope == null ? "" : scope;
    }

    private String currentAgentId() {
        String agentId = evidenceAgentId.get();
        return agentId == null || agentId.isBlank() ? "react" : agentId;
    }

    private long currentEvidenceOrigin() {
        Long value = evidenceOrigin.get();
        return value == null ? 0 : Math.max(0, value);
    }

    private long currentContextEpoch() {
        Long value = evidenceContextEpoch.get();
        return value == null ? 0 : Math.max(0, value);
    }

    /**
     * 在给定证据出处范围内执行。范围只影响本线程，嵌套调用结束后恢复外层范围。
     */
    public <T> T runWithEvidenceScope(String scope, java.util.function.Supplier<T> action) {
        return runWithEvidenceOrigin(scope == null || scope.isBlank() ? "react" : "worker", scope, action);
    }

    public <T> T runWithEvidenceOrigin(String agentId, String stepId,
                                       java.util.function.Supplier<T> action) {
        return runWithEvidenceOrigin(agentId, stepId, 0, action);
    }

    public <T> T runWithEvidenceOrigin(String agentId, String stepId, long contextEpoch,
                                       java.util.function.Supplier<T> action) {
        String previous = currentEvidenceScope();
        String previousAgent = currentAgentId();
        long previousOrigin = currentEvidenceOrigin();
        long previousContextEpoch = currentContextEpoch();
        String nextStep = stepId == null ? "" : stepId;
        String nextAgent = agentId == null || agentId.isBlank() ? "react" : agentId;
        long origin = sessionEventSequence.incrementAndGet();
        evidenceScope.set(nextStep);
        evidenceAgentId.set(nextAgent);
        evidenceOrigin.set(origin);
        evidenceContextEpoch.set(Math.max(0, contextEpoch));
        sessionMemory.accept(new SessionMemory.EvidenceScopeStarted(
                nextAgent, nextStep, origin, Math.max(0, contextEpoch), origin));
        try {
            return action.get();
        } finally {
            if (previous.isEmpty()) {
                evidenceScope.remove();
            } else {
                evidenceScope.set(previous);
            }
            if ("react".equals(previousAgent)) evidenceAgentId.remove();
            else evidenceAgentId.set(previousAgent);
            if (previousOrigin == 0) evidenceOrigin.remove();
            else evidenceOrigin.set(previousOrigin);
            if (previousContextEpoch == 0) evidenceContextEpoch.remove();
            else evidenceContextEpoch.set(previousContextEpoch);
        }
    }

    /** 设置任务状态（plan_task / react_iteration / last_error 等）。 */
    public void setTaskState(String key, String value) {
        sessionMemory.accept(new SessionMemory.StateChanged(
                key, value, currentAgentId(), currentEvidenceScope(),
                sessionEventSequence.incrementAndGet()));
    }

    // ─────────────────────────────────────────────────────────
    // TaskLedger（计划执行进度投影，注入 SessionMemory）
    // ─────────────────────────────────────────────────────────

    /** 设置当前计划及全部步骤（PlanExecuteAgent 在计划创建后调用，覆盖旧账本）。 */
    public void setTaskLedgerPlan(String planId, String goal, Map<String, String> stepIdToDesc) {
        sessionMemory.accept(new SessionMemory.PlanChanged(
                planId, goal, stepIdToDesc, "planner", "",
                sessionEventSequence.incrementAndGet()));
    }

    /** 标记步骤开始执行。 */
    public void startTaskStep(String stepId) {
        sessionMemory.accept(new SessionMemory.StepChanged(
                stepId, TaskLedger.StepStatus.RUNNING, "", "worker",
                sessionEventSequence.incrementAndGet()));
    }

    /** 标记步骤完成。 */
    public void completeTaskStep(String stepId) {
        sessionMemory.accept(new SessionMemory.StepChanged(
                stepId, TaskLedger.StepStatus.DONE, "", "worker",
                sessionEventSequence.incrementAndGet()));
    }

    /** 标记步骤失败并记录错误。 */
    public void failTaskStep(String stepId, String error) {
        sessionMemory.accept(new SessionMemory.StepChanged(
                stepId, TaskLedger.StepStatus.FAILED, error, "worker",
                sessionEventSequence.incrementAndGet()));
    }

    /** 添加一条本会话临时事实。 */
    public void addVolatileFact(String fact) {
        sessionMemory.accept(new SessionMemory.KeyEvent(
                fact, 0, currentAgentId(), currentEvidenceScope(),
                sessionEventSequence.incrementAndGet()));
    }

    /** 开始明确任务；新的 taskId 会轮换掉上一任务的短期运行投影。 */
    public synchronized void beginTask(String taskId) {
        String normalized = taskId == null ? "" : taskId.trim();
        if (normalized.isBlank()) return;
        if (!normalized.equals(sessionMemory.snapshot().taskId())) {
            memoryIgnored = false;
        }
        sessionMemory.beginTask(normalized);
    }

    /** 标记明确任务结束；投影保留到下一任务开始，避免最终答复丢失证据。 */
    public void endTask(String taskId) {
        sessionMemory.endTask(taskId);
    }

    /**
     * 先把当前任务必要子集写入可重放队列，再异步交给全隔离 Curator。
     * 调用方应在 {@link #endTask(String)} 前调用，避免下一个任务轮换运行投影。
     */
    public String completeTask(String taskId, String userRequest,
                               String finalResult, String projectScope) {
        if (memoryIgnored) return "";
        MemoryPromotionPipeline pipeline = memoryPromotionPipeline;
        if (pipeline == null) return "";
        TaskMemorySnapshot snapshot = TaskMemorySnapshot.capture(taskId, projectScope,
                userRequest, finalResult, sessionMemory.snapshot());
        String jobId = pipeline.enqueue(snapshot);
        schedulePendingPromotions();
        return jobId;
    }

    private void schedulePendingPromotions() {
        if (memoryPromotionPipeline == null || memoryPromotionExecutor.isShutdown()) return;
        memoryPromotionExecutor.submit(() -> {
            MemoryPromotionPipeline pipeline = memoryPromotionPipeline;
            int consecutiveFailures = 0;
            while (pipeline != null) {
                boolean processed = pipeline.processNext();
                if (processed) {
                    consecutiveFailures = 0;
                    pipeline = memoryPromotionPipeline;
                    continue;
                }
                if (!memoryPromotionQueue.hasRunnableJobs() || consecutiveFailures >= 3) {
                    break;
                }
                consecutiveFailures++;
                try {
                    Thread.sleep(100L * (1L << Math.min(3, consecutiveFailures - 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public String listPendingMemoryPromotions(int limit) {
        List<MemoryPromotionQueue.Job> jobs = memoryPromotionQueue.listAwaitingConfirmation(limit);
        if (jobs.isEmpty()) return "没有待确认的长期记忆候选。";
        StringBuilder output = new StringBuilder("待确认的长期记忆候选：\n");
        for (MemoryPromotionQueue.Job job : jobs) {
            output.append("- id=").append(SensitiveDataRedactor.redact(job.id()))
                    .append(", task=").append(SensitiveDataRedactor.redact(job.snapshot().taskId()))
                    .append("\n  candidate=").append(truncateForPrompt(
                            SensitiveDataRedactor.redact(job.detail()), 300))
                    .append('\n');
        }
        return output.toString().trim();
    }

    public boolean confirmMemoryPromotion(String jobId, boolean approved) {
        MemoryPromotionPipeline pipeline = memoryPromotionPipeline;
        return pipeline != null && jobId != null && !jobId.isBlank()
                && pipeline.confirm(jobId.trim(), approved, "");
    }

    // ─────────────────────────────────────────────────────────
    // 写入 LongTermMemory
    // ─────────────────────────────────────────────────────────

    /**
     * 存储关键事实到长期记忆
     */
    public void storeFact(String fact) {
        storeFact(fact, Map.of("source", "fact"));
    }

    /**
     * 带长期记忆策略的写入入口。低价值信息不会落库，敏感/中等置信信息返回确认结果。
     */
    public StoreResult storeFactWithPolicy(String fact) {
        return storeFactWithPolicy(fact, false);
    }

    /**
     * @param explicitRequest true 表示用户已经通过 /save 或 save_memory 明确请求长期保存
     */
    public StoreResult storeFactWithPolicy(String fact, boolean explicitRequest) {
        LongTermMemoryPolicy.Decision decision = LongTermMemoryPolicy.evaluate(fact, 0, explicitRequest);
        if (decision.action() != LongTermMemoryPolicy.Action.SAVE) {
            SensitiveDataRedactor.RedactionResult redaction = SensitiveDataRedactor.inspect(fact);
            if (decision.action() == LongTermMemoryPolicy.Action.CONFIRM && redaction.changed()) {
                String confirmationId = "memory-confirm-" + UUID.randomUUID().toString().substring(0, 8);
                memoryConfirmationStore.create(confirmationId, redaction.sanitizedText(),
                        Instant.now().plusSeconds(MEMORY_CONFIRMATION_TTL_SECONDS));
                return new StoreResult(false, decision,
                        "检测到敏感字段，将保存脱敏后的内容：" + redaction.sanitizedText()
                                + "；已剥离类型：" + redaction.removedTypesCsv()
                                + "。请选择：保存脱敏版 / 取消 / 手动编辑",
                        "", confirmationId);
            }
            return new StoreResult(false, decision, "长期记忆策略" + switch (decision.action()) {
                case CONFIRM -> "需要确认: " + decision.reason();
                case SKIP -> "跳过: " + decision.reason();
                case SAVE -> "允许保存";
            });
        }
        String id = storeFact(fact, decision.metadata());
        if (!longTermMemory.isPersistent()) {
            return new StoreResult(true, decision,
                    "已存入本会话内存，但未持久化（长期记忆存储不可用，重启后丢失）", id);
        }
        return new StoreResult(true, decision, "已保存到长期记忆", id);
    }

    /**
     * 用户在敏感记忆确认界面选择“保存脱敏版”或提交手工编辑内容后的唯一写入入口。
     * 本方法会重新检测并清理明文，不能用确认动作绕过最终落库边界。
     */
    public StoreResult storeRedactedFact(String fact) {
        SensitiveDataRedactor.RedactionResult redaction = SensitiveDataRedactor.inspect(fact);
        if (redaction.changed() && !hasReusableRedactedKnowledge(redaction.sanitizedText())) {
            String message = redaction.removed("account")
                    ? "普通长期记忆不保存账号；如确需持久化，请使用 secrets vault"
                    : "临时凭据只保留在当前会话，不进入长期记忆";
            LongTermMemoryPolicy.Decision rejected = LongTermMemoryPolicy.Decision.skip(
                    message,
                    redaction.removed("account") ? "SECRETS_VAULT_REQUIRED" : "TEMPORARY_CREDENTIAL_SESSION_ONLY",
                    "fact", redaction.sensitivity(), "HIGH");
            return new StoreResult(false, rejected, message);
        }

        LongTermMemoryPolicy.Decision original = LongTermMemoryPolicy.evaluate(fact, 0, true);
        Map<String, String> metadata = new HashMap<>(original.metadata());
        metadata.put("source", "explicit");
        metadata.put("reason_code", "SENSITIVE_REDACTED_CONFIRMED");
        metadata.put("confidence", "HIGH");
        LongTermMemoryPolicy.Decision confirmed = new LongTermMemoryPolicy.Decision(
                LongTermMemoryPolicy.Action.SAVE, "用户确认保存脱敏版", Map.copyOf(metadata));
        String safeFact = redaction.sanitizedText();
        String id = longTermMemory.getAll().stream()
                .filter(MemoryEntry::isActive)
                .filter(entry -> entry.getContent().equals(safeFact))
                .map(MemoryEntry::getId)
                .findFirst()
                .orElseGet(() -> storeFact(safeFact, confirmed.metadata()));
        String message = longTermMemory.isPersistent()
                ? "已保存脱敏后的长期记忆"
                : "已保存脱敏内容到本会话内存，但长期记忆存储不可用";
        return new StoreResult(true, confirmed, message, id);
    }

    /** 使用策略签发的一次性确认 id 完成敏感记忆保存，避免模型绕过确认边界。 */
    public StoreResult confirmSensitiveMemory(String confirmationId, String editedFact) {
        pruneExpiredMemoryConfirmations();
        if (confirmationId == null || confirmationId.isBlank()) {
            return invalidConfirmation("缺少 confirmation_id");
        }
        MemoryConfirmationStore.Ticket pending = memoryConfirmationStore
                .find(confirmationId.trim(), Instant.now()).orElse(null);
        if (pending == null) {
            return invalidConfirmation("确认已过期、已使用或不存在");
        }
        if (pending.state() == MemoryConfirmationStore.State.COMPLETED) {
            return pending.result();
        }
        String source = editedFact == null || editedFact.isBlank() ? pending.sanitizedFact() : editedFact;
        StoreResult result = storeRedactedFact(source);
        memoryConfirmationStore.complete(confirmationId.trim(), result);
        return result;
    }

    public boolean cancelSensitiveMemory(String confirmationId) {
        pruneExpiredMemoryConfirmations();
        return confirmationId != null
                && memoryConfirmationStore.cancel(confirmationId.trim(), Instant.now());
    }

    private StoreResult invalidConfirmation(String reason) {
        LongTermMemoryPolicy.Decision rejected = LongTermMemoryPolicy.Decision.skip(
                reason, "INVALID_MEMORY_CONFIRMATION", "fact", "low", "HIGH");
        return new StoreResult(false, rejected, reason);
    }

    private void pruneExpiredMemoryConfirmations() {
        memoryConfirmationStore.pruneExpired(Instant.now());
    }

    private static boolean hasReusableRedactedKnowledge(String sanitized) {
        if (sanitized == null || sanitized.isBlank()) {
            return false;
        }
        String remainder = sanitized
                .replaceAll("(?i)(?:token|api[_-]?key|key|password|secret|authorization|账号|账户|用户名|account|username)"
                        + "\\s*[:=：]\\s*\\*{3}", " ")
                .replaceAll("\\[REDACTED_[A-Z_]+]", " ")
                .replaceAll("(?i)\\b(?:remember|please|store|save)\\b", " ")
                .replaceAll("记住|记一下|记下来|以后记得|保存", " ")
                .replaceAll("[\\p{Punct}，。；：、\\s]+", "")
                .trim();
        return remainder.length() >= 6;
    }

    private String storeFact(String fact, Map<String, String> metadata) {
        SensitiveDataRedactor.RedactionResult redaction = SensitiveDataRedactor.inspect(fact);
        String safeFact = redaction.sanitizedText();
        Map<String, String> effectiveMetadata = new HashMap<>(
                metadata == null || metadata.isEmpty() ? Map.of("source", "fact") : metadata);
        if (redaction.changed()) {
            effectiveMetadata.put("redacted", "true");
            effectiveMetadata.put("redacted_types", redaction.removedTypesCsv());
        }
        if ("explicit".equalsIgnoreCase(effectiveMetadata.getOrDefault("source", ""))) {
            MemoryEntry existing = longTermMemory.getAll().stream()
                    .filter(MemoryEntry::isRecallable)
                    .filter(entry -> entry.getContent().equals(safeFact))
                    .findFirst().orElse(null);
            if (existing != null) {
                longTermMemory.recordValidated(existing.getId(), Instant.now());
                return existing.getId();
            }
        }
        String subject = MemorySubjectExtractor.extract(safeFact, effectiveMetadata);
        if (!subject.isBlank()) {
            effectiveMetadata.put(MemoryWriteProtocol.META_SUBJECT_SOURCE,
                    MemoryWriteProtocol.SUBJECT_SOURCE_DETERMINISTIC);
        }
        effectiveMetadata = Map.copyOf(effectiveMetadata);
        MemoryEvidence evidence = MemoryEvidence.fromPolicy(effectiveMetadata, safeFact);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                safeFact,
                memoryEntryType(effectiveMetadata),
                Instant.now(),
                effectiveMetadata,
                MemoryEntry.estimateTokens(safeFact),
                subject,
                true,
                "",
                MemoryEntry.CURRENT_SCHEMA_VERSION,
                1,
                null,
                evidence
        );
        longTermMemory.storeManaged(entry);
        notifyAutoSaved(entry, effectiveMetadata);
        return entry.getId();
    }

    /**
     * 回传写入事件。写入长期记忆意味着跨会话持久化用户内容，
     * 不允许无声——监听器失败不能影响写入主路径。
     */
    private void notifyAutoSaved(MemoryEntry entry, Map<String, String> metadata) {
        try {
            autoSaveListener.accept(new AutoSavedFact(
                    entry.getId(),
                    entry.getContent(),
                    metadata.getOrDefault("source", "policy"),
                    metadata.getOrDefault("reason_code", ""),
                    metadata.getOrDefault("memory_type", "fact")));
        } catch (RuntimeException e) {
            log.warn("auto-save listener failed for memory {}", entry.getId(), e);
        }
    }

    private static MemoryEntry.MemoryType memoryEntryType(Map<String, String> metadata) {
        String type = metadata == null ? "" : metadata.getOrDefault("memory_type", "");
        if ("feedback".equalsIgnoreCase(type)) {
            return MemoryEntry.MemoryType.FEEDBACK;
        }
        return MemoryEntry.MemoryType.FACT;
    }

    public record StoreResult(boolean stored, LongTermMemoryPolicy.Decision decision, String message,
                              String id, String confirmationId) {
        public StoreResult(boolean stored, LongTermMemoryPolicy.Decision decision, String message) {
            this(stored, decision, message, "", "");
        }

        public StoreResult(boolean stored, LongTermMemoryPolicy.Decision decision, String message, String id) {
            this(stored, decision, message, id, "");
        }
    }

    /**
     * 一次长期记忆写入事件。自动写入与显式写入都走这条通道，让 CLI 能告诉用户
     * 写了什么、依据哪条规则、怎么删。
     */
    public record AutoSavedFact(String id, String content, String source,
                                String reasonCode, String memoryType) {}

    /** 长期记忆写入监听器。默认无监听（单元测试与无 CLI 场景）。 */
    private volatile java.util.function.Consumer<AutoSavedFact> autoSaveListener = fact -> {};

    public void setAutoSaveListener(java.util.function.Consumer<AutoSavedFact> listener) {
        this.autoSaveListener = listener == null ? fact -> {} : listener;
    }

    /** 按 id 删除单条长期记忆。自动写入提示里给出的删除入口。 */
    public boolean forgetLongTermMemory(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return longTermMemory.delete(id.trim());
    }

    /**
     * 返回当前持久化长期记忆的只读快照，供工具层审计和展示。
     */
    public String listLongTermMemory(int limit) {
        List<MemoryEntry> entries = longTermMemory.getAll().stream()
                .sorted(java.util.Comparator.comparing(MemoryEntry::getTimestamp).reversed())
                .limit(Math.max(1, limit))
                .toList();
        if (entries.isEmpty()) {
            return "长期记忆为空。";
        }
        StringBuilder sb = new StringBuilder("长期记忆（LongTermMemory）当前持久化条目：\n");
        for (MemoryEntry entry : entries) {
            sb.append("- id=").append(SensitiveDataRedactor.redact(entry.getId()))
                    .append(", type=").append(entry.getType())
                    .append(", confidence=").append(entry.getEvidence().confidence())
                    .append(", review=").append(entry.getEvidence().reviewState());
            if (!entry.getSubject().isBlank()) {
                sb.append(", subject=").append(SensitiveDataRedactor.redact(entry.getSubject()));
            }
            if (!entry.isActive()) {
                sb.append(", active=false, superseded_by=").append(SensitiveDataRedactor.redact(
                        entry.getSupersededBy()));
            }
            sb.append(", created_at=").append(entry.getTimestamp())
                    .append("\n  content: ").append(SensitiveDataRedactor.redact(entry.getContent()));
            if (!entry.getEvidence().sourceQuote().isBlank()) {
                sb.append("\n  source_quote: ").append(truncateForPrompt(
                        SensitiveDataRedactor.redact(entry.getEvidence().sourceQuote()), 200));
            }
            if (!entry.getEvidence().reasoning().isBlank()) {
                sb.append("\n  reasoning: ").append(SensitiveDataRedactor.redact(
                        entry.getEvidence().reasoning()));
            }
            if (!entry.getEvidence().conflictsWith().isEmpty()) {
                sb.append("\n  conflicts_with: ").append(SensitiveDataRedactor.redact(
                        entry.getEvidence().conflictsWith().toString()));
            }
            if (!entry.getMetadata().isEmpty()) {
                sb.append("\n  metadata: ").append(SensitiveDataRedactor.redact(
                        entry.getMetadata().toString()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public boolean reviewLongTermMemory(String id, MemoryEvidence.ReviewState reviewState) {
        return longTermMemory.updateReviewState(id, reviewState);
    }

    public MemoryOrganizer.Report organizeLongTermMemory(MemoryOrganizer.Mode mode) {
        return new MemoryOrganizer(llmClient, longTermMemory).organize(mode);
    }

    // ─────────────────────────────────────────────────────────
    // 读取（注入到 system prompt）
    // ─────────────────────────────────────────────────────────

    /**
     * 检索与 query 最相关的长期记忆。短期记忆走 {@link SessionMemory#render(SessionMemory.SessionView, int)}
     * 直接注入，不参与 query-based 检索。
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        if (memoryIgnored) {
            return List.of();
        }
        return retriever.retrieveLongTerm(query, limit, activeScopeKeys);
    }

    /** 绑定当前项目作用域；全局和用户偏好仍始终可见。 */
    public void setActiveProjectScope(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            activeScopeKeys = java.util.Set.of();
            return;
        }
        activeScopeKeys = java.util.Set.of(java.nio.file.Path.of(projectPath)
                .toAbsolutePath().normalize().toString());
    }

    public void setMemoryIgnored(boolean ignored) {
        this.memoryIgnored = ignored;
    }

    public boolean isMemoryIgnored() {
        return memoryIgnored;
    }

    /**
     * 构建用于 LLM 的长期记忆上下文（按 query 检索 top-k）。
     * 当用户显式忽略记忆时返回空字符串。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        if (memoryIgnored) {
            return "";
        }
        int safeBudget = Math.max(64, maxTokens);
        List<String> volatileFacts = sessionMemory.getVolatileFacts();
        InventoryContext inventory = MemoryIntentClassifier.classify(query) == MemoryIntentClassifier.Intent.INVENTORY
                ? buildLongTermMemoryInventorySnapshot(5, Math.min(256, safeBudget), volatileFacts)
                : InventoryContext.empty();
        int relevantBudget = Math.max(0, safeBudget - MemoryEntry.estimateTokens(inventory.text()));
        MemoryRetriever.ContextResult relevantResult = relevantBudget == 0
                ? MemoryRetriever.ContextResult.empty()
                : retriever.buildContext(query, relevantBudget, volatileFacts, activeScopeKeys);
        String relevant = relevantResult.text();
        java.util.LinkedHashSet<String> injectedIds = new java.util.LinkedHashSet<>(inventory.memoryIds());
        injectedIds.addAll(relevantResult.injectedMemoryIds());
        longTermMemory.recordRecalled(injectedIds, Instant.now());
        if (relevant.isBlank()) {
            return inventory.text();
        }
        if (inventory.text().isBlank()) {
            return relevant.trim();
        }
        return inventory.text() + "\n\n" + relevant.trim();
    }

    private InventoryContext buildLongTermMemoryInventorySnapshot(
            int limit, int maxTokens, List<String> suppressedFacts) {
        List<MemoryEntry> activeEntries = longTermMemory.getAll().stream()
                .filter(MemoryEntry::isRecallable)
                .filter(entry -> MemoryRetriever.isVisibleInScope(entry, activeScopeKeys))
                .filter(entry -> !MemoryFactDeduper.duplicatesAny(entry.getContent(), suppressedFacts))
                .sorted(java.util.Comparator.comparing(MemoryEntry::getTimestamp).reversed())
                .toList();
        int total = activeEntries.size();
        if (total == 0) {
            return new InventoryContext(
                    "## 长期记忆索引快照\n\n- total: 0\n- 当前持久化长期记忆为空。", List.of());
        }
        StringBuilder context = new StringBuilder("## 长期记忆索引快照\n\n");
        context.append("- total: ").append(total).append('\n');
        context.append("- 说明: 这是持久化长期记忆的轻量目录；用户要求完整查看或审计时调用 list_memory。\n");
        List<MemoryEntry> entries = activeEntries.stream()
                .limit(Math.max(1, limit))
                .toList();
        int usedTokens = MemoryEntry.estimateTokens(context.toString());
        List<String> injectedIds = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            String line = "- [" + entry.getType()
                    + "; confidence=" + entry.getEvidence().confidence()
                    + "; review=" + entry.getEvidence().reviewState()
                    + "] " + truncateForPrompt(SensitiveDataRedactor.redact(entry.getContent()), 120) + "\n";
            int lineTokens = MemoryEntry.estimateTokens(line);
            if (usedTokens + lineTokens > maxTokens && usedTokens > 0) {
                context.append("- ...\n");
                break;
            }
            context.append(line);
            usedTokens += lineTokens;
            injectedIds.add(entry.getId());
        }
        return new InventoryContext(context.toString().trim(), injectedIds);
    }

    private record InventoryContext(String text, List<String> memoryIds) {
        private InventoryContext {
            text = text == null ? "" : text;
            memoryIds = memoryIds == null ? List.of() : List.copyOf(memoryIds);
        }

        static InventoryContext empty() {
            return new InventoryContext("", List.of());
        }
    }

    private static String truncateForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    /**
     * 渲染 SessionMemory 派生视图为 system prompt 段落。
     * Agent / PlanExecuteAgent / SubAgent 通过这条路径把工作记忆注入给 LLM。
     */
    public String buildSessionMemorySection() {
        if (memoryIgnored) {
            return "";
        }
        return sessionMemory.render(SessionMemory.SessionView.FULL,
                contextProfile.shortTermMemoryBudget());
    }

    /** @deprecated 使用 {@link #buildSessionMemorySection()}。 */
    @Deprecated
    public String buildWorkingMemorySection() { return buildSessionMemorySection(); }

    /**
     * 压缩成功后恢复给 messages 的结构化短上下文。
     */
    public String buildPostCompactRestoreSection() {
        if (memoryIgnored) {
            return "";
        }
        return sessionMemory.renderForPostCompactRestore();
    }

    public String buildPostCompactRestoreSectionForAgent(String agentType) {
        if (memoryIgnored) {
            return "";
        }
        return sessionMemory.renderForPostCompactRestore(viewForAgent(agentType));
    }

    public String currentRagEpochSnapshot() {
        if (memoryIgnored) {
            return "none";
        }
        LinkedHashSet<String> epochs = new LinkedHashSet<>();
        for (SessionMemory.RagEvidence evidence : sessionMemory.getRagEvidenceMemory()) {
            String epoch = evidence.indexEpoch();
            if (epoch != null && !epoch.isBlank()) {
                epochs.add(epoch);
            }
        }
        return epochs.isEmpty() ? "none" : String.join(", ", epochs);
    }

    /**
     * turn 结束后的会话预摘要维护入口。
     *
     * <p>该入口只维护当前进程内 SessionMemory，不写长期记忆。触发条件保持保守：
     * token 增量、工具调用次数或单个大工具结果达到阈值时才调用 LLM 生成预摘要。
     */
    public SessionPreSummaryMaintenanceResult maintainSessionPreSummaryAfterTurn(
            List<LlmClient.Message> history,
            int turnToolCalls,
            int largestToolResultChars) {
        if (!Boolean.parseBoolean(System.getProperty(SESSION_PRE_SUMMARY_ENABLED_PROPERTY, "true"))) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED;
        }
        if (!ConversationHistoryCompactor.isCompactionEnabled(System.getProperties(), System.getenv())) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED;
        }
        if (llmClient == null || history == null || history.isEmpty()) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_EMPTY_HISTORY;
        }
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        if (history.size() <= systemEnd) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_EMPTY_HISTORY;
        }
        List<LlmClient.Message> coveredMessages = new ArrayList<>(history.subList(systemEnd, history.size()));
        if (compactionSummaryCache.findReusablePreSummary(coveredMessages).isPresent()) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_ALREADY_CURRENT;
        }
        int tokenEstimate = TokenBudget.estimateMessagesTokens(coveredMessages);
        int previousTokenEstimate = compactionSummaryCache.currentPreSummary()
                .map(CompactionSummaryCache.PreSummary::tokenEstimate)
                .orElse(0);
        int tokenDelta = tokenEstimate - previousTokenEstimate;
        boolean triggered = tokenDelta >= SESSION_PRE_SUMMARY_TOKEN_DELTA
                || turnToolCalls >= SESSION_PRE_SUMMARY_TOOL_CALLS
                || largestToolResultChars >= SESSION_PRE_SUMMARY_LARGE_TOOL_CHARS;
        if (!triggered) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_BELOW_THRESHOLD;
        }
        try {
            Optional<CompactionSummaryCache.PreSummary> incrementalBase =
                    compactionSummaryCache.findExtendablePreSummary(coveredMessages);
            List<LlmClient.Message> summaryRequest;
            String maintenanceMode;
            int deltaMessageCount;
            if (incrementalBase.isPresent()) {
                CompactionSummaryCache.PreSummary base = incrementalBase.get();
                List<LlmClient.Message> deltaMessages =
                        coveredMessages.subList(base.messageCount(), coveredMessages.size());
                maintenanceMode = "incremental";
                deltaMessageCount = deltaMessages.size();
                summaryRequest = List.of(
                        LlmClient.Message.system("你是会话增量预摘要维护器。请把旧摘要与新增消息合并为一份完整替代摘要，保留用户目标、关键决策、文件路径、工具结果、约束和未完成事项，不要只输出本次增量。"),
                        LlmClient.Message.user("旧摘要：\n" + base.summary()
                                + "\n\n新增消息：\n" + renderMessagesForPreSummary(deltaMessages))
                );
            } else {
                maintenanceMode = "full";
                deltaMessageCount = coveredMessages.size();
                summaryRequest = List.of(
                        LlmClient.Message.system("你是会话预摘要维护器。请保留用户目标、关键决策、文件路径、工具结果、约束和未完成事项，输出简洁中文摘要。"),
                        LlmClient.Message.user("请为以下会话内容生成可供后续上下文压缩复用的预摘要：\n\n"
                                + renderMessagesForPreSummary(coveredMessages))
                );
            }
            if (TokenBudget.estimateMessagesTokens(summaryRequest)
                    > Math.max(256, contextProfile.compressionTriggerTokens())) {
                log.info("skip session pre-summary because request exceeds the local input budget");
                return SessionPreSummaryMaintenanceResult.SKIPPED_INPUT_TOO_LARGE;
            }
            LlmClient.ChatResponse response = llmClient.chat(summaryRequest, List.of());
            if (Boolean.parseBoolean(System.getProperty(
                    ConversationHistoryCompactor.COMPACTION_METRICS_PROPERTY, "false"))) {
                System.err.printf(java.util.Locale.ROOT,
                        "[context-compaction] kind=pre-summary-call inputTokens=%d outputTokens=%d cachedInputTokens=%d%n",
                        response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            }
            String summary = response.content();
            if (summary == null || summary.isBlank()) {
                preSummaryFailureCount.incrementAndGet();
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            CompactionSemanticGuard.Validation validation = CompactionSemanticGuard.validateAndRepair(
                    coveredMessages, summary, ConversationHistoryCompactor.MAX_SUMMARY_CHARS);
            summary = validation.repairedSummary();
            if (summary == null || summary.isBlank()
                    || summary.length() > ConversationHistoryCompactor.MAX_SUMMARY_CHARS) {
                preSummaryFailureCount.incrementAndGet();
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            compactionSummaryCache.recordPreSummary(coveredMessages, summary);
            if ("incremental".equals(maintenanceMode)) {
                preSummaryIncrementalCount.incrementAndGet();
            } else {
                preSummaryFullCount.incrementAndGet();
            }
            lastPreSummaryMetrics = new SessionPreSummaryMetrics(
                    maintenanceMode,
                    coveredMessages.size(),
                    deltaMessageCount,
                    TokenBudget.estimateMessagesTokens(summaryRequest),
                    summary.length(),
                    preSummaryFullCount.get(),
                    preSummaryIncrementalCount.get(),
                    preSummaryFailureCount.get(),
                    Instant.now());
            return SessionPreSummaryMaintenanceResult.MAINTAINED;
        } catch (IOException | RuntimeException e) {
            preSummaryFailureCount.incrementAndGet();
            if (e instanceof com.devcli.llm.LlmException failure && Boolean.parseBoolean(System.getProperty(
                    ConversationHistoryCompactor.COMPACTION_METRICS_PROPERTY, "false"))) {
                System.err.printf(java.util.Locale.ROOT,
                        "[context-compaction] kind=pre-summary-error code=%s status=%d%n",
                        failure.code(), failure.statusCode());
            }
            log.warn("session pre-summary maintenance failed", e);
            return SessionPreSummaryMaintenanceResult.FAILED;
        }
    }

    public CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
            List<LlmClient.Message> history,
            int turnToolCalls,
            int largestToolResultChars) {
        List<LlmClient.Message> snapshot = history == null ? List.of() : List.copyOf(history);
        return CompletableFuture.supplyAsync(
                () -> maintainSessionPreSummaryAfterTurn(snapshot, turnToolCalls, largestToolResultChars),
                sessionPreSummaryExecutor);
    }

    private static String renderMessagesForPreSummary(List<LlmClient.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message message : messages) {
            sb.append("[").append(message.role()).append("] ");
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                sb.append("toolCallId=").append(message.toolCallId()).append(' ');
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                sb.append("toolCalls=");
                for (LlmClient.ToolCall toolCall : message.toolCalls()) {
                    if (toolCall.function() != null) {
                        sb.append(toolCall.function().name());
                        if (toolCall.function().arguments() != null
                                && !toolCall.function().arguments().isBlank()) {
                            sb.append(" args=")
                                    .append(truncateForPrompt(toolCall.function().arguments(), 1_000));
                        }
                        sb.append(' ');
                    }
                }
            }
            String content = message.content();
            if (content != null && !content.isBlank()) {
                sb.append(truncateForPrompt(content, 2_000));
            }
            sb.append("\n\n");
            if (sb.length() >= SESSION_PRE_SUMMARY_MAX_RENDER_CHARS) {
                sb.append("[预摘要输入超过上限，未继续展开更早消息]");
                break;
            }
        }
        return sb.toString().trim();
    }

    /**
     * 为 Multi-Agent 角色构建隔离后的工作记忆视图。
     *
     * Planner 只需要任务状态和关键事件，避免被 Worker 的工具原文证据污染；
     * Worker 需要完整执行上下文；
     * Reviewer 聚焦任务状态和工具证据，避免把会话事件当成验收证据。
     */
    public String buildSessionMemorySectionForAgent(String agentType) {
        if (memoryIgnored) {
            return "";
        }
        return sessionMemory.render(viewForAgent(agentType), contextProfile.shortTermMemoryBudget());
    }

    /** @deprecated 使用 {@link #buildSessionMemorySectionForAgent(String)}。 */
    @Deprecated
    public String buildWorkingMemorySectionForAgent(String agentType) {
        return buildSessionMemorySectionForAgent(agentType);
    }

    private static boolean hasIgnoreMemoryIntent(String content) {
        return MemoryIntentClassifier.classify(content) == MemoryIntentClassifier.Intent.IGNORE;
    }

    private static SessionMemory.SessionView viewForAgent(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return SessionMemory.SessionView.FULL;
        }
        String normalized = agentType.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("planner")) {
            return SessionMemory.SessionView.PLANNER;
        }
        if (normalized.contains("reviewer")) {
            return SessionMemory.SessionView.REVIEWER;
        }
        if (normalized.contains("worker")) {
            return SessionMemory.SessionView.WORKER;
        }
        return SessionMemory.SessionView.FULL;
    }

    // ─────────────────────────────────────────────────────────
    // Token 统计
    // ─────────────────────────────────────────────────────────

    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    // ─────────────────────────────────────────────────────────
    // 清理
    // ─────────────────────────────────────────────────────────

    /** 清空工作记忆（用于 /clear 命令；长期记忆保持不变）。 */
    public void clearShortTerm() {
        sessionMemory.clear();
        compactionSummaryCache.clearPreSummary();
        sessionEventSequence.set(0);
        memoryIgnored = false;
    }

    /** 清空长期记忆（用于 /memory clear 命令）。 */
    public void clearLongTerm() {
        memoryPromotionQueue.deleteAllJobs();
        longTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        SessionPreSummaryMetrics metrics = lastPreSummaryMetrics;
        return "上下文策略: " + contextProfile.summary() + "\n" +
                sessionMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                "会话预摘要: mode=" + metrics.mode()
                + ", covered=" + metrics.coveredMessages()
                + ", delta=" + metrics.deltaMessages()
                + ", input≈" + metrics.inputTokenEstimate()
                + ", summaryChars=" + metrics.summaryChars()
                + ", full/incremental/failed=" + metrics.fullCount() + "/"
                + metrics.incrementalCount() + "/" + metrics.failureCount() + "\n" +
                tokenBudget.getUsageReport();
    }

    // ─────────────────────────────────────────────────────────
    // Getter
    // ─────────────────────────────────────────────────────────

    public SessionMemory getSessionMemory() { return sessionMemory; }
    public CompactionSummaryCache getCompactionSummaryCache() { return compactionSummaryCache; }

    /**
     * @deprecated 使用 {@link #getSessionMemory()}。
     */
    @Deprecated
    public SessionMemory getShortTermMemory() { return sessionMemory; }

    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public MemoryRetriever getRetriever() { return retriever; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }
    public SessionPreSummaryMetrics getSessionPreSummaryMetrics() { return lastPreSummaryMetrics; }

    public record SessionPreSummaryMetrics(String mode, int coveredMessages, int deltaMessages,
                                           int inputTokenEstimate, int summaryChars,
                                           long fullCount, long incrementalCount, long failureCount,
                                           Instant updatedAt) {
        static SessionPreSummaryMetrics empty() {
            return new SessionPreSummaryMetrics("none", 0, 0, 0, 0, 0, 0, 0, Instant.EPOCH);
        }
    }

    public enum SessionPreSummaryMaintenanceResult {
        MAINTAINED,
        SKIPPED_DISABLED,
        SKIPPED_EMPTY_HISTORY,
        SKIPPED_BELOW_THRESHOLD,
        SKIPPED_INPUT_TOO_LARGE,
        SKIPPED_ALREADY_CURRENT,
        FAILED
    }

    /**
     * 关闭底层记忆资源。Main 长进程不需要主动调（JVM 退出释放）；
     * 主要给单元测试用，避免 SQLite 文件锁阻碍 @TempDir 清理。
     */
    @Override
    public void close() {
        sessionPreSummaryExecutor.shutdownNow();
        memoryPromotionExecutor.shutdown();
        try {
            if (!memoryPromotionExecutor.awaitTermination(
                    2, java.util.concurrent.TimeUnit.SECONDS)) {
                memoryPromotionExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            memoryPromotionExecutor.shutdownNow();
        }
        memoryPromotionQueue.close();
        memoryConfirmationStore.close();
        if (longTermMemory != null) {
            longTermMemory.close();
        }
    }
}
