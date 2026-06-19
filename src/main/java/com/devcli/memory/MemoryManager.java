package com.devcli.memory;

import com.devcli.llm.LlmClient;
import com.devcli.context.ContextProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 管理器 —— Memory 系统的门面类。
 *
 * <p>v2 重构（路径 B）：把旧版"短期记忆笔记本"重定位为 {@link WorkingMemory}（工作记忆）。
 *
 * <p>四层职责切片（不重叠）：
 * <ol>
 *   <li><b>Conversation History</b>（不在本类管理）：真实 LLM messages，
 *       {@code Agent.conversationHistory} 维护，{@code ConversationHistoryCompactor} 压缩</li>
 *   <li><b>Working Memory</b>（本类持有）：当前会话工作状态（最近工具证据 / 任务状态 / 临时事实），
 *       作为 system prompt <b>派生视图</b>注入，不进 messages。仅当前会话有效</li>
 *   <li><b>Long-Term Memory</b>（本类持有）：跨会话持久化事实，按 query 检索 top-k 注入</li>
 *   <li><b>Sticky Memory</b>（{@code StickyMemory} 单独管理）：跨会话持久化强约束，每轮全量注入</li>
 * </ol>
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
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private final WorkingMemory workingMemory;
    private final SessionMemory sessionMemory;
    private final LongTermMemory longTermMemory;
    private final MemoryRetriever retriever;
    // Bug #12 修复：使用 ConcurrentHashMap 支持 Multi-Agent 并发调用
    private final Map<String, Integer> memoryCandidateOccurrences = new java.util.concurrent.ConcurrentHashMap<>();
    /** recurrence 候选计数器的容量上限，防止长会话下无界增长。 */
    private static final int MAX_MEMORY_CANDIDATE_ENTRIES = 512;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /**
     * @param llmClient      LLM 客户端（v2 不再需要——压缩走 ConversationHistoryCompactor，留参数兼容旧测试）
     * @param shortTermBudget 历史参数名，v2 已迁移到 WorkingMemory，仅用于设置 ContextProfile.shortTermMemoryBudget
     * @param contextWindow  模型上下文窗口大小
     */
    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow) {
        this(llmClient, shortTermBudget, contextWindow, null);
    }

    public MemoryManager(LlmClient llmClient, int shortTermBudget, int contextWindow, LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow, shortTermBudget), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.workingMemory = new WorkingMemory();
        this.sessionMemory = new SessionMemory();
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.retriever = new MemoryRetriever(this.longTermMemory);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    public void setLlmClient(LlmClient llmClient) {
        applyContextProfile(ContextProfile.from(llmClient));
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    // ─────────────────────────────────────────────────────────
    // 写入 WorkingMemory（不进 LLM messages，作为 system prompt 派生视图）
    // ─────────────────────────────────────────────────────────

    /**
     * 添加用户消息——v2 不再写笔记本，仅添加为 volatile fact 标注「最近一次用户输入」便于
     * LLM 在长会话里识别用户最新请求。Conversation History 由 Agent 直接维护。
     */
    public void addUserMessage(String content) {
        if (content == null || content.isBlank()) return;
        // 取首 60 字符做 fact，避免 prompt 膨胀
        String preview = content.length() > 60 ? content.substring(0, 60) + "..." : content;
        workingMemory.addVolatileFact("用户最新输入: " + preview);
        maybePersistUserFact(content);
    }

    /**
     * 添加助手回复——v2 不再写笔记本。conversationHistory 已经是真实记录。
     * 保留方法签名是为了兼容 Agent / SubAgent 的调用约定。
     */
    public void addAssistantMessage(String content) {
        // no-op：assistant 内容已在 conversationHistory 里，重复存到 working memory 没有用
    }

    /**
     * 添加工具执行结果到 WorkingMemory.recentToolResults。
     * 注意：完整 result 不再截断到 500 字符；摘要不会保留的精确实体（路径/数字/错误码）
     * 在这里以原文形式保留，作为 system prompt "## 最近工具调用证据" 段注入 LLM。
     */
    public void addToolResult(String toolName, String result) {
        addToolResult(toolName, "", result);
    }

    /**
     * 带 args 的版本：让 LLM 能识别"刚刚 read_file 读的是哪个路径"。
     */
    public void addToolResult(String toolName, String argsJson, String result) {
        if (toolName == null || result == null) return;
        workingMemory.recordToolResult(toolName, argsJson, result);
    }

    /** 设置任务状态（plan_task / react_iteration / last_error 等）。 */
    public void setTaskState(String key, String value) {
        workingMemory.setTaskState(key, value);
    }

    // ─────────────────────────────────────────────────────────
    // TaskLedger（计划执行进度投影，注入 working memory 段）
    // ─────────────────────────────────────────────────────────

    /** 设置当前计划及全部步骤（PlanExecuteAgent 在计划创建后调用，覆盖旧账本）。 */
    public void setTaskLedgerPlan(String planId, String goal, Map<String, String> stepIdToDesc) {
        workingMemory.taskLedger().setPlan(planId, goal, stepIdToDesc);
    }

    /** 标记步骤开始执行。 */
    public void startTaskStep(String stepId) {
        workingMemory.taskLedger().startStep(stepId);
    }

    /** 标记步骤完成。 */
    public void completeTaskStep(String stepId) {
        workingMemory.taskLedger().completeStep(stepId);
    }

    /** 标记步骤失败并记录错误。 */
    public void failTaskStep(String stepId, String error) {
        workingMemory.taskLedger().failStep(stepId, error);
    }

    /** 添加一条本会话临时事实。 */
    public void addVolatileFact(String fact) {
        workingMemory.addVolatileFact(fact);
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
            return new StoreResult(false, decision, "长期记忆策略" + switch (decision.action()) {
                case CONFIRM -> "需要确认: " + decision.reason();
                case SKIP -> "跳过: " + decision.reason();
                case SAVE -> "允许保存";
            });
        }
        storeFact(fact, decision.metadata());
        return new StoreResult(true, decision, "已保存到长期记忆");
    }

    private void maybePersistUserFact(String content) {
        String candidate = normalizeMemoryCandidate(content);
        if (candidate.isBlank()) {
            return;
        }
        // 进程内计数器防泄漏：超过上限直接清空重新统计。
        // recurrence 本就不跨会话持久化，清空只是重置单会话内的重复计数，影响可接受。
        if (memoryCandidateOccurrences.size() > MAX_MEMORY_CANDIDATE_ENTRIES) {
            memoryCandidateOccurrences.clear();
            log.debug("memoryCandidateOccurrences exceeded {} entries; reset recurrence counters",
                    MAX_MEMORY_CANDIDATE_ENTRIES);
        }
        int recurrence = memoryCandidateOccurrences.merge(candidate, 1, Integer::sum);
        LongTermMemoryPolicy.Decision decision = LongTermMemoryPolicy.evaluate(candidate, recurrence, false);
        if (decision.action() == LongTermMemoryPolicy.Action.SAVE
                && longTermMemory.search(candidate, 1).stream().noneMatch(e -> e.getContent().equals(candidate))) {
            storeFact(candidate, decision.metadata());
        }
    }

    private String normalizeMemoryCandidate(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim().replaceAll("\\s+", " ");
        if (normalized.length() > 200) {
            return "";
        }
        return normalized;
    }

    private void storeFact(String fact, Map<String, String> metadata) {
        Map<String, String> effectiveMetadata =
                metadata == null || metadata.isEmpty() ? Map.of("source", "fact") : metadata;
        String subject = MemorySubjectExtractor.extract(fact, effectiveMetadata);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                Instant.now(),
                effectiveMetadata,
                MemoryEntry.estimateTokens(fact),
                subject,
                true,
                ""
        );
        if (subject.isBlank()) {
            longTermMemory.store(entry);               // 无法确定主题：追加，不覆盖
        } else {
            longTermMemory.storeWithSubject(entry);    // 同主题旧事实被 supersede
        }
    }

    public record StoreResult(boolean stored, LongTermMemoryPolicy.Decision decision, String message) {}

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
            sb.append("- id=").append(entry.getId())
                    .append(", type=").append(entry.getType());
            if (!entry.getSubject().isBlank()) {
                sb.append(", subject=").append(entry.getSubject());
            }
            if (!entry.isActive()) {
                sb.append(", active=false, superseded_by=").append(entry.getSupersededBy());
            }
            sb.append(", created_at=").append(entry.getTimestamp())
                    .append("\n  content: ").append(entry.getContent());
            if (!entry.getMetadata().isEmpty()) {
                sb.append("\n  metadata: ").append(entry.getMetadata());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────
    // 读取（注入到 system prompt）
    // ─────────────────────────────────────────────────────────

    /**
     * 检索与 query 最相关的长期记忆。短期记忆走 {@link WorkingMemory#renderForPrompt()}
     * 直接注入，不参与 query-based 检索。
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieveLongTerm(query, limit);
    }

    /**
     * 构建用于 LLM 的长期记忆上下文（按 query 检索 top-k）。
     */
    public String buildContextForQuery(String query, int maxTokens) {
        int safeBudget = Math.max(64, maxTokens);
        String inventory = buildLongTermMemoryInventorySnapshot(5, Math.min(256, safeBudget));
        int relevantBudget = Math.max(0, safeBudget - MemoryEntry.estimateTokens(inventory));
        String relevant = relevantBudget == 0 ? "" : retriever.buildContextForQuery(query, relevantBudget);
        if (relevant.isBlank()) {
            return inventory;
        }
        return inventory + "\n\n" + relevant.trim();
    }

    private String buildLongTermMemoryInventorySnapshot(int limit, int maxTokens) {
        List<MemoryEntry> activeEntries = longTermMemory.getAll().stream()
                .filter(MemoryEntry::isActive)
                .sorted(java.util.Comparator.comparing(MemoryEntry::getTimestamp).reversed())
                .toList();
        int total = activeEntries.size();
        if (total == 0) {
            return "## 长期记忆索引快照\n\n- total: 0\n- 当前持久化长期记忆为空。";
        }
        StringBuilder context = new StringBuilder("## 长期记忆索引快照\n\n");
        context.append("- total: ").append(total).append('\n');
        context.append("- 说明: 这是持久化长期记忆的轻量目录；用户要求完整查看或审计时调用 list_memory。\n");
        List<MemoryEntry> entries = activeEntries.stream()
                .limit(Math.max(1, limit))
                .toList();
        int usedTokens = MemoryEntry.estimateTokens(context.toString());
        for (MemoryEntry entry : entries) {
            String line = "- [" + entry.getType() + "] " + truncateForPrompt(entry.getContent(), 120) + "\n";
            int lineTokens = MemoryEntry.estimateTokens(line);
            if (usedTokens + lineTokens > maxTokens && usedTokens > 0) {
                context.append("- ...\n");
                break;
            }
            context.append(line);
            usedTokens += lineTokens;
        }
        return context.toString().trim();
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
     * 渲染 working memory 派生视图为 system prompt 段落。
     * Agent / PlanExecuteAgent / SubAgent 通过这条路径把工作记忆注入给 LLM。
     */
    public String buildWorkingMemorySection() {
        return workingMemory.renderForPrompt();
    }

    /**
     * 为 Multi-Agent 角色构建隔离后的工作记忆视图。
     *
     * Planner 只需要任务状态和关键事件，避免被 Worker 的工具原文证据污染；
     * Worker 需要完整执行上下文；
     * Reviewer 聚焦任务状态和工具证据，避免把会话事件当成验收证据。
     */
    public String buildWorkingMemorySectionForAgent(String agentType) {
        return workingMemory.renderForPrompt(viewForAgent(agentType));
    }

    private static WorkingMemory.View viewForAgent(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return WorkingMemory.View.FULL;
        }
        String normalized = agentType.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("planner")) {
            return WorkingMemory.View.PLANNER;
        }
        if (normalized.contains("reviewer")) {
            return WorkingMemory.View.REVIEWER;
        }
        if (normalized.contains("worker")) {
            return WorkingMemory.View.WORKER;
        }
        return WorkingMemory.View.FULL;
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
        workingMemory.clear();
        sessionMemory.clearPreSummary();
    }

    /** 清空长期记忆（用于 /memory clear 命令）。 */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                workingMemory.getStatusSummary() + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    // ─────────────────────────────────────────────────────────
    // Getter
    // ─────────────────────────────────────────────────────────

    public WorkingMemory getWorkingMemory() { return workingMemory; }
    public SessionMemory getSessionMemory() { return sessionMemory; }

    /**
     * @deprecated v2 重构后短期记忆已升级为 {@link WorkingMemory}；保留旧方法名兼容老测试。
     *             调用方应迁移到 {@link #getWorkingMemory()}。
     */
    @Deprecated
    public WorkingMemory getShortTermMemory() { return workingMemory; }

    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public MemoryRetriever getRetriever() { return retriever; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }

    /**
     * 关闭底层记忆资源。Main 长进程不需要主动调（JVM 退出释放）；
     * 主要给单元测试用，避免 SQLite 文件锁阻碍 @TempDir 清理。
     */
    @Override
    public void close() {
        if (longTermMemory != null) {
            longTermMemory.close();
        }
    }
}
