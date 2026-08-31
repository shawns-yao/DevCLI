package com.devcli.memory;

import com.devcli.context.ContextProfile;
import com.devcli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 压缩 ReAct 主循环里的 {@code conversationHistory}（即 {@code List<LlmClient.Message>}）。
 *
 * <p>v3 重构（路径 B）：旧版本曾与 {@code ContextCompressor + ConversationMemory} 双轨并存，
 * 后者只压旁路笔记本不影响 LLM 输入，已删除。本类是真正治理 LLM 输入窗口的唯一压缩点。
 *
 * <p>第 0 层 microcompact：在任何 LLM 摘要之前，只回收已经离开最近保护区的旧工具结果，
 * 不调用 LLM，也不修改 user / assistant 消息。完整工具结果落盘，并在上下文中保留项目相对路径。
 *
 * 算法：
 * 1. 估算 conversationHistory 当前 token，未达 trigger 直接返回 false
 * 2. <b>token 预算保留区</b>：从尾巴往前累计 token，到 retainRecentTokens 时停在
 *    最近的 user 消息边界，作为 splitIdx
 * 3. <b>增量摘要</b>：识别 history 头部是否已有"上一轮摘要"标记
 *    （首条 user 内容以 {@link #SUMMARY_MARKER} 开头），如有则只把"上次摘要之后到 splitIdx 之间"
 *    的新消息送 LLM，老摘要作为 base 并入；如无则走 Map-Reduce 全量摘要
 * 4. 重建：[system] + [user("[已压缩的历史对话摘要]\n" + summary)] +
 *         [assistant("好的，已了解上下文。请继续。")] + [尾部保留消息]
 *
 * 关键约束：分割点必然落在 user message 边界，避免切断 tool_call / tool_result 的成对协议。
 *
 * 摘要算法选型：
 * - 历史首次压缩时使用 Map-Reduce（整段历史进 LLM 视野，不 first-N 截断）
 * - 后续压缩使用增量更新（基于上轮摘要 + 仅新增消息），避免摘要套娃稀释老事实
 * - first-N 字符截断在多轮压缩下信息保留率会塌到 16% 量级（实测）
 * - 摘要输出为固定六段结构化（{@link RollingSummary}）；任务状态不进入摘要；
 *   超长时先由 {@link SummaryGarbageCollector} 程序化按段裁剪（不调 LLM），不够再 LLM recompress 兜底
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    /** 实验与诊断用：默认保持生产压缩行为，可显式关闭形成 raw 对照。 */
    public static final String COMPACTION_ENABLED_PROPERTY = "devcli.context.compaction.enabled";
    public static final String COMPACTION_ENABLED_ENV = "DEVCLI_CONTEXT_COMPACTION_ENABLED";
    public static final String COMPACTION_METRICS_PROPERTY = "devcli.context.compaction.metrics.enabled";

    /**
     * 老阈值参数，向后兼容字段名（虽然语义改了）。当通过旧构造器
     * {@code ConversationHistoryCompactor(llm, n)} 传入时，会按 "n × 1k token" 折算成
     * retainRecentTokens（每个 user 轮约 1k token 是个粗估）。
     */
    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final double DEFAULT_RETAIN_WINDOW_RATIO = 0.15;

    // ── microcompact（第 0 层）：旧工具结果 GC，不调 LLM ──
    static final int MICRO_COMPACT_RETAIN_RECENT_TOOL_RESULTS = 4;
    static final String MICRO_COMPACT_KEEP_RECENT_PROPERTY =
            "devcli.context.microcompact.keep.recent.tool.results";
    static final String MICRO_COMPACT_KEEP_RECENT_ENV =
            "DEVCLI_CONTEXT_MICROCOMPACT_KEEP_RECENT_TOOL_RESULTS";
    static final String MICRO_COMPACT_EXCLUDE_TOOLS_PROPERTY =
            "devcli.context.microcompact.exclude.tools";
    static final String MICRO_COMPACT_EXCLUDE_TOOLS_ENV =
            "DEVCLI_CONTEXT_MICROCOMPACT_EXCLUDE_TOOLS";

    /**
     * Reduce 阶段最多合并多少片摘要。如果片数 > 此值，会先做"二次 Map"
     * （每 N 片合并成一段中间摘要），再 Reduce 最终。防止 Reduce prompt 自己撑爆 window。
     */
    private static final int MAX_REDUCE_FANIN = 8;
    /**
     * 摘要消息的统一前缀，用于识别"上一轮压缩留下的摘要"。
     *
     * <p>使用语言无关的结构化标记（与 {@code <compact_boundary>} /
     * {@code <microcompact_boundary>} 同一约定）：协议识别不应依赖中文散文,
     * 否则换模型或模型复述该句时识别会失效。旧版中文标记仅保留用于
     * 识别历史会话与已持久化检查点（见 {@link #LEGACY_SUMMARY_MARKER}），新写入一律用结构化标记。
     */
    static final String SUMMARY_MARKER = "<compact_summary>\n";
    /** 旧版中文摘要前缀：仅用于向后兼容识别，不再写入。 */
    static final String LEGACY_SUMMARY_MARKER = "[已压缩的历史对话摘要]\n";
    static final String POST_COMPACT_RESTORE_MARKER = "<post_compact_restore>\n";
    private static final int MAX_POST_COMPACT_RESTORE_CHARS = 8_000;
    static final String MICROCOMPACT_OUTPUTS_DIR = ".devcli/microcompact_tool_outputs";
    private static final DateTimeFormatter MICROCOMPACT_SESSION_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());
    private static final String MICROCOMPACT_SESSION_ID =
            MICROCOMPACT_SESSION_ID_FMT.format(Instant.now());

    /**
     * 滚动摘要的字符上限。增量摘要"只追加不删除"会让摘要单调膨胀，
     * 超过此上限时优先执行确定性生命周期 GC。结构化六段摘要不再交给 LLM 二次改写，
     * 避免稳定决策在反复摘要中漂移。
     */
    static final int MAX_SUMMARY_CHARS = 16_000;
    /** 每完成 K 次增量压缩，执行一次确定性生命周期 GC，不再二次压缩旧摘要。 */
    static final int DEFAULT_FULL_RECOMPACT_INTERVAL = 5;

    /**
     * 连续压缩失败上限。达到后本会话停止再次尝试压缩，避免在不可恢复的窗口溢出
     * 场景下反复打爆 LLM API（参考 Claude Code 的 autocompact circuit breaker）。
     */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * 降级截断后的冷却时间（毫秒）。在此时间内不再尝试任何压缩操作，避免降级循环。
     */
    private static final long FALLBACK_COOLDOWN_MS = 300_000L; // 5 分钟

    /**
     * 摘要调用自身 prompt-too-long 时的最大重试次数。
     * 每次重试收紧单次请求预算，重新分片全部原始消息，不删除历史。
     * 超过仍然 PTL 才计入 {@link #consecutiveFailures}。
     */
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

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成结构化摘要，严格按以下六个 Markdown 段落输出（标题用 ## 开头，无内容写"无"）：

            ## 主要请求与意图
            ## 关键技术概念
            ## 文件和代码
            ## 踩过的坑和修复
            ## 问题解决过程
            ## 逐条用户消息

            要求：任务状态、待办事项和下一步由 Session Memory 提供，不写入摘要；
            精确实体（文件名/路径/数字/错误码）保留原文；决策被覆盖时只保留最终值；
            "逐条用户消息"按时间列每条用户消息的要点（不复述全文）；不保留过渡话术；不加段落外的前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private static final String MAP_PROMPT = """
            下面是一段长对话历史的【片段 %d / %d】。请只对本片段做摘要，保留：
            1. 用户在本片段中的具体诉求
            2. Agent 在本片段中已完成的关键工具调用与结果
            3. 本片段中提到的精确实体（文件名、路径、数字常量、错误码、配置值）必须保留原文
            4. 本片段中达成或修改的决策

            不要复述每条原文，不要发明片段外的信息。输出 2-4 段中文，不加前缀。

            === 片段开始 ===
            %s
            === 片段结束 ===
            """;

    private static final String REDUCE_PROMPT = """
            下面是一段长对话被切成多片后各自的摘要。请合并成一份完整摘要，严格按以下六个 Markdown 段落输出（标题用 ## 开头，无内容写"无"）：

            ## 主要请求与意图
            ## 关键技术概念
            ## 文件和代码
            ## 踩过的坑和修复
            ## 问题解决过程
            ## 逐条用户消息

            要求：任务状态、待办事项和下一步由 Session Memory 提供，不写入摘要；
            所有片段里的精确实体（文件名/路径/数字/错误码）必须以原文出现；决策被覆盖（先 A 后 B 最终 C）只保留"最终是 C"；不遗漏任何片段事实；不加段落外前缀。

            === 各片段摘要 ===
            %s
            === 各片段摘要结束 ===
            """;

    private static final String INCREMENTAL_PROMPT = """
            你在维护一份固定六段式滚动摘要。不要重写完整摘要，只输出 JSON 变更操作：
            {"operations":[{"action":"ADD|UPDATE|RESOLVE|SUPERSEDE|EXPIRE|DELETE",
            "section":"六段标题之一","target_section":"可选六段标题","subject":"稳定主题键",
            "content":"新增或最终事实","lifecycle":"STABLE|ACTIVE|UNRESOLVED|RESOLVED",
            "importance":0-100,"evidence_refs":["工具或消息引用"]}]}

            规则：
            1. 六段标题保持不变，生命周期只是事实元数据，不新增段落；任务状态不进入摘要。
            2. 新事实用 ADD；同主题最终值变化用 UPDATE，且必须原样复用已有元数据中的 subject；任务完成用 RESOLVE 并写入最终结果。
            3. 已被覆盖用 SUPERSEDE，暂时失效用 EXPIRE，确定无审计价值才用 DELETE。
            4. 保留仍有效的决策、未完成事项、当前阻塞、精确实体和证据引用。
            5. 只输出一个 JSON 对象，不输出 Markdown、解释或代码围栏。

            === 已有摘要（六段） ===
            %s
            === 已有摘要（结束） ===

            === 新增对话 ===
            %s
            === 新增对话（结束） ===
            """;

    private static final String RECOMPRESS_PROMPT = """
            下面这份滚动摘要已经过长。请把它压缩到大约 %d 字以内，规则：
            1. 保留所有仍然有效的最终决策、约束和用户偏好
            2. 精确实体（文件名、路径、数字常量、错误码、配置值）必须保留原文
            3. 合并重复信息，删除已被覆盖的中间状态，只保留"最终值"
            4. 输出压缩后的完整摘要，不加任何前缀或元描述

            === 待再压缩的摘要 ===
            %s
            === 待再压缩的摘要（结束） ===
            """;

    private LlmClient llmClient;
    private int learnedSummaryInputBudget;
    private final int retainRecentTokens;
    private boolean adaptiveRetainBudget;
    /** 六段摘要的程序化垃圾回收（capSummarySize 优先用它裁剪，不调 LLM）。 */
    private final SummaryGarbageCollector summaryGc = new SummaryGarbageCollector();
    private CompactionSummaryCache compactionSummaryCache;
    private Supplier<String> postCompactContextSupplier;
    private Supplier<CompactBoundaryRuntimeState> compactBoundaryRuntimeStateSupplier;
    private Path microcompactOutputRoot;
    private MicrocompactStats lastMicrocompactStats = MicrocompactStats.empty();

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

    /**
     * 连续压缩失败计数。每次摘要 LLM 调用失败 / 返回空 / 找不到分割点时 +1；
     * 任何一次成功压缩立即清零。达到 {@link #MAX_CONSECUTIVE_FAILURES} 后
     * {@link #compactIfNeeded} 直接返回 false，不再调 LLM。
     */
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

    public void setMicrocompactOutputRoot(Path microcompactOutputRoot) {
        this.microcompactOutputRoot = microcompactOutputRoot == null
                ? null
                : microcompactOutputRoot.toAbsolutePath().normalize();
    }

    public MicrocompactStats lastMicrocompactStats() {
        return lastMicrocompactStats;
    }

    static String microcompactSessionId() {
        return MICROCOMPACT_SESSION_ID;
    }

    /**
     * 评估并按需压缩 history，原地修改。
     *
     * @param history       Agent 主循环的 conversationHistory，调用结束后可能被替换为更短列表
     * @param triggerTokens 触发压缩的 token 阈值（通常是 ContextProfile.compressionTriggerTokens()）
     * @return 是否做了历史级压缩（LLM 摘要或降级截断）；仅 microcompact 回收旧工具结果
     *         不改变历史结构，返回 false（回收已在 content 留标记 + log，调用方无需提示）
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;
        int preCompactionTokens = TokenBudget.estimateMessagesTokens(history);
        boolean metrics = Boolean.parseBoolean(System.getProperty(COMPACTION_METRICS_PROPERTY, "false"));
        boolean enabled = isCompactionEnabled(System.getProperties(), System.getenv());
        if (metrics) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=decision enabled=%s historyTokens=%d triggerTokens=%d%n",
                    enabled, preCompactionTokens, triggerTokens);
        }
        if (!enabled) return false;
        if (metrics && preCompactionTokens >= triggerTokens) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=trigger beforeTokens=%d triggerTokens=%d%n",
                    preCompactionTokens, triggerTokens);
        }

        // 第 0 层 microcompact：只回收旧工具结果，不改 user / assistant 语义消息。
        // 回收后仍超过阈值才进入模型摘要；熔断/冷却期也可执行这层低成本 Tool Result GC。
        boolean microChanged = microcompactOversizeMessages(history);
        if (TokenBudget.estimateMessagesTokens(history) < triggerTokens) {
            if (microChanged) {
                log.info("microcompact alone brought conversation below trigger; skip LLM summarization");
            }
            // micro 只替换旧 tool_result 正文、不改变消息结构，不视为"历史压缩"；
            // 返回 false 避免调用方打印"已压缩为摘要"的误导提示。
            return false;
        }

        // 检查是否在降级冷却期内
        long now = System.currentTimeMillis();
        if (lastFallbackTimestamp > 0 && (now - lastFallbackTimestamp) < FALLBACK_COOLDOWN_MS) {
            // 冷却期内不再尝试 LLM 摘要压缩，避免降级循环；
            // 但 token 再次越过阈值说明有真实新增内容，允许结构性截断兜底，
            // 否则冷却期内会裸奔撞窗口。
            if (TokenBudget.estimateMessagesTokens(history) >= triggerTokens) {
                boolean truncated = fallbackTruncate(history, triggerTokens);
                if (truncated) {
                    lastFallbackTimestamp = now;
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

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // circuit breaker 已熔断：启用降级截断策略
            log.warn("压缩连续失败 {} 次，启用降级截断策略", MAX_CONSECUTIVE_FAILURES);
            boolean truncated = fallbackTruncate(history, triggerTokens);
            if (truncated) {
                lastFallbackTimestamp = now;
                consecutiveFailures = 0; // 重置计数器
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
        int tailBudget = adaptiveRetainBudget
                ? Math.min(retainRecentTokens(), Math.max(1, triggerTokens / 2)) : retainRecentTokens;
        int splitIdx = findSplitIdxByTokenBudget(history, systemEnd, tailBudget);
        splitIdx = fitRecentTailWithinTokenBudget(history, systemEnd, splitIdx, tailBudget);
        if (splitIdx <= systemEnd) {
            log.info("compactIfNeeded skip: cannot find safe splitIdx > systemEnd={}", systemEnd);
            // 这不是 LLM 调用失败，是结构性无法压缩（如全是 system 或 retainTokens 过大）。
            // 不计入 consecutiveFailures，避免被锁死在尾部消息超大但摘要其实可用的场景。
            return false;
        }

        // 2) 识别 history 头是否已有"上一轮摘要" + 它的位置
        PreviousSummary prev = detectPreviousSummary(history, systemEnd);
        PreviousSummary summaryBase = prev;
        boolean periodicLifecycleGc = summaryBase != null
                && fullRecompactInterval > 0
                && successfulCompactions > 0
                && successfulCompactions % fullRecompactInterval == 0;

        // 3) oldMsgs：[systemEnd 之后到 splitIdx 之前] 的所有消息
        //    若有 prev 摘要，oldMsgs 包括 prev 那条 user 消息（增量摘要 prompt 会把它单独识别出来当 base）
        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        // 4) 摘要：优先复用会话预摘要，否则走增量 vs 全量 Map-Reduce。
        String summary = null;
        if (summaryBase == null && compactionSummaryCache != null) {
            var reusablePreSummary = compactionSummaryCache.findReusablePreSummary(oldMsgs);
            if (reusablePreSummary.isPresent()) {
                summary = reusablePreSummary.get().summary();
                log.info("reuse session memory pre-summary for {} old messages",
                        reusablePreSummary.get().messageCount());
            } else {
                var extendablePreSummary = compactionSummaryCache.findExtendablePreSummary(oldMsgs);
                if (extendablePreSummary.isPresent()) {
                    int absoluteEnd = systemEnd + extendablePreSummary.get().messageCount();
                    summaryBase = new PreviousSummary(
                            systemEnd, absoluteEnd, extendablePreSummary.get().summary());
                    log.info("extend session memory pre-summary from {} to {} old messages",
                            extendablePreSummary.get().messageCount(), oldMsgs.size());
                }
            }
        }
        periodicLifecycleGc = summaryBase != null
                && fullRecompactInterval > 0
                && successfulCompactions > 0
                && successfulCompactions % fullRecompactInterval == 0;
        if (summary == null) {
            SummaryAttempt attempt = summarizeWithPtlRetry(
                    summaryBase, history, splitIdx, oldMsgs);
            if (attempt.terminated()) {
                // attempt 内部已经 recordFailure
                return false;
            }
            summary = attempt.summary();
        }
        RollingSummary lifecycleSummary = RollingSummary.parse(summary);
        if (!lifecycleSummary.isEmpty()) {
            ageLifecycleItems(lifecycleSummary);
            summaryGc.gc(lifecycleSummary, MAX_SUMMARY_CHARS, periodicLifecycleGc);
            summary = lifecycleSummary.render();
        }
        summary = capSummarySize(summary);
        CompactionSemanticGuard.Validation semanticValidation =
                CompactionSemanticGuard.validateAndRepair(oldMsgs, summary, MAX_SUMMARY_CHARS);
        if (!semanticValidation.validBeforeRepair()) {
            log.warn("compaction semantic guard restored {}/{} protected constraints",
                    semanticValidation.missingConstraints().size(),
                    semanticValidation.protectedConstraintCount());
        }
        summary = semanticValidation.repairedSummary();

        // 5) 重建：[system] + [user(摘要)] + [assistant("好的")] + 保留尾部
        int originalMessages = history.size();
        int retainedMessages = history.size() - splitIdx;
        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.internalUser(SUMMARY_MARKER + summary.trim()));
        // 占位确认消息只为维持 user/assistant 交替协议;用语言无关的最短文本,
        // 避免模型复述中文散文或在非中文模型下产生歧义。
        rebuilt.add(LlmClient.Message.assistant("OK."));
        String restoreContext = buildPostCompactRestoreContext();
        if (!restoreContext.isBlank()) {
            rebuilt.add(LlmClient.Message.internalUser(POST_COMPACT_RESTORE_MARKER + restoreContext));
            rebuilt.add(LlmClient.Message.assistant("OK."));
        }
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        CompactBoundaryRuntimeState runtimeState =
                buildCompactBoundaryRuntimeState(!restoreContext.isBlank());
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history",
                "token_threshold",
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
                semanticValidation.protectedConstraintCount(),
                semanticValidation.missingConstraints().size(),
                semanticValidation.validBeforeRepair() ? "pass" : "repaired");
        rebuilt.set(systemEnd, LlmClient.Message.internalUser(
                SUMMARY_MARKER + metadata.renderBoundaryBlock() + "\n" + summary.trim()));
        history.clear();
        history.addAll(rebuilt);
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
                    "[context-compaction] kind=history mode=%s beforeTokens=%d afterTokens=%d summaryChars=%d%n",
                    periodicLifecycleGc ? "lifecycle-gc" : (summaryBase != null ? "incremental" : "full"),
                    currentTokens, afterTokens, summary.length());
        }
        return true;
    }

    private static void ageLifecycleItems(RollingSummary summary) {
        for (SummaryItem item : summary.allItems()) {
            summary.replaceItem(item, item.withCompactionCount(item.compactionCount() + 1));
        }
    }

    private CompactBoundaryRuntimeState buildCompactBoundaryRuntimeState(boolean hasPostCompactRestoreContext) {
        CompactBoundaryRuntimeState state = CompactBoundaryRuntimeState.EMPTY;
        if (compactBoundaryRuntimeStateSupplier != null) {
            try {
                CompactBoundaryRuntimeState supplied = compactBoundaryRuntimeStateSupplier.get();
                if (supplied != null) {
                    state = supplied;
                }
            } catch (RuntimeException e) {
                log.warn("compact boundary runtime state supplier failed; use empty snapshot", e);
            }
        }
        return state.withPostCompactRestoreEnabled(
                state.postCompactRestoreEnabled() || hasPostCompactRestoreContext);
    }

    private String buildPostCompactRestoreContext() {
        if (postCompactContextSupplier == null) {
            return "";
        }
        try {
            String context = postCompactContextSupplier.get();
            if (context == null || context.isBlank()) {
                return "";
            }
            String trimmed = context.trim();
            if (trimmed.length() <= MAX_POST_COMPACT_RESTORE_CHARS) {
                return trimmed;
            }
            int omitted = trimmed.length() - MAX_POST_COMPACT_RESTORE_CHARS;
            return trimmed.substring(0, MAX_POST_COMPACT_RESTORE_CHARS)
                    + "\n\n[恢复上下文已截断 " + omitted + " 字符]";
        } catch (RuntimeException e) {
            log.warn("post-compact context supplier failed; skip restore context", e);
            return "";
        }
    }

    /**
     * 第 0 层 microcompact：仅回收旧 tool_result。user、assistant、任务状态与决策文本不参与规则删除。
     * 最近 N 个工具结果、显式排除工具、记忆/外部查询工具和仍携带失败信号的结果受到保护。
     */
    boolean microcompactOversizeMessages(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            lastMicrocompactStats = MicrocompactStats.empty();
            return false;
        }
        int beforeTokens = TokenBudget.estimateMessagesTokens(history);
        Map<String, Integer> roleBefore = roleTokens(history);
        Map<String, String> toolNames = toolNamesByCallId(history);
        List<Integer> toolResultIndexes = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message message = history.get(i);
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                toolResultIndexes.add(i);
            }
        }

        int keepRecent = configuredKeepRecentToolResults();
        int firstClearablePosition = Math.max(0, toolResultIndexes.size() - keepRecent);
        Set<Integer> recentIndexes = new LinkedHashSet<>(
                toolResultIndexes.subList(firstClearablePosition, toolResultIndexes.size()));
        Map<String, Integer> removedByTool = new LinkedHashMap<>();
        int cleared = 0;
        if (microcompactOutputRoot != null) {
            for (int index : toolResultIndexes) {
                if (recentIndexes.contains(index)) continue;
                LlmClient.Message message = history.get(index);
                String content = message.content();
                String toolName = toolNames.getOrDefault(message.toolCallId(), "unknown");
                if (content == null || content.isBlank()
                        || content.contains("<microcompact_boundary>")
                        || isProtectedToolResult(toolName, content)) {
                    continue;
                }
                int before = TokenBudget.estimateMessagesTokens(List.of(message));
                String compacted = collapseOldToolResultContent(message.toolCallId(), toolName, content);
                if (compacted.equals(content)) continue;
                LlmClient.Message replacement = new LlmClient.Message(
                        message.role(), compacted, message.reasoningContent(), message.toolCalls(),
                        message.toolCallId(), message.contentParts(), message.source());
                history.set(index, replacement);
                int after = TokenBudget.estimateMessagesTokens(List.of(replacement));
                removedByTool.merge(toolName, Math.max(0, before - after), Integer::sum);
                cleared++;
                log.info("microcompact cleared old tool_result[{}] tool={} toolCallId={}: {} -> {} chars",
                        index, toolName, message.toolCallId(), content.length(), compacted.length());
            }
        }

        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        lastMicrocompactStats = new MicrocompactStats(
                beforeTokens, afterTokens, cleared, removedByTool, roleBefore, roleTokens(history));
        if (Boolean.parseBoolean(System.getProperty(COMPACTION_METRICS_PROPERTY, "false"))) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=micro beforeTokens=%d afterTokens=%d clearedToolResults=%d "
                            + "removedByTool=%s roleBefore=%s roleAfter=%s%n",
                    beforeTokens, afterTokens, cleared, removedByTool,
                    lastMicrocompactStats.roleTokensBefore(), lastMicrocompactStats.roleTokensAfter());
        }
        return cleared > 0;
    }

    private static Map<String, String> toolNamesByCallId(List<LlmClient.Message> history) {
        Map<String, String> names = new LinkedHashMap<>();
        for (LlmClient.Message message : history) {
            if (message.toolCalls() == null) continue;
            for (LlmClient.ToolCall call : message.toolCalls()) {
                if (call.id() != null && call.function() != null && call.function().name() != null) {
                    names.put(call.id(), call.function().name());
                }
            }
        }
        return names;
    }

    private static Map<String, Integer> roleTokens(List<LlmClient.Message> history) {
        Map<String, Integer> tokens = new LinkedHashMap<>();
        for (LlmClient.Message message : history) {
            String role = message.role() == null ? "unknown" : message.role();
            tokens.merge(role, TokenBudget.estimateMessagesTokens(List.of(message)), Integer::sum);
        }
        return tokens;
    }

    private static int configuredKeepRecentToolResults() {
        String configured = System.getProperty(MICRO_COMPACT_KEEP_RECENT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MICRO_COMPACT_KEEP_RECENT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return MICRO_COMPACT_RETAIN_RECENT_TOOL_RESULTS;
        }
        try {
            return Math.max(0, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(MICRO_COMPACT_KEEP_RECENT_PROPERTY
                    + " must be a non-negative integer, got: " + configured, e);
        }
    }

    private static boolean isProtectedToolResult(String toolName, String content) {
        String normalizedName = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        if (normalizedName.equals("save_memory") || normalizedName.equals("confirm_memory")
                || normalizedName.equals("list_memory")
                || normalizedName.equals("web_search") || normalizedName.equals("web_fetch")
                || (normalizedName.startsWith("mcp__")
                && (normalizedName.contains("memory") || normalizedName.contains("recall")))) {
            return true;
        }
        if (isConfiguredExcludedTool(normalizedName)) {
            return true;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        return normalizedContent.contains("toolstatus=error")
                || normalizedContent.contains("execution_failed")
                || normalizedContent.matches("(?s).*exit code:\\s*[1-9][0-9]*.*")
                || normalizedContent.contains("build failure")
                || normalizedContent.contains("工具执行失败")
                || normalizedContent.contains("命令执行失败");
    }

    private static boolean isConfiguredExcludedTool(String toolName) {
        String configured = System.getProperty(MICRO_COMPACT_EXCLUDE_TOOLS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MICRO_COMPACT_EXCLUDE_TOOLS_ENV);
        }
        if (configured == null || configured.isBlank()) return false;
        for (String raw : configured.split(",")) {
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.isEmpty()) continue;
            if (pattern.endsWith("*") && toolName.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
            if (toolName.equals(pattern)) return true;
        }
        return false;
    }

    private String collapseOldToolResultContent(String toolCallId, String toolName, String content) {
        Path outputFile = persistMicrocompactToolOutput(toolCallId, content);
        if (outputFile == null) {
            return content;
        }
        return renderMicrocompactBoundary(toolCallId, toolName, content.length(), outputFile)
                + "[旧工具结果已回收；可用 read_file 读取 storedPath。]";
    }

    private Path persistMicrocompactToolOutput(String toolCallId, String content) {
        if (microcompactOutputRoot == null) {
            return null;
        }
        try {
            Path outputDir = microcompactOutputRoot
                    .resolve(MICROCOMPACT_OUTPUTS_DIR)
                    .resolve(MICROCOMPACT_SESSION_ID);
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve(sanitizeFileName(toolCallId) + ".txt")
                    .toAbsolutePath()
                    .normalize();
            if (!outputFile.startsWith(microcompactOutputRoot)) {
                return null;
            }
            Files.writeString(outputFile, content, StandardCharsets.UTF_8);
            return outputFile;
        } catch (IOException | RuntimeException e) {
            log.warn("failed to persist microcompact tool output for {}; fallback to inline content",
                    toolCallId, e);
            return null;
        }
    }

    private String renderMicrocompactBoundary(String toolCallId,
                                              String toolName,
                                              int originalChars,
                                              Path outputFile) {
        String storedPath = microcompactOutputRoot.relativize(outputFile)
                .toString().replace('\\', '/');
        return "<microcompact_boundary>\n"
                + "type=tool_result\n"
                + "toolCallId=" + toolCallId + "\n"
                + "toolName=" + toolName + "\n"
                + "originalChars=" + originalChars + "\n"
                + "storedPath=" + storedPath + "\n"
                + "</microcompact_boundary>\n";
    }

    private static String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "tool-result";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "tool-result" : sanitized;
    }

    /**
     * 带 PTL retry 的摘要包装。
     *
     * <p>每次 LLM 调用按以下规则处理：
     * <ul>
     *   <li>成功 → 返回 summary</li>
     *   <li>抛 IOException 且消息含 PTL 关键词 → 收紧请求预算后重新分片，重试
     *       （最多 {@link #MAX_PTL_RETRIES} 次）</li>
     *   <li>抛 IOException 且非 PTL → 直接 recordFailure 并返回 giveUp</li>
     *   <li>3 次 PTL 重试仍失败 → recordFailure(ptl_exhausted) 并返回 giveUp</li>
     *   <li>summary 空 → recordFailure(empty_summary) 并返回 giveUp</li>
     * </ul>
     *
     * <p>完整历史、已有摘要和新增消息在重试之间保持不变。
     */
    private SummaryAttempt summarizeWithPtlRetry(PreviousSummary prev,
                                                  List<LlmClient.Message> history,
                                                  int splitIdx,
                                                  List<LlmClient.Message> oldMsgs) {
        // 决定增量 vs 全量路径
        boolean incremental = prev != null;
        List<LlmClient.Message> currentMsgs;
        if (incremental) {
            currentMsgs = new ArrayList<>(history.subList(prev.endIdx, splitIdx));
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
                String summary = incremental
                        ? summarizeIncremental(prev.summaryText, currentMsgs)
                        : summarize(currentMsgs);
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

    /**
     * 从尾巴往前累计 token，找到第一个让累计 ≥ retainTokens 的 user 边界作为 splitIdx。
     * <p>语义：保留尾部 ≥ retainTokens（保留区可能略大于阈值，因为切点要对齐 user 边界）。
     * splitIdx 之前的所有内容会被压缩；如果 splitIdx 等于 systemEnd，说明整段都没达到
     * retain 阈值或第一个 user 就达标了，没东西可压，调用方应跳过。
     */
    private static int findSplitIdxByTokenBudget(List<LlmClient.Message> history,
                                                  int systemEnd, int retainTokens) {
        int accumulated = 0;
        for (int i = history.size() - 1; i >= systemEnd; i--) {
            LlmClient.Message m = history.get(i);
            accumulated += TokenBudget.estimateMessagesTokens(List.of(m));
            if ("user".equals(m.role()) && accumulated >= retainTokens) {
                // 当前 user 进保留区；如果它就是 systemEnd 之后第一个 user，splitIdx == systemEnd
                // 说明整段除 system 外都要保留，调用方会 skip。否则正常压缩。
                return i;
            }
        }
        // 累计不够 retain，且没找到任何 user：返回 systemEnd 让调用方跳过
        return systemEnd;
    }

    /**
     * user 边界对齐会带来尾部超预算场景。只移动安全的 user 边界，绝不通过规则截断
     * user / assistant 或最近工具结果；仍然超限时交给后续模型语义压缩和窗口保护处理。
     */
    private int fitRecentTailWithinTokenBudget(List<LlmClient.Message> history,
                                               int systemEnd,
                                               int splitIdx,
                                               int retainTokens) {
        if (splitIdx <= systemEnd) {
            return splitIdx;
        }
        while (estimateRangeTokens(history, splitIdx, history.size()) > retainTokens) {
            int nextUser = findNextUserBoundary(history, splitIdx + 1);
            if (nextUser < 0) {
                break;
            }
            splitIdx = nextUser;
        }

        return splitIdx;
    }

    private static int findNextUserBoundary(List<LlmClient.Message> history, int start) {
        for (int i = Math.max(0, start); i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    private static int estimateRangeTokens(List<LlmClient.Message> history, int start, int end) {
        if (start >= end) {
            return 0;
        }
        return TokenBudget.estimateMessagesTokens(history.subList(start, end));
    }

    /**
     * 检测 history 里是否已有上一轮压缩留下的摘要消息（"[已压缩的历史对话摘要]" 起头的 user）。
     * 返回该摘要消息的位置和文本，没有返回 null。
     * <p>识别规则：systemEnd 之后第一条 role=user 且 content 以 SUMMARY_MARKER 起头。
     * 紧随其后通常是 assistant("好的，已了解...")，但不强制。
     */
    private static PreviousSummary detectPreviousSummary(List<LlmClient.Message> history, int systemEnd) {
        if (history.size() <= systemEnd) return null;
        LlmClient.Message first = history.get(systemEnd);
        if (!"user".equals(first.role())) return null;
        String content = first.content();
        if (content == null) return null;
        // 新结构化标记优先;旧中文标记仅为识别历史会话/旧检查点回放的存量摘要。
        String matchedMarker = content.startsWith(SUMMARY_MARKER) ? SUMMARY_MARKER
                : content.startsWith(LEGACY_SUMMARY_MARKER) ? LEGACY_SUMMARY_MARKER
                : null;
        if (matchedMarker == null) return null;
        String summaryText = CompactBoundaryMetadata.stripBoundaryBlock(
                content.substring(matchedMarker.length()).trim());
        // endIdx 跳过摘要 user + 紧随的 assistant 确认（如果有）
        int endIdx = systemEnd + 1;
        if (endIdx < history.size() && "assistant".equals(history.get(endIdx).role())) {
            endIdx++;
        }
        return new PreviousSummary(systemEnd, endIdx, summaryText);
    }

    private record PreviousSummary(int startIdx, int endIdx, String summaryText) {
    }

    /**
     * 真正调 LLM 摘要 —— Map-Reduce 形态：
     * <ol>
     *   <li><b>Map</b>: 把整段历史按摘要模型的完整请求 Token 预算分片，
     *       每片送一次 LLM 出片摘要 —— 历史所有内容都进 LLM 视野，不再 first-N 截断</li>
     *   <li><b>Reduce</b>: 多片摘要合并为最终摘要；
     *       如果片数 > {@link #MAX_REDUCE_FANIN}，先两两合并降阶再最终合并</li>
     *   <li>单片场景退化为单次摘要（与原行为一致）</li>
     * </ol>
     * <p>包可见以便测试通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        // 1) 拼成完整字符串（不截断）
        StringBuilder full = new StringBuilder();
        for (LlmClient.Message m : messages) {
            full.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                full.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    full.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            full.append("\n\n");
        }

        // 2) 整体请求能放进摘要模型窗口时直接单次摘要。固定字符阈值会把长窗口模型
        // 本可一次处理的历史强行拆成多次 Map + Reduce，增加 Token、延迟和语义损失。
        if (fitsSinglePassSummary(full.toString())) {
            return summarizeSingle(full.toString());
        }

        // 3) Map: 切片后逐片摘要
        String mapSystem = "你是一个对话摘要助手，专注于本片段事实保留，不输出片段外信息。";
        List<String> chunks = new ArrayList<>();
        String text = full.toString();
        for (int start = 0; start < text.length();) {
            // 先按最大序号开销分片，实际片段序号只会占用更少 Token。
            int end = nextChunkEnd(text, start, mapSystem,
                    chunk -> String.format(MAP_PROMPT, Integer.MAX_VALUE, Integer.MAX_VALUE, chunk));
            chunks.add(text.substring(start, end));
            start = end;
        }
        log.info("Map-Reduce summarize: {} chars -> {} chunks", full.length(), chunks.size());
        List<String> mapSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String mapPrompt = String.format(MAP_PROMPT, i + 1, chunks.size(), chunks.get(i));
            String mapSummary = requireSummary(chatOnce(mapSystem, mapPrompt));
            mapSummaries.add(mapSummary.trim());
        }

        // 4) Reduce: 合并片摘要；若片数过多先两两合并降阶
        return reduceSummaries(mapSummaries);
    }

    /** 单片场景：和原 summarize 行为一致，一次摘要。 */
    private String summarizeSingle(String content) throws IOException {
        String prompt = String.format(SUMMARY_PROMPT, content);
        return chatOnce("你是一个对话摘要助手，只输出摘要本身，不输出元描述。", prompt);
    }

    /**
     * 增量摘要：基于上一轮已有摘要 + 仅新增的若干消息，更新摘要。
     * <p>不再把已有摘要作为 oldMsgs 重新压一遍，避免摘要套娃稀释老事实。
     * 包可见以便测试通过子类替换。
     */
    protected String summarizeIncremental(String previousSummary,
                                          List<LlmClient.Message> newMessages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        StringBuilder newContent = new StringBuilder();
        for (LlmClient.Message m : newMessages) {
            newContent.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                newContent.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    newContent.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            newContent.append("\n\n");
        }
        String rolling = previousSummary;
        String text = newContent.toString();
        String system = "你是一个滚动摘要变更提取器，只提出受限 JSON 操作，不直接重写摘要。";
        for (int start = 0; start < text.length();) {
            String base = rolling;
            Function<String, String> prompt = chunk -> String.format(INCREMENTAL_PROMPT, base, chunk);
            int end = nextChunkEnd(text, start, system, prompt);
            String proposedOperations = requireSummary(chatOnce(system, prompt.apply(text.substring(start, end))));
            rolling = applyIncrementalOperations(base, proposedOperations);
            start = end;
        }
        return rolling;
    }

    private String applyIncrementalOperations(String previousSummary, String proposedOperations) throws IOException {
        SummaryLifecycleReducer.Result reduced =
                new SummaryLifecycleReducer().apply(previousSummary, proposedOperations);
        if (reduced.applied()) {
            return reduced.summary();
        }

        // 兼容过渡期仍返回完整结构化 Markdown 的模型；任意文本或损坏 JSON 均失败关闭，
        // 保留上一版摘要，避免一次格式漂移清空核心推理状态。
        RollingSummary legacy = RollingSummary.parse(proposedOperations);
        if (!legacy.isEmpty()) {
            log.warn("incremental summarizer returned legacy nine-section snapshot; normalized in compatibility mode");
            return legacy.render();
        }
        throw new IOException("Incremental summary operations rejected; original history retained");
    }

    /**
     * Reduce: 多片摘要合并。
     * <p>每批按完整请求 Token 预算装填，片数上限仅限制归并复杂度。
     */
    private String reduceSummaries(List<String> summaries) throws IOException {
        if (summaries.size() == 1) {
            return summaries.get(0);
        }
        List<String> intermediate = new ArrayList<>();
        for (int start = 0; start < summaries.size();) {
            int end = start + 1;
            while (end < summaries.size() && end - start < MAX_REDUCE_FANIN
                    && fitsSummaryRequest(reduceSystem(), reducePrompt(summaries.subList(start, end + 1)))) {
                end++;
            }
            List<String> batch = summaries.subList(start, end);
            intermediate.add(batch.size() == 1 ? batch.get(0) : doReduceOnce(batch));
            start = end;
        }
        if (intermediate.size() >= summaries.size()) {
            throw new IOException("Partial summaries cannot be merged within the model input budget; original history retained");
        }
        return reduceSummaries(intermediate);
    }

    private String doReduceOnce(List<String> summaries) throws IOException {
        return requireSummary(chatOnce(reduceSystem(), reducePrompt(summaries)));
    }

    private static String reduceSystem() {
        return "你是一个摘要合并助手，必须保留所有片段里出现过的精确实体原文。";
    }

    private static String reducePrompt(List<String> summaries) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            joined.append("--- 片段摘要 ").append(i + 1).append(" / ").append(summaries.size()).append(" ---\n");
            joined.append(summaries.get(i)).append("\n\n");
        }
        return String.format(REDUCE_PROMPT, joined);
    }

    private boolean fitsSinglePassSummary(String content) {
        return fitsSummaryRequest("你是一个对话摘要助手，只输出摘要本身，不输出元描述。",
                String.format(SUMMARY_PROMPT, content));
    }

    private boolean fitsSummaryRequest(String system, String prompt) {
        return TokenBudget.estimateMessagesTokens(List.of(
                LlmClient.Message.system(system), LlmClient.Message.user(prompt))) <= summaryInputBudgetTokens();
    }

    private int summaryInputBudgetTokens() {
        int modelBudget = ContextProfile.from(llmClient).compressionTriggerTokens();
        return learnedSummaryInputBudget > 0 ? Math.min(modelBudget, learnedSummaryInputBudget) : modelBudget;
    }

    private int nextChunkEnd(String text, int start, String system,
                             Function<String, String> prompt) throws IOException {
        if (!fitsSummaryRequest(system, prompt.apply(""))) {
            throw new IOException("Summary base and instructions exceed the model input budget; original history retained");
        }
        int low = start;
        int high = text.length();
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (fitsSummaryRequest(system, prompt.apply(text.substring(start, mid)))) low = mid;
            else high = mid - 1;
        }
        int end = low;
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        if (end <= start) throw new IOException("No room for a complete character in summary request");
        if (end < text.length()) {
            int boundary = text.lastIndexOf("\n\n", end - 2);
            if (boundary > start + (end - start) / 2) end = boundary + 2;
        }
        return end;
    }

    private static String requireSummary(String summary) throws IOException {
        if (summary == null || summary.isBlank()) {
            throw new IOException("Summary response is empty; original history retained");
        }
        return summary;
    }

    /**
     * 滚动摘要超过 {@link #MAX_SUMMARY_CHARS} 时先做确定性生命周期 GC。
     * 六段摘要即使仍超预算也不再交给 LLM 二次改写；旧版非结构化摘要才保留 LLM 兼容兜底。
     */
    private String capSummarySize(String summary) {
        if (summary == null || summary.length() <= MAX_SUMMARY_CHARS) {
            return summary;
        }
        // 先程序化 GC（不调 LLM）：解析六段 → 按段裁剪 → 渲染
        RollingSummary parsed = RollingSummary.parse(summary);
        if (!parsed.isEmpty()) {
            summaryGc.gc(parsed, MAX_SUMMARY_CHARS);
            String collected = parsed.render();
            if (collected.length() < summary.length()) {
                log.info("rolling summary GC'd: {} -> {} chars", summary.length(), collected.length());
                if (collected.length() <= MAX_SUMMARY_CHARS) {
                    return collected;
                }
                summary = collected;
            }
            if (summary.length() > MAX_SUMMARY_CHARS) {
                log.warn("structured rolling summary remains above cap after lifecycle GC ({} chars); "
                        + "protected stable or unresolved facts were retained", summary.length());
            }
            return summary;
        }
        // 旧版非结构化格式无法解析时才使用 LLM 兼容兜底。
        if (llmClient == null) {
            return summary; // 无 LLM 可兜底，返回 GC 后结果（可能略超，宁可不崩）
        }
        int targetChars = MAX_SUMMARY_CHARS / 2;
        try {
            String recompressed = chatOnce(
                    "你是一个摘要再压缩助手，必须保留所有精确实体原文和最终决策。",
                    String.format(RECOMPRESS_PROMPT, targetChars, summary));
            if (recompressed != null && !recompressed.isBlank()
                    && recompressed.trim().length() < summary.length()) {
                log.info("rolling summary recompressed: {} -> {} chars",
                        summary.length(), recompressed.trim().length());
                return recompressed.trim();
            }
            log.warn("summary recompress returned invalid result; keep oversized summary ({} chars)",
                    summary.length());
        } catch (IOException e) {
            // fallback：保留超长原摘要，宁可贵也不丢事实
            log.warn("summary recompress failed; keep oversized summary ({} chars)", summary.length(), e);
        }
        return summary;
    }

    private String chatOnce(String systemPrompt, String userPrompt) throws IOException {
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system(systemPrompt),
                LlmClient.Message.user(userPrompt)
        );
        if (TokenBudget.estimateMessagesTokens(req) > summaryInputBudgetTokens()) {
            throw new IOException("Summary request exceeds local input budget; original history retained");
        }
        LlmClient.ChatResponse response;
        try {
            response = llmClient.chat(req, null);
        } catch (com.devcli.llm.LlmException failure) {
            if (Boolean.parseBoolean(System.getProperty(COMPACTION_METRICS_PROPERTY, "false"))) {
                System.err.printf(Locale.ROOT,
                        "[context-compaction] kind=summary-error code=%s status=%d%n",
                        failure.code(), failure.statusCode());
            }
            throw failure;
        }
        if (response != null && Boolean.parseBoolean(
                System.getProperty(COMPACTION_METRICS_PROPERTY, "false"))) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=summary-call inputTokens=%d outputTokens=%d cachedInputTokens=%d%n",
                    response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        }
        return response == null ? null : response.content();
    }

    /**
     * 降级截断策略：压缩失败 3 次后的兜底方案。
     * 根据目标 token 动态计算需要删除的消息数量，保留 system 和最近上下文。
     *
     * @param history       对话历史
     * @param triggerTokens 触发压缩的 token 阈值
     * @return 是否成功截断
     */
    private boolean fallbackTruncate(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        if (history.size() <= systemEnd + 1) {
            // 只有 system 或 system + 1条消息，无法截断
            return false;
        }

        // 目标：降到 trigger * 0.7（留 30% 安全余量）
        int targetTokens = (int) (triggerTokens * 0.7);
        int currentTokens = TokenBudget.estimateMessagesTokens(history);

        if (currentTokens <= targetTokens) {
            // 已经在安全范围内，无需截断
            return false;
        }

        // Bug #4 修复：按 user 边界对齐删除，确保 tool_call/tool_result 不分离
        // 策略：从头开始找 user 消息边界，删除到该边界之前的所有消息
        int deleteUpTo = systemEnd;  // 默认从 system 后开始
        int accumulatedTokens = currentTokens;

        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                // 计算删除到这个 user 之前的 token 减少量
                int tokensToRemove = 0;
                for (int j = systemEnd; j < i; j++) {
                    tokensToRemove += TokenBudget.estimateMessagesTokens(List.of(history.get(j)));
                }
                if (accumulatedTokens - tokensToRemove <= targetTokens) {
                    // 删除到这个 user 之前可以达到目标
                    deleteUpTo = i;
                    break;
                }
            }
        }

        // 至少保留 system + 3 条消息（降级标记 + assistant + 1条用户消息）
        int minKeep = 3;

        // 兜底：没有任何 user 边界能降到目标（例如尾部存在超大单条消息）时仍尽力删，
        // 删到保留最近 minKeep 条的最近 user 边界，避免熔断/冷却期裸奔撞窗口。
        if (deleteUpTo == systemEnd && history.size() > systemEnd + minKeep) {
            int keepFrom = history.size() - minKeep;
            while (keepFrom > systemEnd && !"user".equals(history.get(keepFrom).role())) {
                keepFrom--;
            }
            deleteUpTo = keepFrom;
        }

        int toRemove = deleteUpTo - systemEnd;
        int originalToRemove = toRemove;
        toRemove = Math.min(toRemove, history.size() - systemEnd - minKeep);

        // Bug #4 残留修复：如果 minKeep cap 改变了 toRemove，回退到最近的 user 边界
        if (toRemove < originalToRemove) {
            int cappedEnd = systemEnd + toRemove;
            for (int i = cappedEnd; i > systemEnd; i--) {
                if ("user".equals(history.get(i).role())) {
                    toRemove = i - systemEnd;
                    break;
                }
            }
        }

        if (toRemove <= 0) {
            // 无法删除足够的消息达到目标，说明最近几条消息就很大
            log.warn("fallbackTruncate: 无法删除足够消息降到目标 token，当前 {} 目标 {}",
                currentTokens, targetTokens);
            return false;
        }

        List<LlmClient.Message> preserved = new ArrayList<>();

        // 保留 system 消息
        for (int i = 0; i < systemEnd; i++) {
            preserved.add(history.get(i));
        }

        // 插入降级标记
        int keptMessages = history.size() - systemEnd - toRemove;
        preserved.add(LlmClient.Message.internalUser(
            "[上下文压缩降级] 由于压缩连续失败，早期对话已截断。"
            + "当前保留最近 " + keptMessages + " 条消息。"
        ));
        preserved.add(LlmClient.Message.assistant(
            "了解，早期上下文已截断。我会基于当前保留的上下文继续工作。"
        ));

        // 保留尾部消息
        preserved.addAll(history.subList(systemEnd + toRemove, history.size()));

        history.clear();
        history.addAll(preserved);

        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        log.warn("fallbackTruncate: removed {} early messages ({}->{} tokens), kept {} recent messages",
            toRemove, currentTokens, afterTokens, keptMessages);

        return true;
    }

    public int retainRecentTokens() {
        return adaptiveRetainBudget
                ? Math.max(1, (int) (ContextProfile.from(llmClient).maxContextWindow() * DEFAULT_RETAIN_WINDOW_RATIO))
                : retainRecentTokens;
    }

    public static boolean isCompactionEnabled(java.util.Properties properties,
                                              java.util.Map<String, String> environment) {
        String configured = properties == null ? null : properties.getProperty(COMPACTION_ENABLED_PROPERTY);
        if ((configured == null || configured.isBlank()) && environment != null) {
            configured = environment.get(COMPACTION_ENABLED_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return true;
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)
                || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)
                || "off".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException(COMPACTION_ENABLED_PROPERTY
                + " must be true|false, got: " + configured);
    }
}
