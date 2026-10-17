package com.devcli.memory;

import java.util.stream.Collectors;
import com.devcli.context.ContextProfile;
import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static com.devcli.memory.CompactionSummarizer.*;
import static com.devcli.memory.CompactionSummaryWorkflow.*;
import static com.devcli.memory.HistoryWindow.*;

/** 对话窗口压缩入口；修改调用方提供的临时副本，由 ConversationJournal 追加投影后提交。 */
public class ConversationHistoryCompactor {
    private final CompactionSummaryWorkflow summaryWorkflow = new CompactionSummaryWorkflow(this);

    private final SummaryMessageRenderer messageRenderer = new SummaryMessageRenderer();

    static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 实验与诊断用：默认保持生产压缩行为，可显式关闭形成 raw 对照。 */

    public static final String COMPACTION_METRICS_PROPERTY = "devcli.context.compaction.metrics.enabled";

    /** 摘要消息的统一前缀，用于识别"上一轮压缩留下的摘要"。 */
    static final String SUMMARY_MARKER = "<compact_summary>\n";

    /** 旧版中文摘要前缀：仅用于向后兼容识别，不再写入。 */
    static final String LEGACY_SUMMARY_MARKER = "[已压缩的历史对话摘要]\n";

    static final String POST_COMPACT_RESTORE_MARKER = "<post_compact_restore>\n";

    static final int MAX_POST_COMPACT_RESTORE_CHARS = 8_000;

    static final String MICROCOMPACT_OUTPUTS_DIR = ".devcli/microcompact_tool_outputs";

    /** 滚动摘要的字符上限。增量摘要"只追加不删除"会让摘要单调膨胀， */
    static final int MAX_SUMMARY_CHARS = 16_000;

    /** 每完成 K 次增量压缩，执行一次确定性生命周期 GC，不再二次压缩旧摘要。 */
    static final int DEFAULT_FULL_RECOMPACT_INTERVAL = 5;

    /**
     * 连续压缩失败上限。达到后本会话停止再次尝试压缩，避免在不可恢复的窗口溢出
     * 场景下反复打爆 LLM API（参考 Claude Code 的 autocompact circuit breaker）。
     */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final long FALLBACK_COOLDOWN_MS = 300_000L; // 5 分钟

    /** 摘要调用自身 prompt-too-long 时的最大重试次数。 */
    static final int MAX_PTL_RETRIES = 3;

    /**
     * Provider 窗口小于本地估算时逐步降低摘要请求预算。
     */
    private static final double PTL_RETRY_BUDGET_RATIO = 0.75;

    /**
     * 识别 LLM 返回的"prompt too long"错误信息片段。各家 provider 错误措辞不一，
     * 列足够多关键词覆盖。匹配时大小写不敏感。
     */
    private static final String[] PTL_ERROR_KEYWORDS = {
            "prompt too long",
            "prompt is too long",
            "context length",
            "context too long",
            "context_length_exceeded",
            "exceeds maximum",
            "exceeds the maximum",
            "maximum context",
            "input is too long",
            "request too large",
            "tokens exceeds",
            "exceed the model",
    };

    LlmClient llmClient;

    int learnedSummaryInputBudget;

    private final int retainRecentTokens;

    private boolean adaptiveRetainBudget;

    private final ContextProjectionBuilder projectionBuilder = new ContextProjectionBuilder();

    /** 六段摘要的程序化垃圾回收（摘要流程优先用它裁剪，不调 LLM）。 */
    final SummaryGarbageCollector summaryGc = new SummaryGarbageCollector();

    /** Deterministic facts retained across semantic summary generations. */
    final CompactionFactLedger factLedger = new CompactionFactLedger();

    private final CompactionFactExtractor factExtractor = new CompactionFactExtractor();

    final CompactionConsistencyValidator consistencyValidator = new CompactionConsistencyValidator();

    final CompactionRepairer compactionRepairer = new CompactionRepairer();

    volatile CompactBoundarySnapshot lastBoundarySnapshot;

    Supplier<SessionMemory.SessionSnapshot> sessionSnapshotSupplier;

    private final String boundaryScopeId = UUID.randomUUID().toString();

    volatile CompactBoundarySnapshotStore boundarySnapshotStore =
            new CompactBoundarySnapshotStore(CompactionPaths.defaultBoundarySnapshotPath());

    private volatile CompactionResult lastCompactionResult =
            new CompactionResult(false, List.of(), null, List.of(), 0, 0);

    private volatile CompactionSummaryEnvelope lastSummaryEnvelope;

    private volatile CompactionContext activeCompactionContext = CompactionContext.empty();

    public CompactionFactLedger factLedger() {
        return factLedger;
    }

    public CompactionResult lastCompactionResult() {
        return lastCompactionResult;
    }

    public CompactBoundarySnapshot captureBoundarySnapshot(long historySequence) {
        return captureBoundarySnapshot(historySequence,
                sessionSnapshotSupplier == null ? null : sessionSnapshotSupplier.get());
    }

    public void setSessionSnapshotSupplier(Supplier<SessionMemory.SessionSnapshot> supplier) {
        this.sessionSnapshotSupplier = supplier;
    }

