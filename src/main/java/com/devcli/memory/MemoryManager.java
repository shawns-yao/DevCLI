package com.devcli.memory;

import com.devcli.llm.LlmClient;
import com.devcli.context.ContextProfile;
import com.devcli.policy.SensitiveDataRedactor;
import com.devcli.tool.ToolSideChannel;
import com.devcli.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final long SESSION_PRE_SUMMARY_FAILURE_COOLDOWN_MILLIS = 30_000L;
    /** 索引段落标题；其 token 也要从本轮记忆预算里扣掉。 */
    private static final String INDEX_HEADER = "## 长期记忆\n\n";
    /** 相关性全文段落的固定标题；其 token 也要从本轮记忆预算里扣掉。 */
    private static final String RELEVANT_MEMORY_HEADER = "\n\n## 与本次问题相关的长期记忆\n\n";
    private final SessionMemory sessionMemory;
    private final CompactionSummaryCache compactionSummaryCache;
    private final ConversationHistoryCompactor sessionPreSummaryCompactor;
    private final LongTermMemory longTermMemory;
    private final ExecutorService sessionPreSummaryExecutor;
    /** 后台预摘要最多保留一个运行中任务和一个最新待处理任务。 */
    private final Object sessionPreSummaryScheduleLock = new Object();
    private SessionPreSummaryTask runningSessionPreSummary;
    private SessionPreSummaryTask pendingSessionPreSummary;
    private boolean sessionPreSummaryClosed;
    private final AtomicLong preSummaryFullCount = new AtomicLong();
    private final AtomicLong preSummaryIncrementalCount = new AtomicLong();
    private final AtomicLong preSummaryFailureCount = new AtomicLong();
    private final AtomicLong sessionEventSequence = new AtomicLong();
    private volatile SessionPreSummaryMetrics lastPreSummaryMetrics = SessionPreSummaryMetrics.empty();
    private volatile String preSummaryFailureFingerprint = "";
    private volatile long preSummaryFailureUntilMillis;
    private LlmClient llmClient;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    /** 当前会话显式忽略记忆 flag。用户说"忘记记忆"/"别管记忆"时设为 true。 */
    private volatile boolean memoryIgnored = false;
    private volatile Supplier<String> ruleContextSupplier = () -> "";

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient      LLM 客户端（长期记忆选择器与预摘要使用）
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
        this.sessionPreSummaryCompactor = new ConversationHistoryCompactor(llmClient);
        this.sessionPreSummaryCompactor.setSummaryUsageConsumer(this::recordPreSummaryUsage);
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.sessionPreSummaryExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "devcli-session-pre-summary");
            t.setDaemon(true);
            return t;
        });
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.compactionSummaryCache.clearPreSummary();
        clearPreSummaryFailure();
        this.sessionPreSummaryCompactor.setLlmClient(llmClient);
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setRuleContextSupplier(Supplier<String> ruleContextSupplier) {
        this.ruleContextSupplier = ruleContextSupplier == null ? () -> "" : ruleContextSupplier;
    }

    // ─────────────────────────────────────────────────────────
    // 写入 SessionMemory（不进 LLM messages，作为当轮 user 消息的派生视图）
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
     * 在这里以原文形式保留，作为当轮 user 消息 "## 最近工具调用证据" 段注入 LLM。
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
        addToolResult(toolName, argsJson, result, sideChannels, null, null, List.of());
    }

    public void addToolResult(ToolRegistry.ToolExecutionResult toolResult) {
        if (toolResult == null) return;
        addToolResult(toolResult.name(), toolResult.argumentsJson(), toolResult.result(),
                toolResult.sideChannels(), toolResult.status(), toolResult.errorCode(), List.of());
    }

    private void addToolResult(String toolName, String argsJson, String result,
                               List<ToolSideChannel> sideChannels,
                               com.devcli.tool.ToolStatus status,
                               com.devcli.tool.ToolErrorCode errorCode,
                               List<String> modifiedResources) {
        if (memoryIgnored || toolName == null || result == null) return;
        sessionMemory.accept(new SessionMemory.ToolResultObserved(
                toolName, argsJson, result, sideChannels, currentAgentId(), currentEvidenceScope(),
                currentEvidenceOrigin(), currentContextEpoch(),
                sessionEventSequence.incrementAndGet(), status, errorCode, modifiedResources));
    }

    /**
     * 取出本执行线程刚产生的状态冲突指令。执行内核把它追加到工具结果之后，
     * 覆盖当轮早先注入但已被当前证据推翻的长期记忆快照。
     */
    public String drainCurrentStateConflictInstruction() {
        return "";
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
     * 任务收尾钩子。
     *
     * <p>这里**不再自动写长期记忆**。旧实现把任务快照交给全隔离 Curator，
     * 由它判证据、提作用域、进待确认队列；对齐 WorkBuddy 后记忆由 agent 在
     * 判断「这条值得跨会话保留」时显式写入（{@code save_memory} / {@code /save}），
     * 不再由收尾阶段批量猜测。保留空实现是为了让调用方不必感知这次移除。
     */
    public void completeTask(String taskId) {
        // 有意为空：见方法注释。
    }

    /** 兼容旧调用点；收尾阶段不再自动提取长期记忆。 */
    public void completeTask(String taskId, String userInput, String result, String projectPath) {
        completeTask(taskId);
    }

    /** 兼容运行时注入；新长期记忆路径不再使用独立 Curator 模型。 */
    public void setMemoryCuratorClient(LlmClient curatorClient) {
        // no-op
    }

    // ─────────────────────────────────────────────────────────
    // 写入 LongTermMemory
    // ─────────────────────────────────────────────────────────

    /**
     * 存储关键事实到长期记忆。
     */
    public void storeFact(String fact) {
        storeFactWithPolicy(fact, false);
    }

    public StoreResult storeFactWithPolicy(String fact) {
        return storeFactWithPolicy(fact, false);
    }

    /**
     * 长期记忆的唯一写入入口。
     *
     * <p>简化入口由宿主决定作用域：有活动项目就写项目目录，否则写全局目录；
     * 工具入口可显式选择作用域，但未知值会被拒绝，不会扩大为全局记忆。
     *
     * <p>旧实现是「策略判定 → 敏感确认票据 → 脱敏落库」三段式，判定不通过就整条丢弃，
     * 用户只看到一句「策略需要确认」。现在写入即落盘：成功返回文件名，失败如实返回原因。
     *
     * @param explicitRequest 是否来自用户明确请求（{@code /save} 或 {@code save_memory}）
     */
    public StoreResult storeFactWithPolicy(String fact, boolean explicitRequest) {
        if (fact == null || fact.isBlank()) {
            return new StoreResult(false, "内容为空，未保存", "");
        }
        return storeTopic(deriveName(fact), deriveDescription(fact), fact, explicitRequest);
    }

    /**
     * 带主题名与描述写入一条长期记忆。
     */
    public StoreResult storeTopic(String name, String description, String content,
                                  boolean explicitRequest) {
        MemoryScope scope = longTermMemory.defaultScope();
        String type = scope == MemoryScope.PROJECT ? "project" : "user";
        return storeTopic(scope, name, description, type, content, null, explicitRequest);
    }

    /**
     * 按明确作用域、类型与有效期写入。全局作用域只能由 CLI/工具参数明确选择，
     * 避免项目事实因模型推断被扩大为跨项目记忆。
     */
    public StoreResult storeTopic(MemoryScope scope, String name, String description,
                                  String type, String content, Instant expiresAt,
                                  boolean explicitRequest) {
        if (content == null || content.isBlank()) {
            return new StoreResult(false, "内容为空，未保存", "");
        }
        SensitiveDataRedactor.RedactionResult inspection = SensitiveDataRedactor.inspect(content);
        if (inspection.changed()) {
            return new StoreResult(false,
                    "内容包含敏感信息（" + inspection.removedTypesCsv()
                            + "），未写入；请脱敏后重试",
                    "", LongTermMemory.SaveStatus.REJECTED);
        }
        LongTermMemory.SaveResult result = longTermMemory.save(scope, name, description,
                type, content, expiresAt, explicitRequest);
        if (result.status() == LongTermMemory.SaveStatus.CREATED
                || result.status() == LongTermMemory.SaveStatus.UPDATED) {
            notifyAutoSaved(result.fileName(), content.strip(),
                    explicitRequest ? "explicit" : "auto");
        }
        return new StoreResult(result.stored(), result.message()
                + (result.fileName().isBlank() ? "" : " " + result.fileName()),
                result.fileName(), result.status());
    }

    /** 工具层入口；空作用域默认当前项目，空名称/描述从内容推导。 */
    public StoreResult storeTopic(String scopeValue, String name, String description,
                                  String type, String content, Integer validDays,
                                  boolean explicitRequest) {
        MemoryScope scope;
        try {
            scope = scopeValue == null || scopeValue.isBlank()
                    ? longTermMemory.defaultScope()
                    : MemoryScope.of(scopeValue);
        } catch (IllegalArgumentException e) {
            return new StoreResult(false, e.getMessage(), "",
                    LongTermMemory.SaveStatus.REJECTED);
        }
        if (validDays != null && (validDays < 1 || validDays > 3_650)) {
            return new StoreResult(false, "valid_days 必须为 1–3650", "",
                    LongTermMemory.SaveStatus.REJECTED);
        }
        String effectiveName = name == null || name.isBlank() ? deriveName(content) : name.trim();
        String effectiveDescription = description == null || description.isBlank()
                ? deriveDescription(content) : description.trim();
        String effectiveType = type == null || type.isBlank()
                ? (scope == MemoryScope.PROJECT ? "project" : "user")
                : type.trim();
        Instant expiresAt = validDays == null ? null
                : Instant.now().plusSeconds(validDays.longValue() * 86_400L);
        return storeTopic(scope, effectiveName, effectiveDescription, effectiveType,
                content, expiresAt, explicitRequest);
    }

    /** 主题名：取首句（截到换行或首个句末标点），最长 40 字。 */
    private static String deriveName(String fact) {
        String firstLine = fact.strip().split("\n", 2)[0].strip();
        String firstSentence = firstLine.split("[。！？.!?;；]", 2)[0].strip();
        String candidate = firstSentence.isEmpty() ? firstLine : firstSentence;
        return candidate.length() > 40 ? candidate.substring(0, 40) : candidate;
    }

    /** 描述：取首行，最长 120 字。 */
    private static String deriveDescription(String fact) {
        String firstLine = fact.strip().split("\n", 2)[0].strip();
        return firstLine.length() > 120 ? firstLine.substring(0, 120) : firstLine;
    }

    /**
     * 回传写入事件。写入长期记忆意味着跨会话持久化用户内容，
     * 不允许无声——监听器失败不能影响写入主路径。
     */
    private void notifyAutoSaved(String id, String content, String source) {
        try {
            autoSaveListener.accept(new AutoSavedFact(id, content, source, source, "topic"));
        } catch (RuntimeException e) {
            log.warn("auto-save listener failed for memory {}", id, e);
        }
    }

    /**
     * 一次长期记忆写入事件。
     *
     * @param source     {@code explicit}（用户明确要求）或 {@code auto}
     * @param reasonCode 与 {@code source} 同值；保留字段以兼容既有展示
     * @param memoryType 记忆形态，固定 {@code topic}（一条记忆即一篇主题文件）
     */
    public record AutoSavedFact(String id, String content, String source,
                                String reasonCode, String memoryType) {}

    /**
     * 一次写入结果。
     *
     * @param stored  是否落盘成功
     * @param message 面向用户的一句话说明
     * @param id      落盘文件名；失败时为空串
     */
    public record StoreResult(boolean stored, String message, String id,
                              LongTermMemory.SaveStatus status) {
        public StoreResult(boolean stored, String message, String id) {
            this(stored, message, id,
                    stored ? LongTermMemory.SaveStatus.CREATED : LongTermMemory.SaveStatus.REJECTED);
        }

        public StoreResult {
            message = message == null ? "" : message;
            id = id == null ? "" : id;
            status = status == null ? LongTermMemory.SaveStatus.REJECTED : status;
        }
    }

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
     * 列出当前可见作用域的长期记忆，供工具层与 CLI 展示。
     *
     * <p>每行给出文件名、主题名、修改时间与作用域——文件名就是删除时要传的 id。
     */
    public String listLongTermMemory(int limit) {
        List<MemoryEntry> entries = longTermMemory.getAll().stream()
                .limit(Math.max(1, limit))
                .toList();
        if (entries.isEmpty()) {
            return "长期记忆为空。记忆目录：" + longTermMemory.rootDir();
        }
        StringBuilder sb = new StringBuilder("长期记忆当前条目（目录 "
                + longTermMemory.rootDir() + "）：\n");
        for (MemoryEntry entry : entries) {
            sb.append("- ").append(entry.getId())
                    .append("  [").append(entry.getScope()).append(']')
                    .append("  ").append(entry.getName())
                    .append("  (修改于 ").append(entry.getTimestamp()).append(")\n");
            if (!entry.getDescription().isBlank()) {
                sb.append("    ").append(truncateForPrompt(entry.getDescription(), 200)).append('\n');
            }
        }
        sb.append("删除单条：/memory forget <文件名>");
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────
    // 读取（注入到当轮 user 消息）
    // ─────────────────────────────────────────────────────────

    /**
     * 检索与 query 相关的长期记忆。短期记忆走 {@link SessionMemory#render(SessionMemory.SessionView, int)}
     * 直接注入，不参与检索。
     *
     * <p>候选来自当前可见作用域的目录扫描——作用域由目录决定，不需要再做可见性过滤。
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        if (memoryIgnored) {
            return List.of();
        }
        return longTermMemory.search(query, limit);
    }

    /** 绑定当前项目；决定长期记忆的读写落在哪个目录。 */
    public void setActiveProjectScope(String projectPath) {
        longTermMemory.setActiveProjectPath(projectPath);
    }

    public void setMemoryIgnored(boolean ignored) {
        this.memoryIgnored = ignored;
    }

    public boolean isMemoryIgnored() {
        return memoryIgnored;
    }

    /**
     * 构建用于 LLM 的长期记忆上下文。
     *
     * <p>两段：
     * <ol>
     *   <li><b>索引</b>（{@code MEMORY.md}）——总是注入。它告诉模型「有哪些记忆、每条讲什么、
     *       文件在哪」，模型可自行读取全文。先按 200 行 / 25000 字节截断，再按本轮预算裁剪。</li>
     *   <li><b>全文</b>——仅在选择器开启时，由 LLM 从候选里挑出相关条目后注入，
     *       并附上年龄与核对要求；同一会话不重复注入同一条，放不下的整条跳过。</li>
     * </ol>
     *
     * <p><b>索引与全文共享同一个预算</b>：索引自身上限（25000 字节，中文约 5500 tokens）
     * 可能远大于本轮预算（{@code ContextProfile.memoryContextTokens()}，500–5000 tokens），
     * 所以索引必须先按预算裁剪，剩余额度才给全文——否则索引会静默超出预算一个数量级。
     *
     * <p>返回值由调用方前置到<b>当轮 user 消息</b>（{@code Agent#buildTurnContext}），
     * 不注入 system prompt。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        if (memoryIgnored) {
            return "";
        }
        int safeBudget = Math.max(64, maxTokens);
        // 索引段标题也占预算，先从总额度里扣掉，再拿剩余额度裁剪索引正文。
        int indexBudget = safeBudget - MemoryEntry.estimateTokens(INDEX_HEADER);
        if (indexBudget <= 0) {
            return "";
        }
        String index = longTermMemory.indexContext(indexBudget);
        if (index.isBlank()) {
            return "";
        }
        StringBuilder context = new StringBuilder(INDEX_HEADER).append(index);
        int remaining = safeBudget - MemoryEntry.estimateTokens(context.toString())
                - MemoryEntry.estimateTokens(RELEVANT_MEMORY_HEADER);
        if (remaining <= 0) {
            return context.toString().strip();
        }
        String rendered = longTermMemory.renderSelected(
                longTermMemory.select(query, llmClient), remaining);
        if (!rendered.isBlank()) {
            context.append(RELEVANT_MEMORY_HEADER).append(rendered);
        }
        return context.toString().strip();
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
     * 渲染 SessionMemory 派生视图为当轮 user 消息段落。
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
        return maintainSessionPreSummaryForCoveredHistory(history, turnToolCalls, largestToolResultChars);
    }

    private SessionPreSummaryMaintenanceResult maintainSessionPreSummaryForCoveredHistory(
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
        String coveredFingerprint = CompactionSummaryCache.fingerprintOf(coveredMessages);
        if (isPreSummaryFailureCoolingDown(coveredFingerprint)) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_FAILURE_COOLDOWN;
        }
        if (compactionSummaryCache.findReusablePreSummary(coveredMessages, llmClient).isPresent()) {
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
                    compactionSummaryCache.findExtendablePreSummary(coveredMessages, llmClient);
            String maintenanceMode;
            int deltaMessageCount;
            int inputTokenEstimate;
            String summary;
            if (incrementalBase.isPresent()) {
                CompactionSummaryCache.PreSummary base = incrementalBase.get();
                List<LlmClient.Message> deltaMessages =
                        coveredMessages.subList(base.messageCount(), coveredMessages.size());
                maintenanceMode = "incremental";
                deltaMessageCount = deltaMessages.size();
                inputTokenEstimate = TokenBudget.estimateMessagesTokens(deltaMessages)
                        + MemoryEntry.estimateTokens(base.summary());
                if (preSummaryInputExceedsBudget(inputTokenEstimate)) {
                    recordPreSummaryFailure(coveredFingerprint, true);
                    return SessionPreSummaryMaintenanceResult.SKIPPED_INPUT_TOO_LARGE;
                }
                summary = sessionPreSummaryCompactor.summarizeIncremental(
                        base.summary(), deltaMessages);
            } else {
                maintenanceMode = "full";
                deltaMessageCount = coveredMessages.size();
                inputTokenEstimate = TokenBudget.estimateMessagesTokens(coveredMessages);
                if (preSummaryInputExceedsBudget(inputTokenEstimate)) {
                    recordPreSummaryFailure(coveredFingerprint, true);
                    return SessionPreSummaryMaintenanceResult.SKIPPED_INPUT_TOO_LARGE;
                }
                summary = sessionPreSummaryCompactor.summarizePrefix(coveredMessages);
            }
            if (summary == null || summary.isBlank()) {
                recordPreSummaryFailure(coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            RollingSummary structured = RollingSummary.parse(summary);
            if (structured.isEmpty()) {
                recordPreSummaryFailure(coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            summary = structured.render();
            CompactionSemanticGuard.Validation validation = CompactionSemanticGuard.validateAndRepair(
                    coveredMessages, summary, ConversationHistoryCompactor.MAX_SUMMARY_CHARS);
            summary = validation.repairedSummary();
            structured = RollingSummary.parse(summary);
            if (structured.isEmpty()) {
                recordPreSummaryFailure(coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            summary = structured.render();
            if (summary.length() > ConversationHistoryCompactor.MAX_SUMMARY_CHARS) {
                recordPreSummaryFailure(coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            compactionSummaryCache.recordPreSummary(coveredMessages, summary, llmClient);
            clearPreSummaryFailure();
            if ("incremental".equals(maintenanceMode)) {
                preSummaryIncrementalCount.incrementAndGet();
            } else {
                preSummaryFullCount.incrementAndGet();
            }
            lastPreSummaryMetrics = new SessionPreSummaryMetrics(
                    maintenanceMode,
                    coveredMessages.size(),
                    deltaMessageCount,
                    inputTokenEstimate,
                    summary.length(),
                    preSummaryFullCount.get(),
                    preSummaryIncrementalCount.get(),
                    preSummaryFailureCount.get(),
                    Instant.now());
            return SessionPreSummaryMaintenanceResult.MAINTAINED;
        } catch (IOException | RuntimeException e) {
            boolean deterministic = !(e instanceof com.devcli.llm.LlmException failure && failure.retryable());
            recordPreSummaryFailure(coveredFingerprint, deterministic);
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

    private boolean isPreSummaryFailureCoolingDown(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        long until = preSummaryFailureUntilMillis;
        if (until <= System.currentTimeMillis()) {
            clearPreSummaryFailure();
            return false;
        }
        return fingerprint.equals(preSummaryFailureFingerprint);
    }

    private void recordPreSummaryFailure(String fingerprint, boolean deterministic) {
        preSummaryFailureCount.incrementAndGet();
        if (deterministic && fingerprint != null && !fingerprint.isBlank()) {
            preSummaryFailureFingerprint = fingerprint;
            preSummaryFailureUntilMillis = System.currentTimeMillis()
                    + SESSION_PRE_SUMMARY_FAILURE_COOLDOWN_MILLIS;
        }
    }

    private void clearPreSummaryFailure() {
        preSummaryFailureFingerprint = "";
        preSummaryFailureUntilMillis = 0L;
    }

    private boolean preSummaryInputExceedsBudget(int inputTokenEstimate) {
        boolean exceeded = inputTokenEstimate
                > Math.max(256, contextProfile.compressionTriggerTokens());
        if (exceeded) {
            log.info("skip session pre-summary because source exceeds the local input budget");
        }
        return exceeded;
    }

    private void recordPreSummaryUsage(LlmClient.ChatResponse response) {
        if (response == null) return;
        recordTokenUsage(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        if (!Boolean.parseBoolean(System.getProperty(
                ConversationHistoryCompactor.COMPACTION_METRICS_PROPERTY, "false"))) {
            return;
        }
        System.err.printf(java.util.Locale.ROOT,
                "[context-compaction] kind=pre-summary-call inputTokens=%d outputTokens=%d cachedInputTokens=%d%n",
                response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
    }

    public CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
            List<LlmClient.Message> history,
            int turnToolCalls,
            int largestToolResultChars) {
        return maintainSessionPreSummaryAfterTurnAsync(history, turnToolCalls, largestToolResultChars,
                contextProfile.compressionTriggerTokens(), () -> true, response -> { });
    }

    public CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
            List<LlmClient.Message> history, int turnToolCalls, int largestToolResultChars,
            int triggerTokens, java.util.function.BooleanSupplier callGuard,
            java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer) {
        List<LlmClient.Message> snapshot = sessionPreSummaryCompactor.preSummaryHistory(history, triggerTokens);
        SessionPreSummaryTask task = new SessionPreSummaryTask(
                snapshot, turnToolCalls, largestToolResultChars, callGuard, usageConsumer);
        SessionPreSummaryTask superseded = null;
        synchronized (sessionPreSummaryScheduleLock) {
            if (sessionPreSummaryClosed) {
                task.complete(SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED);
                return task.result;
            }
            if (runningSessionPreSummary == null) {
                runningSessionPreSummary = task;
                sessionPreSummaryExecutor.execute(task);
            } else {
                superseded = pendingSessionPreSummary;
                pendingSessionPreSummary = task;
            }
        }
        if (superseded != null) {
            superseded.complete(SessionPreSummaryMaintenanceResult.SKIPPED_SUPERSEDED);
        }
        return task.result;
    }

    private final class SessionPreSummaryTask implements Runnable {
        private final List<LlmClient.Message> history;
        private final int turnToolCalls;
        private final int largestToolResultChars;
        private final java.util.function.BooleanSupplier callGuard;
        private final java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer;
        private final CompletableFuture<SessionPreSummaryMaintenanceResult> result =
                new CompletableFuture<>();

        private SessionPreSummaryTask(List<LlmClient.Message> history,
                                     int turnToolCalls,
                                     int largestToolResultChars,
                                     java.util.function.BooleanSupplier callGuard,
                                     java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer) {
            this.history = history;
            this.turnToolCalls = turnToolCalls;
            this.largestToolResultChars = largestToolResultChars;
            this.callGuard = callGuard;
            this.usageConsumer = usageConsumer;
        }

        @Override
        public void run() {
            try {
                sessionPreSummaryCompactor.setSummaryCallGuard(callGuard);
                sessionPreSummaryCompactor.setSummaryUsageConsumer(response -> {
                    recordPreSummaryUsage(response);
                    usageConsumer.accept(response);
                });
                complete(maintainSessionPreSummaryForCoveredHistory(
                        history, turnToolCalls, largestToolResultChars));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            } finally {
                sessionPreSummaryCompactor.setSummaryCallGuard(null);
                sessionPreSummaryCompactor.setSummaryUsageConsumer(MemoryManager.this::recordPreSummaryUsage);
                scheduleNextPreSummary(this);
            }
        }

        private void complete(SessionPreSummaryMaintenanceResult maintenanceResult) {
            result.complete(maintenanceResult);
        }
    }

    private void scheduleNextPreSummary(SessionPreSummaryTask completed) {
        synchronized (sessionPreSummaryScheduleLock) {
            if (runningSessionPreSummary != completed) {
                return;
            }
            runningSessionPreSummary = null;
            if (sessionPreSummaryClosed || pendingSessionPreSummary == null) {
                return;
            }
            runningSessionPreSummary = pendingSessionPreSummary;
            pendingSessionPreSummary = null;
            sessionPreSummaryExecutor.execute(runningSessionPreSummary);
        }
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
        if (content == null || content.isBlank()) return false;
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("忽略记忆")
                || normalized.contains("忘记记忆")
                || normalized.contains("别管记忆")
                || normalized.contains("ignore memory")
                || normalized.contains("forget memory");
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
        clearPreSummaryFailure();
        sessionEventSequence.set(0);
        memoryIgnored = false;
    }

    /** 清空当前写入作用域的长期记忆（有活动项目清项目，否则清全局）。 */
    public String clearLongTerm() {
        return clearLongTerm(null);
    }

    /**
     * 清空长期记忆。
     *
     * <p>不传作用域时只清**当前写入作用域**：全局目录跨项目共享，
     * 在项目会话里执行「清空长期记忆」不应该连带抹掉其他项目也能看到的全局记忆。
     * 需要清全局时显式传 {@code global}。
     *
     * @param scopeValue {@code global} / {@code project}；空值表示当前写入作用域
     * @return 面向用户的结果说明
     */
    public String clearLongTerm(String scopeValue) {
        MemoryScope scope;
        if (scopeValue == null || scopeValue.isBlank()) {
            scope = longTermMemory.defaultScope();
        } else {
            try {
                scope = MemoryScope.of(scopeValue);
            } catch (IllegalArgumentException e) {
                return "❌ " + e.getMessage() + "（可用 global 或 project）";
            }
        }
        java.nio.file.Path dir = scope.dir(longTermMemory.rootDir(), longTermMemory.activeProjectPath());
        if (dir == null) {
            return "❌ 未绑定项目，无法清空项目记忆；如需清全局记忆请用 /memory clear global";
        }
        int removed = longTermMemory.clearScope(scope);
        String label = scope == MemoryScope.GLOBAL ? "全局" : "项目";
        String hint = scope == MemoryScope.GLOBAL
                ? "\n   全局记忆跨项目共享；项目记忆用 /memory clear project"
                : "\n   全局记忆未受影响；如需一并清空请用 /memory clear global";
        return "🧹 已清空" + label + "长期记忆 " + removed + " 条（" + dir + "）" + hint;
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
        SKIPPED_FAILURE_COOLDOWN,
        SKIPPED_SUPERSEDED,
        FAILED
    }

    /**
     * 关闭底层记忆资源。Main 长进程不需要主动调（JVM 退出释放）；
     * 主要给单元测试用，及时释放文件句柄与后台线程，避免阻碍 @TempDir 清理。
     */
    @Override
    public void close() {
        SessionPreSummaryTask discarded;
        synchronized (sessionPreSummaryScheduleLock) {
            sessionPreSummaryClosed = true;
            discarded = pendingSessionPreSummary;
            pendingSessionPreSummary = null;
            sessionPreSummaryExecutor.shutdownNow();
        }
        if (discarded != null) {
            discarded.complete(SessionPreSummaryMaintenanceResult.SKIPPED_SUPERSEDED);
        }
        if (longTermMemory != null) {
            longTermMemory.close();
        }
    }
}