    private CompactBoundarySnapshot captureBoundarySnapshot(long historySequence,
                                                           SessionMemory.SessionSnapshot state) {
        List<CompactionFactLedger.Fact> facts = factLedger.snapshot();
        Map<String, String> decisions = new LinkedHashMap<>();
        facts.stream().filter(f -> f.type() == CompactionFactLedger.Type.USER_DECISION)
                .forEach(f -> decisions.put(f.id(), f.value()));
        return new CompactBoundarySnapshot(boundaryScopeId + "-" + historySequence, historySequence,
                facts, state == null ? List.of() : state.modifiedFiles(),
                facts.stream().filter(f -> f.type() == CompactionFactLedger.Type.UNRESOLVED_ITEM)
                        .map(CompactionFactLedger.Fact::value).toList(),
                state == null ? List.of() : state.protectedConstraints(), decisions, "",
                state == null ? Map.of() : state.workState(),
                state == null ? "" : state.taskLedger(),
                activeCompactionContext.projectId(), activeCompactionContext.sessionId(),
                activeCompactionContext.contextEpoch(), activeCompactionContext.sourceHash(),
                activeCompactionContext.sourceEventStart(), activeCompactionContext.sourceEventEnd()).sealed();
    }

    public CompactBoundarySnapshot captureBoundarySnapshot(long historySequence, SessionMemory memory) {
        return captureBoundarySnapshot(historySequence, memory == null ? null : memory.snapshot());
    }

    /** Merge structured runtime projections before scanning free-form message text. */
    private void mergeContextFacts(CompactionContext context) {
        summaryWorkflow.mergeContextFacts(context);
    }

    private CompactionSummaryCache compactionSummaryCache;

    Supplier<String> postCompactContextSupplier;

    Supplier<CompactBoundaryRuntimeState> compactBoundaryRuntimeStateSupplier;

    Supplier<CompactionSourceCursor> compactionSourceCursorSupplier;

    private Supplier<List<LlmClient.Message>> originalHistorySupplier;

    BooleanSupplier summaryCallGuard = () -> true;

    Consumer<LlmClient.ChatResponse> summaryUsageConsumer = response -> { };

    Supplier<List<LlmClient.Tool>> summaryToolsSupplier;

    Path microcompactOutputRoot;

    private MicrocompactStats lastMicrocompactStats = MicrocompactStats.empty();

    private CompactionTriggerStateStore triggerStateStore =
            new CompactionTriggerStateStore(CompactionPaths.defaultCompactionStatePath());

    public record MicrocompactStats(int beforeTokens,
                                    int afterTokens,
                                    int clearedToolResults,
                                    Map<String, Integer> removedTokensByTool,
                                    Map<String, Integer> roleTokensBefore,
                                    Map<String, Integer> roleTokensAfter) {
        public MicrocompactStats {
            removedTokensByTool = Map.copyOf(removedTokensByTool);
            roleTokensBefore = Map.copyOf(roleTokensBefore);
            roleTokensAfter = Map.copyOf(roleTokensAfter);
        }

        static MicrocompactStats empty() {
            return new MicrocompactStats(0, 0, 0, Map.of(), Map.of(), Map.of());
        }
    }

    /** Runtime 事件日志中可持久定位的摘要来源范围。 */
    public record CompactionSourceCursor(long eventStart, long eventEnd, String sourceHash,
                                         List<Long> messageEventIds,
                                         Map<String, Long> messageEventIdsByFingerprint) {
        public CompactionSourceCursor {
            eventStart = Math.max(0L, eventStart);
            eventEnd = Math.max(eventStart, eventEnd);
            sourceHash = sourceHash == null || sourceHash.isBlank() ? "none" : sourceHash.trim();
            messageEventIds = messageEventIds == null ? List.of()
                    : messageEventIds.stream().map(value -> value == null ? 0L : Math.max(0L, value)).toList();
            messageEventIdsByFingerprint = messageEventIdsByFingerprint == null ? Map.of()
                    : Map.copyOf(messageEventIdsByFingerprint);
        }

        public CompactionSourceCursor(long eventStart, long eventEnd, String sourceHash) {
            this(eventStart, eventEnd, sourceHash, List.of(), Map.of());
        }

        public CompactionSourceCursor(long eventStart, long eventEnd, String sourceHash,
                                      List<Long> messageEventIds) {
            this(eventStart, eventEnd, sourceHash, messageEventIds, Map.of());
        }

        public static CompactionSourceCursor none() {
            return new CompactionSourceCursor(0L, 0L, "none", List.of(), Map.of());
        }
    }

    /** 连续压缩失败计数。每次摘要 LLM 调用失败 / 返回空 / 找不到分割点时 +1； */
    private int consecutiveFailures = 0;

    /** 已成功完成的历史压缩次数，用于周期性生命周期 GC。 */
    private int successfulCompactions = 0;

    private int fullRecompactInterval = DEFAULT_FULL_RECOMPACT_INTERVAL;

    /**
     * 上次降级截断的时间戳（毫秒）。用于冷却期判断，避免降级循环。
     */
    private long lastFallbackTimestamp = 0;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, 1, true);
        adaptiveRetainBudget = true;
    }

    /**
     * 兼容旧调用：把"保留最近 N 轮 user"折算成"保留最近 N×1k token"。
     * 新代码请用 {@link #ConversationHistoryCompactor(LlmClient, int, boolean)}。
     */
    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRoundsLegacy) {
        this(llmClient, Math.max(1, retainRecentRoundsLegacy) * 1_000, true);
    }

    /**
     * @param retainRecentTokens 保留尾部最近 N token，按 user 边界对齐
     * @param tokensFlag         必须为 true，仅作类型区分（避免和上面 legacy 构造器签名冲突）
     */
    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentTokens, boolean tokensFlag) {
        this.llmClient = llmClient;
        // 测试场景下允许极小 retain；生产里使用方应传 ≥ 5_000 token
        this.retainRecentTokens = Math.max(1, retainRecentTokens);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        learnedSummaryInputBudget = 0;
    }

    /** 配置周期性生命周期 GC 间隔；传入 0 表示关闭周期治理。兼容保留旧方法名。 */
    public void setFullRecompactInterval(int interval) {
        this.fullRecompactInterval = Math.max(0, interval);
    }

    public void setCompactionSummaryCache(CompactionSummaryCache compactionSummaryCache) {
        this.compactionSummaryCache = compactionSummaryCache;
    }

    public void setPostCompactContextSupplier(Supplier<String> postCompactContextSupplier) {
        this.postCompactContextSupplier = postCompactContextSupplier;
    }

    public void setCompactBoundaryRuntimeStateSupplier(
            Supplier<CompactBoundaryRuntimeState> compactBoundaryRuntimeStateSupplier) {
        this.compactBoundaryRuntimeStateSupplier = compactBoundaryRuntimeStateSupplier;
    }

    public void setCompactionSourceCursorSupplier(
            Supplier<CompactionSourceCursor> compactionSourceCursorSupplier) {
        this.compactionSourceCursorSupplier = compactionSourceCursorSupplier;
    }

    public void setBoundarySnapshotPath(Path path) {
        if (path != null) {
            this.boundarySnapshotStore = new CompactBoundarySnapshotStore(path);
        }
    }

    /** 提供 Runtime 原始事件重放结果，用于周期性纠正滚动摘要。 */
    public void setOriginalHistorySupplier(Supplier<List<LlmClient.Message>> supplier) {
        this.originalHistorySupplier = supplier;
    }

    /** 将摘要调用纳入外层 Agent 的 Token 预算；不设置时保持兼容的无限制行为。 */
    public void setSummaryCallGuard(BooleanSupplier summaryCallGuard) {
        this.summaryCallGuard = summaryCallGuard == null ? () -> true : summaryCallGuard;
    }

    /** 把摘要请求的真实用量回传给外层预算和统计。 */
    public void setSummaryUsageConsumer(Consumer<LlmClient.ChatResponse> summaryUsageConsumer) {
        this.summaryUsageConsumer = summaryUsageConsumer == null ? response -> { } : summaryUsageConsumer;
    }

    public void setMicrocompactOutputRoot(Path microcompactOutputRoot) {
        this.microcompactOutputRoot = microcompactOutputRoot == null
                ? null
                : microcompactOutputRoot.toAbsolutePath().normalize();
        if (this.microcompactOutputRoot != null) {
            this.triggerStateStore = new CompactionTriggerStateStore(
                    CompactionPaths.defaultCompactionStatePath(this.microcompactOutputRoot));
        }
    }

    void setCompactionTriggerStatePath(Path statePath) {
        this.triggerStateStore = statePath == null
                ? new CompactionTriggerStateStore(CompactionPaths.defaultCompactionStatePath())
                : new CompactionTriggerStateStore(statePath);
    }

    public MicrocompactStats lastMicrocompactStats() {
        return lastMicrocompactStats;
    }

    static String microcompactSessionId() {
        return ToolResultCompactor.MICROCOMPACT_SESSION_ID;
    }

    /** Structured compaction entry point. The legacy boolean overload delegates here. */
    public CompactionResult compactIfNeeded(List<LlmClient.Message> history, CompactionContext context) {
        return compact(history, context, null);
    }

    /** 主动压缩旧轮次，最近 keepRecentTurns 个用户轮次保持原文。 */
    public CompactionResult compactNow(List<LlmClient.Message> history, CompactionContext context,
                                       int keepRecentTurns) {
        if (keepRecentTurns < 0) {
            throw new IllegalArgumentException("保留轮次必须为非负整数");
        }
        return compact(history, context, keepRecentTurns);
    }

    public void setSummaryToolsSupplier(Supplier<List<LlmClient.Tool>> supplier) {
        summaryToolsSupplier = supplier;
    }

    private CompactionResult compact(List<LlmClient.Message> history, CompactionContext context,
                                     Integer keepRecentTurns) {
        CompactionContext effective = context == null ? CompactionContext.forTrigger(Integer.MAX_VALUE) : context;
        int before = history == null ? 0 : TokenBudget.estimateMessagesTokens(history);
        CompactionResult previous = lastCompactionResult;
        // Stage all window mutations so rejected summaries leave the source intact.
        List<LlmClient.Message> candidate = history == null ? null : new ArrayList<>(history);
        boolean changed = compactIfNeededInternal(candidate, effective, keepRecentTurns);
        if (changed) {
            String projected = candidate.stream().map(m -> m.content() == null ? "" : m.content())
                    .collect(Collectors.joining("\n"));
            var coverage = consistencyValidator.validate(projected, factLedger);
            int after = TokenBudget.estimateMessagesTokens(candidate);
            if (consistencyValidator.hasBlockingMissingFacts(coverage)
                    || !fitsPostCompactionBudget(after)) {
                lastCompactionResult = new CompactionResult(false, factLedger.snapshot(), null,
                        List.of("final_coverage_or_budget_rejected"), before, before);
                return lastCompactionResult;
            }
        }
        if (changed) {
            history.clear();
            history.addAll(candidate);
            if (previous == lastCompactionResult) {
                lastCompactionResult = new CompactionResult(true, factLedger.snapshot(), null,
                        List.of("fallback_truncation"), before,
                        TokenBudget.estimateMessagesTokens(history));
            }
        } else {
            lastCompactionResult = new CompactionResult(false, List.of(), null,
                    List.of("compaction_not_applied"), before, before);
        }
        return lastCompactionResult;
    }

    private boolean fitsPostCompactionBudget(int tokens) {
        if (llmClient == null) return true;
        int window = llmClient.maxContextWindow();
        int output = Math.max(0, llmClient.maxOutputTokens());
        if (window <= 0 || window == Integer.MAX_VALUE) return true;
        return tokens + output < window;
    }

    /** Compatibility API retained for existing callers. */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        return compactIfNeeded(history, CompactionContext.forTrigger(triggerTokens)).compacted();
    }

    private boolean compactIfNeededInternal(List<LlmClient.Message> history, CompactionContext context,
                                            Integer keepRecentTurns) {
        if (history == null || history.isEmpty()) return false;
        boolean manual = keepRecentTurns != null;
        if (manual && !hasCompleteToolBatches(history)) return false;
        activeCompactionContext = enrichCompactionContext(
                context == null ? CompactionContext.empty() : context, history);
        int triggerTokens = activeCompactionContext.triggerTokens();
        int preCompactionTokens = TokenBudget.estimateMessagesTokens(history);
        boolean metrics = Boolean.parseBoolean(System.getProperty(COMPACTION_METRICS_PROPERTY, "false"));
        boolean enabled = CompactionConfiguration.isCompactionEnabled(System.getProperties(), System.getenv());
        if (metrics) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=decision enabled=%s historyTokens=%d triggerTokens=%d%n",
                    enabled, preCompactionTokens, triggerTokens);
        }
        if (!enabled && !manual) return false;
        String currentHistoryFingerprint = historyFingerprint(history);
        CompactionTriggerStateStore.State pendingTrigger = manual ? null : triggerStateStore.load().orElse(null);
        boolean crossed = preCompactionTokens >= triggerTokens;
        // 持久化触发状态只属于产生它的那份历史。项目级状态文件可能被多个
        // compactor 实例复用，不能让旧会话的 pending 标记污染新会话。
        if (pendingTrigger != null
                && !pendingTrigger.sourceHash().isBlank()
                && !currentHistoryFingerprint.equals(pendingTrigger.sourceHash())) {
            triggerStateStore.clear();
            pendingTrigger = null;
        }
        if (!manual && !crossed && pendingTrigger == null) {
            return false;
        }
        if (!manual && pendingTrigger == null) {
            pendingTrigger = triggerStateStore.begin(
                    triggerTokens, preCompactionTokens, currentHistoryFingerprint);
            if (!triggerStateStore.isDurable()) {
                log.warn("compaction trigger state could not be persisted; continuing with in-memory guard");
                if (metrics) {
                    System.err.println("[context-compaction] kind=state-error operation=begin");
                }
            }
        }
        if (metrics && crossed) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=trigger beforeTokens=%d triggerTokens=%d%n",
                    preCompactionTokens, triggerTokens);
        }

        // 保留本次压缩开始时的原始消息快照。MicroCompact 只改变模型窗口投影，
        // 语义摘要仍必须从原始工具结果取证，避免“先裁剪、再摘要”的二次损失。
        List<LlmClient.Message> summarySourceHistory = List.copyOf(history);
        // 所有出口（包括冷却期与熔断降级）共用原始事实集。
        mergeContextFacts(activeCompactionContext);

        // 历史淘汰只属于一次已经发生的触发事件。不要用淘汰后的 token 再次决定
        // 是否触发语义压缩，否则微压缩会把 crossed 状态悄悄清掉，导致模型摘要永远不执行。
        boolean microChanged = !manual && microcompactOversizeMessages(history);
        int afterEvictionTokens = TokenBudget.estimateMessagesTokens(history);
        if (metrics) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=eviction beforeTokens=%d afterTokens=%d changed=%s%n",
                    preCompactionTokens, afterEvictionTokens, microChanged);
        }

        // 检查是否在降级冷却期内
        long now = System.currentTimeMillis();
        if (!manual && lastFallbackTimestamp > 0 && (now - lastFallbackTimestamp) < FALLBACK_COOLDOWN_MS) {
            // 冷却期内不再尝试 LLM 摘要压缩，避免降级循环；
            // 但 token 再次越过阈值说明有真实新增内容，允许结构性截断兜底，
            // 否则冷却期内会裸奔撞窗口。
            if (TokenBudget.estimateMessagesTokens(history) >= triggerTokens) {
                factExtractor.extract(summarySourceHistory, factLedger, activeCompactionContext);
                boolean truncated = fallbackTruncate(history, triggerTokens);
                if (truncated) {
                    lastFallbackTimestamp = now;
                    triggerStateStore.clear();
                    if (metrics) {
                        System.err.printf(Locale.ROOT,
                                "[context-compaction] kind=fallback beforeTokens=%d triggerTokens=%d%n",
                                preCompactionTokens, triggerTokens);
                    }
                }
                return truncated;
            }
            return false;
        }

        if (!manual && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // circuit breaker 已熔断：启用降级截断策略
            log.warn("压缩连续失败 {} 次，启用降级截断策略", MAX_CONSECUTIVE_FAILURES);
            factExtractor.extract(summarySourceHistory, factLedger, activeCompactionContext);
            boolean truncated = fallbackTruncate(history, triggerTokens);
            if (truncated) {
                lastFallbackTimestamp = now;
                consecutiveFailures = 0; // 重置计数器
                triggerStateStore.clear();
                if (metrics) {
                    System.err.printf(Locale.ROOT,
                            "[context-compaction] kind=fallback beforeTokens=%d triggerTokens=%d%n",
                            preCompactionTokens, triggerTokens);
                }
            }
            return truncated;
        }
        int currentTokens = TokenBudget.estimateMessagesTokens(history);

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        // 1) token 预算保留区：从尾巴往前累计 token，落在 user 边界
        int tailBudget = summaryTailBudget(triggerTokens);
        int splitIdx = manual
                ? splitBeforeRecentTurns(summarySourceHistory, systemEnd, keepRecentTurns)
                : summarySplitIndex(summarySourceHistory, triggerTokens);
        if (splitIdx <= systemEnd) {
            log.info("compactIfNeeded skip: cannot find safe splitIdx > systemEnd={}", systemEnd);
            // 这不是 LLM 调用失败，是结构性无法压缩（如全是 system 或 retainTokens 过大）。
            // 不计入 consecutiveFailures，避免被锁死在尾部消息超大但摘要其实可用的场景。
            return false;
        }

        // 2) 识别 history 头是否已有"上一轮摘要" + 它的位置
        PreviousSummary prev = detectPreviousSummary(history, systemEnd);
        PreviousSummary summaryBase = prev;
        boolean periodicLifecycleGc = !manual && summaryBase != null
                && fullRecompactInterval > 0
                && successfulCompactions > 0
                && successfulCompactions % fullRecompactInterval == 0;

        // 3) oldMsgs：[systemEnd 之后到 splitIdx 之前] 的所有消息
        //    若有 prev 摘要，oldMsgs 包括 prev 那条 user 消息（增量摘要 prompt 会把它单独识别出来当 base）
        List<LlmClient.Message> oldMsgs = new ArrayList<>(summarySourceHistory.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;
        factExtractor.extract(oldMsgs, factLedger, activeCompactionContext);
        configureBoundarySnapshotStore(activeCompactionContext);
        lastBoundarySnapshot = captureBoundarySnapshot(summarySourceHistory.size());
        try { boundarySnapshotStore.save(lastBoundarySnapshot); }
        catch (IOException e) { log.warn("failed to persist compaction boundary snapshot", e); }
        int retainedTailTokens = estimateRangeTokens(history, splitIdx, history.size());

        // 4) 摘要：优先复用会话预摘要，否则走增量 vs 全量 Map-Reduce。
        String summary = null;
        if (!periodicLifecycleGc && compactionSummaryCache != null) {
            var reusablePreSummary = compactionSummaryCache.findReusablePreSummary(oldMsgs, llmClient);
            if (reusablePreSummary.isPresent()) {
                summary = reusablePreSummary.get().summary();
                log.info("reuse session memory pre-summary for {} old messages",
                        reusablePreSummary.get().messageCount());
            } else {
                var extendablePreSummary = compactionSummaryCache.findExtendablePreSummary(oldMsgs, llmClient);
                if (extendablePreSummary.isPresent()) {
                    int absoluteEnd = systemEnd + extendablePreSummary.get().messageCount();
                    summaryBase = new PreviousSummary(
                            systemEnd, absoluteEnd, extendablePreSummary.get().summary());
                    log.info("extend session memory pre-summary from {} to {} old messages",
                            extendablePreSummary.get().messageCount(), oldMsgs.size());
                }
            }
        }
        periodicLifecycleGc = !manual && summaryBase != null
                && fullRecompactInterval > 0
                && successfulCompactions > 0
                && successfulCompactions % fullRecompactInterval == 0;
        if (summary == null) {
            SummaryAttempt attempt = summarizeWithPtlRetry(
                    summaryBase, summarySourceHistory, splitIdx, oldMsgs, periodicLifecycleGc);
            if (attempt.terminated()) {
                // attempt 内部已经 recordFailure
                triggerStateStore.recordRetry(pendingTrigger);
                if (!triggerStateStore.isDurable()) {
                    log.warn("compaction retry state could not be persisted");
                }
                return false;
            }
            summary = attempt.summary();
        }
        summary = normalizeStructuredSummary(summary);
        if (summary == null) {
            log.warn("compaction summary protocol or protected fact validation failed");
            triggerStateStore.recordRetry(pendingTrigger);
            return false;
        }
        SummaryFinalization finalization = finalizeSummary(summary, oldMsgs, periodicLifecycleGc);
        if (finalization.rejected()) {
            triggerStateStore.recordRetry(pendingTrigger);
            return false;
        }
        summary = finalization.summary();

        // 5) 重建：[system] + [user(摘要)] + [assistant("好的")] + 保留尾部
        int originalMessages = history.size();
        int retainedMessages = history.size() - splitIdx;
        List<LlmClient.Message> systemMessages = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            systemMessages.add(history.get(i));
        }
        String restoreContext = buildPostCompactRestoreContext();
        ContextProjectionBuilder.Projection projection = projectionBuilder.project(
                systemMessages, summary, restoreContext,
                history.subList(splitIdx, history.size()));
        ContextProfile profile = ContextProfile.from(llmClient);
        int windowBudget = profile.maxContextWindow() - profile.outputReserveTokens()
                - TokenBudget.estimateToolDefinitionsTokens(summaryToolsSupplier == null ? List.of() : summaryToolsSupplier.get()) - 1;
        windowBudget = Math.min(windowBudget, currentTokens - 1);
        List<LlmClient.Message> retainedUsers = UserMessageRetention.select(oldMsgs,
                windowBudget - TokenBudget.estimateMessagesTokens(projection.messages()));
        if (!retainedUsers.isEmpty()) {
            List<LlmClient.Message> recent = new ArrayList<>(retainedUsers);
            recent.addAll(history.subList(splitIdx, history.size()));
            projection = projectionBuilder.project(systemMessages, summary, restoreContext, recent);
        }
        List<LlmClient.Message> rebuilt = new ArrayList<>(projection.messages());
        // checkpoint 只保存 system 之后的可恢复窗口；其指纹也按同一范围计算。
        String checkpointProjectionHash = ContextProjectionBuilder.fingerprintOf(
                rebuilt.stream()
                        .filter(message -> !"system".equals(message.role()))
                        .map(message -> message.withoutImageContent().withoutReasoningContent())
                        .toList());

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        CompactBoundaryRuntimeState runtimeState =
                buildCompactBoundaryRuntimeState(!restoreContext.isBlank());
        String messageSourceHash = messageRangeFingerprint(summarySourceHistory, systemEnd, splitIdx);
        CompactionSourceCursor sourceCursor = compactionSourceCursor();
        String sourceHash = sourceCursor.eventEnd() > 0 && !"none".equalsIgnoreCase(sourceCursor.sourceHash())
                ? sourceCursor.sourceHash() : messageSourceHash;
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history",
                manual ? "manual" : "token_threshold",
                periodicLifecycleGc ? "lifecycle-gc" : (summaryBase != null ? "incremental" : "full"),
                currentTokens,
                afterTokens,
                originalMessages,
                rebuilt.size(),
                retainedMessages,
                summary.length(),
                runtimeState.loadedSkills(),
                runtimeState.ragEpoch(),
                runtimeState.mcpToolSnapshot(),
                runtimeState.postCompactRestoreEnabled(),
                finalization.protectedConstraints(),
                finalization.restoredConstraints(),
                finalization.guardRepaired() ? "repaired" : "pass",
                sourceHash,
                sourceCursor.eventEnd() > 0 ? 0 : systemEnd,
                sourceCursor.eventEnd() > 0 ? 0 : splitIdx,
                checkpointProjectionHash,
                sourceCursor.eventStart(),
                sourceCursor.eventEnd(),
                boundarySnapshotStore.file().toString(),
                lastBoundarySnapshot == null ? "none" : lastBoundarySnapshot.checksum());
        rebuilt.set(systemEnd, LlmClient.Message.internalUser(
                SUMMARY_MARKER + metadata.renderBoundaryBlock() + "\n" + summary.trim()));
        if (manual && TokenBudget.estimateMessagesTokens(rebuilt) >= preCompactionTokens) {
            return false;
        }
        history.clear();
        history.addAll(rebuilt);
        int postCompactionHistoryTokens = TokenBudget.estimateMessagesTokens(history);
        lastCompactionResult = new CompactionResult(true, factLedger.snapshot(),
                lastSummaryEnvelope,
                consistencyValidator.validate(summary, factLedger).missing().stream()
                        .map(f -> "restored:" + f.id()).toList(),
                preCompactionTokens, postCompactionHistoryTokens);
        triggerStateStore.clear();
        // 成功压缩：清零失败计数，让下次失败重新累计
        if (consecutiveFailures > 0) {
            log.info("conversation compaction succeeded; reset failure counter from {}", consecutiveFailures);
            consecutiveFailures = 0;
        }
        successfulCompactions++;
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, mode=%s, summary chars %d",
                currentTokens, afterTokens, oldMsgs.size() + systemEnd, rebuilt.size(),
                periodicLifecycleGc ? "lifecycle-gc" : (summaryBase != null ? "incremental" : "full"),
                summary.length()));
        if (Boolean.parseBoolean(System.getProperty(COMPACTION_METRICS_PROPERTY, "false"))) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=history mode=%s beforeTokens=%d afterTokens=%d "
                            + "triggerTokens=%d tailBudgetTokens=%d summaryInputBudgetTokens=%d summaryTokens=%d retainedTailTokens=%d "
                            + "postCompactionHistoryTokens=%d summaryChars=%d%n",
                    periodicLifecycleGc ? "lifecycle-gc" : (summaryBase != null ? "incremental" : "full"),
                    currentTokens, afterTokens, triggerTokens, tailBudget,
                    summaryInputBudgetTokens(), MemoryEntry.estimateTokens(summary), retainedTailTokens,
                    postCompactionHistoryTokens, summary.length());
        }
        return true;
    }

    private SummaryFinalization finalizeSummary(String summary, List<LlmClient.Message> oldMsgs,
                                                boolean periodicLifecycleGc) {
        return summaryWorkflow.finalizeSummary(summary, oldMsgs, periodicLifecycleGc);
    }

    String normalizeStructuredSummary(String summary) {
        CompactionSummaryEnvelope envelope = CompactionSummaryEnvelope.parse(summary);
        if (envelope == null || !envelope.isValid()) {
            String candidate = summary == null ? "" : summary.stripLeading();
            return candidate.startsWith("{") || candidate.startsWith("[")
                    || candidate.startsWith("```json") ? null : summary;
        }
        lastSummaryEnvelope = envelope;
        // 模型只能引用原始账本中的事实，不能通过摘要创建或改写事实。
        List<CompactionFactLedger.Fact> sourceFacts = factLedger.snapshot();
        if (!sourceFacts.containsAll(envelope.protectedFacts())) return null;
        return envelope.renderMarkdown();
    }

    private CompactionSourceCursor compactionSourceCursor() {
        return summaryWorkflow.compactionSourceCursor();
    }

    private CompactionContext enrichCompactionContext(CompactionContext input,
                                                      List<LlmClient.Message> history) {
        return summaryWorkflow.enrichCompactionContext(input, history);
    }

    private void configureBoundarySnapshotStore(CompactionContext context) {
        summaryWorkflow.configureBoundarySnapshotStore(context);
    }

    private CompactBoundaryRuntimeState buildCompactBoundaryRuntimeState(boolean hasPostCompactRestoreContext) {
        return summaryWorkflow.buildCompactBoundaryRuntimeState(hasPostCompactRestoreContext);
    }

    String buildPostCompactRestoreContext() {
        return summaryWorkflow.buildPostCompactRestoreContext();
    }

    /** 带 PTL retry 的摘要包装。 */
    private SummaryAttempt summarizeWithPtlRetry(PreviousSummary prev,
                                                  List<LlmClient.Message> summarySourceHistory,
                                                  int splitIdx,
                                                  List<LlmClient.Message> oldMsgs,
                                                  boolean periodicRebuild) {
        // 决定增量 vs 全量路径
        List<LlmClient.Message> rebuiltSource = periodicRebuild ? originalHistory() : List.of();
        boolean incremental = prev != null && rebuiltSource.isEmpty();
        List<LlmClient.Message> currentMsgs;
        if (periodicRebuild && !rebuiltSource.isEmpty()) {
            currentMsgs = rebuiltSource;
        } else if (incremental) {
            currentMsgs = new ArrayList<>(summarySourceHistory.subList(prev.endIdx(), splitIdx));
            if (currentMsgs.isEmpty()) {
                log.info("compactIfNeeded skip: previous summary present but no new messages between summary and splitIdx");
                // 这是结构性 noop，不计入失败
                return SummaryAttempt.giveUpWithoutRecord();
            }
        } else {
            currentMsgs = oldMsgs;
        }

        int ptlAttempts = 0;
        while (true) {
            try {
                String summary = !incremental && !periodicRebuild && ptlAttempts == 0
                        ? summarizeWithConversationPrefix(summarySourceHistory,
                                splitIdx)
                        : null;
                if (summary == null) {
                    summary = incremental
                            ? summarizeIncremental(prev.summaryText(), currentMsgs)
                            : summarize(currentMsgs);
                }
                if (summary == null || summary.isBlank()) {
                    log.warn("conversation summary returned empty; skip compaction");
                    recordFailure("empty_summary");
                    return SummaryAttempt.giveUp();
                }
                if (ptlAttempts > 0) {
                    log.info("conversation summary recovered after {} PTL retries", ptlAttempts);
                }
                return SummaryAttempt.ok(summary);
            } catch (IOException e) {
                if (!isPromptTooLongError(e)) {
                    // 其它 IO 错误（network / auth / 5xx）：直接走 circuit breaker
                    log.warn("conversation summary LLM call failed (non-PTL); skip compaction", e);
                    recordFailure("io_exception");
                    return SummaryAttempt.giveUp();
                }

                ptlAttempts++;
                if (ptlAttempts > MAX_PTL_RETRIES) {
                    log.warn("conversation summary still PTL after {} retries; give up and trip failure counter",
                            MAX_PTL_RETRIES, e);
                    recordFailure("ptl_exhausted");
                    return SummaryAttempt.giveUp();
                }

                int reducedBudget = (int) (summaryInputBudgetTokens() * PTL_RETRY_BUDGET_RATIO);
                if (reducedBudget < 1) {
                    recordFailure("ptl_budget_exhausted");
                    return SummaryAttempt.giveUp();
                }
                learnedSummaryInputBudget = reducedBudget;
                log.info("conversation summary PTL on attempt {}/{}: request budget reduced to {}, retaining all {} messages",
                        ptlAttempts, MAX_PTL_RETRIES, reducedBudget, currentMsgs.size());
            }
        }
    }

    private List<LlmClient.Message> originalHistory() {
        if (originalHistorySupplier == null) return List.of();
        try {
            List<LlmClient.Message> value = originalHistorySupplier.get();
            return value == null ? List.of() : value.stream().filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("original event history supplier failed; keep rolling summary", e);
            return List.of();
        }
    }

    /**
     * 判断 IOException 是否来自"prompt too long"语义。
     * 各家 provider 错误措辞不一，按关键词列表大小写不敏感匹配。
     */
    static boolean isPromptTooLongError(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                for (String kw : PTL_ERROR_KEYWORDS) {
                    if (lower.contains(kw)) return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 摘要尝试结果。terminated=true 时调用方应当结束本次 compactIfNeeded。 */
    private record SummaryAttempt(String summary, boolean terminated) {
        static SummaryAttempt ok(String s) { return new SummaryAttempt(s, false); }
        static SummaryAttempt giveUp() { return new SummaryAttempt(null, true); }
        /** 结构性放弃（不计失败计数）—— 当前调用方接到 terminated 直接 return false。 */
        static SummaryAttempt giveUpWithoutRecord() { return new SummaryAttempt(null, true); }
    }

    /**
     * 记录一次压缩失败，并在达到上限时打印 circuit breaker tripped 日志。
     */
    private void recordFailure(String reason) {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            log.warn("conversation compaction circuit breaker tripped after {} consecutive failures (last reason={}); subsequent compactIfNeeded calls will short-circuit until manual reset",
                    consecutiveFailures, reason);
        } else {
            log.info("conversation compaction failure {} of {} (reason={})",
                    consecutiveFailures, MAX_CONSECUTIVE_FAILURES, reason);
        }
    }

    /**
     * 当前连续失败计数。供测试和 /memory status 等命令查询用。
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * 是否已熔断。
     */
    public boolean isCircuitOpen() {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }

    /**
     * 重置 circuit breaker。供用户手动 /memory reset 或新会话开始时调用。
     */
    public void resetCircuitBreaker() {
        if (consecutiveFailures > 0) {
            log.info("conversation compaction circuit breaker manually reset (was {} failures)", consecutiveFailures);
            consecutiveFailures = 0;
        }
    }

    /** 从尾巴往前累计 token，找到第一个让累计 ≥ retainTokens 的 user 边界作为 splitIdx。 */
    int summarySplitIndex(List<LlmClient.Message> history, int triggerTokens) {
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        int tailBudget = summaryTailBudget(triggerTokens);
        int split = findSplitIdxByTokenBudget(history, systemEnd, tailBudget);
        split = fitRecentTailWithinTokenBudget(history, systemEnd, split, tailBudget);
        return split > systemEnd ? split : findSplitIdxByCompletedToolBatch(history, systemEnd, tailBudget);
    }

    private int summaryTailBudget(int triggerTokens) {
        return adaptiveRetainBudget
                ? Math.min(retainRecentTokens(), Math.max(1, triggerTokens / 2)) : retainRecentTokens;
    }

    public List<LlmClient.Message> preSummaryHistory(List<LlmClient.Message> history, int triggerTokens) {
        return summaryWorkflow.preSummaryHistory(history, triggerTokens);
    }

    String summarizePrefix(List<LlmClient.Message> messages) throws IOException {
        int systemEnd = !messages.isEmpty() && "system".equals(messages.get(0).role()) ? 1 : 0;
        PreviousSummary previous = detectPreviousSummary(messages, systemEnd);
        if (previous == null) {
            String cachedPrefix = summarizeWithConversationPrefix(messages, messages.size());
            return cachedPrefix != null ? cachedPrefix : summarize(messages.subList(systemEnd, messages.size()));
        }
        List<LlmClient.Message> delta = messages.subList(previous.endIdx(), messages.size());
        return delta.isEmpty() ? previous.summaryText() : summarizeIncremental(previous.summaryText(), delta);
    }

    private String summarizeWithConversationPrefix(List<LlmClient.Message> history, int end) throws IOException {
        return summaryWorkflow.summarizeWithConversationPrefix(history, end);
    }

    String structuredSnapshot(String system, String prompt) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String response = chatOnce(system, prompt);
            CompactionSummaryEnvelope envelope = CompactionSummaryEnvelope.parse(response);
            if (envelope != null && envelope.isValid()) {
                return normalizeStructuredSummary(response);
            }
            // 保留旧版摘要客户端的六段 Markdown 兼容性：历史测试、旧 provider
            // 以及已落盘的预摘要仍可能返回 Markdown，但不接受任意纯文本或 JSON。
            if (isLegacyMarkdownSummary(response)) {
                return response;
            }
            // 有界重试复用来源，不把损坏的模型输出作为事实再次输入。
        }
        throw new IOException("Structured summary protocol rejected after repair; original history retained");
    }

    private static boolean isLegacyMarkdownSummary(String response) {
        return CompactionSummaryWorkflow.isLegacyMarkdownSummary(response);
    }

    private int summaryInputBudgetTokens() {
        return summaryWorkflow.summaryInputBudgetTokens();
    }

    String chatOnce(String systemPrompt, String userPrompt) throws IOException {
        return summaryWorkflow.chatOnce(systemPrompt, userPrompt);
    }

    public int retainRecentTokens() {
        return adaptiveRetainBudget
                ? ContextProfile.from(llmClient).compactionTailBudgetTokens()
                : retainRecentTokens;
    }

    boolean microcompactOversizeMessages(List<LlmClient.Message> history) {
        lastMicrocompactStats = new ToolResultCompactor(microcompactOutputRoot, retainRecentTokens).compact(history);
        return lastMicrocompactStats.afterTokens() < lastMicrocompactStats.beforeTokens();
    }

    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        return summarizer().summarize(messages);
    }

    protected String summarizeIncremental(String previousSummary, List<LlmClient.Message> newMessages) throws IOException {
        return summarizer().summarizeIncremental(previousSummary, newMessages);
    }

    private CompactionSummarizer summarizer() {
        return new CompactionSummarizer(llmClient, summaryInputBudgetTokens(), this, messageRenderer);
    }

    private boolean fallbackTruncate(List<LlmClient.Message> history, int triggerTokens) {
        return new HistoryFallback(this).fallbackTruncate(history, triggerTokens);
    }

    HistoryFallback.Boundary prepareFallbackBoundary(int messageCount) {
        configureBoundarySnapshotStore(activeCompactionContext);
        lastBoundarySnapshot = captureBoundarySnapshot(messageCount);
        try {
            boundarySnapshotStore.save(lastBoundarySnapshot);
        } catch (IOException e) {
            log.warn("failed to persist fallback compaction boundary snapshot", e);
        }
        return new HistoryFallback.Boundary(compactionSourceCursor(), boundarySnapshotStore.file().toString(), lastBoundarySnapshot.checksum());
    }

    String repairFallbackProjection(String projection) {
        return compactionRepairer.repair("", consistencyValidator.validate(projection, factLedger));
    }
}
