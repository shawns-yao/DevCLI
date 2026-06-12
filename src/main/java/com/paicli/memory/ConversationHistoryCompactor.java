package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 压缩 ReAct 主循环里的 {@code conversationHistory}（即 {@code List<LlmClient.Message>}）。
 *
 * <p>v3 重构（路径 B）：旧版本曾与 {@code ContextCompressor + ConversationMemory} 双轨并存，
 * 后者只压旁路笔记本不影响 LLM 输入，已删除。本类是真正治理 LLM 输入窗口的唯一压缩点。
 *
 * 算法（v2，PR-1）：
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
 * - Map-Reduce 朴素版多轮压缩到 27.8%；增量摘要预期突破 40%+
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    /**
     * 老阈值参数，向后兼容字段名（虽然语义改了）。当通过旧构造器
     * {@code ConversationHistoryCompactor(llm, n)} 传入时，会按 "n × 1k token" 折算成
     * retainRecentTokens（每个 user 轮约 1k token 是个粗估）。
     */
    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    /** 默认按 token 预算保留尾部。30k token 在 200k window 下约占 15%，给 LLM 充足近期上下文。 */
    private static final int DEFAULT_RETAIN_RECENT_TOKENS = 30_000;
    /** 单片送 LLM 的字符上限。控制单次摘要请求不会撑爆 LLM window。 */
    private static final int MAP_CHUNK_CHARS = 60_000;
    /**
     * Reduce 阶段最多合并多少片摘要。如果片数 > 此值，会先做"二次 Map"
     * （每 N 片合并成一段中间摘要），再 Reduce 最终。防止 Reduce prompt 自己撑爆 window。
     */
    private static final int MAX_REDUCE_FANIN = 8;
    /** 摘要消息的统一前缀，用于识别"上一轮压缩留下的摘要"。 */
    static final String SUMMARY_MARKER = "[已压缩的历史对话摘要]\n";

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
     * 每次重试丢掉 oldMsgs 头部 20% 的 user 边界对齐 round，再试一次。
     * 超过仍然 PTL 才计入 {@link #consecutiveFailures}。
     */
    static final int MAX_PTL_RETRIES = 3;

    /**
     * 每次 PTL retry 丢掉的 round 比例。20% 是经验值——丢太少不够腾出空间、
     * 丢太多保留区压力过大。
     */
    private static final double PTL_RETRY_DROP_RATIO = 0.20;

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
            "max_tokens",
            "input is too long",
            "request too large",
            "tokens exceeds",
            "exceed the model",
    };

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

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
            下面是一段长对话被切成多个片段后，每片各自的摘要。请把它们合并成一份完整的整体摘要，保留：
            1. 整段对话的最初目标和最终结论
            2. 所有出现过的精确实体（文件名、路径、数字常量、错误码）必须以原文形式出现在合并摘要里
            3. 决策若被覆盖过（先 A 后 B 最终 C），合并摘要里要明确"最终是 C"
            4. 出现过的关键错误与对应处理

            不要遗漏任何片段里出现过的精确实体；不要发明片段里不存在的信息。
            输出 3-5 段中文，不加前缀。

            === 各片段摘要 ===
            %s
            === 各片段摘要结束 ===
            """;

    private static final String INCREMENTAL_PROMPT = """
            你正在维护一份长对话的滚动摘要。下面是已有摘要和自上次以来的新对话，
            请把新对话的关键信息**整合进**已有摘要，保留：
            1. 已有摘要里的所有事实（决策、约束、用户偏好、错误码、精确实体）一条都不能丢
            2. 新对话里出现的具体决策（包括对已有决策的覆盖；覆盖时明确标注"最终值=X"）
            3. 新对话里出现的精确实体（文件名/路径/数字/错误码）必须保留原文
            4. 新对话引发的新结论或新待办

            不要重新组织已有摘要的句式；只在结尾追加新增内容，必要时修改被覆盖的字段。
            丢弃过渡话术（"好的，继续"、"我理解了"）和应答性回复。
            输出更新后的完整摘要，3-6 段中文，不加前缀。

            === 已有摘要 ===
            %s
            === 已有摘要（结束） ===

            === 新增对话 ===
            %s
            === 新增对话（结束） ===
            """;

    private LlmClient llmClient;
    private final int retainRecentTokens;

    /**
     * 连续压缩失败计数。每次摘要 LLM 调用失败 / 返回空 / 找不到分割点时 +1；
     * 任何一次成功压缩立即清零。达到 {@link #MAX_CONSECUTIVE_FAILURES} 后
     * {@link #compactIfNeeded} 直接返回 false，不再调 LLM。
     */
    private int consecutiveFailures = 0;

    /**
     * 上次降级截断的时间戳（毫秒）。用于冷却期判断，避免降级循环。
     */
    private long lastFallbackTimestamp = 0;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_TOKENS, true);
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
    }

    /**
     * 评估并按需压缩 history，原地修改。
     *
     * @param history       Agent 主循环的 conversationHistory，调用结束后可能被替换为更短列表
     * @param triggerTokens 触发压缩的 token 阈值（通常是 ContextProfile.compressionTriggerTokens()）
     * @return 是否真的压缩了
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;

        // 检查是否在降级冷却期内
        long now = System.currentTimeMillis();
        if (lastFallbackTimestamp > 0 && (now - lastFallbackTimestamp) < FALLBACK_COOLDOWN_MS) {
            // 冷却期内不执行任何压缩操作，避免降级循环
            return false;
        }

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // circuit breaker 已熔断：启用降级截断策略
            log.warn("压缩连续失败 {} 次，启用降级截断策略", MAX_CONSECUTIVE_FAILURES);
            boolean truncated = fallbackTruncate(history, triggerTokens);
            if (truncated) {
                lastFallbackTimestamp = now;
                consecutiveFailures = 0; // 重置计数器
            }
            return truncated;
        }
        int currentTokens = TokenBudget.estimateMessagesTokens(history);
        if (currentTokens < triggerTokens) return false;

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        // 1) token 预算保留区：从尾巴往前累计 token，落在 user 边界
        int splitIdx = findSplitIdxByTokenBudget(history, systemEnd, retainRecentTokens);
        if (splitIdx <= systemEnd) {
            log.info("compactIfNeeded skip: cannot find safe splitIdx > systemEnd={}", systemEnd);
            // 这不是 LLM 调用失败，是结构性无法压缩（如全是 system 或 retainTokens 过大）。
            // 不计入 consecutiveFailures，避免被锁死在尾部消息超大但摘要其实可用的场景。
            return false;
        }

        // 2) 识别 history 头是否已有"上一轮摘要" + 它的位置
        PreviousSummary prev = detectPreviousSummary(history, systemEnd);

        // 3) oldMsgs：[systemEnd 之后到 splitIdx 之前] 的所有消息
        //    若有 prev 摘要，oldMsgs 包括 prev 那条 user 消息（增量摘要 prompt 会把它单独识别出来当 base）
        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        // 4) 摘要：增量 vs 全量 Map-Reduce（含 PTL retry：摘要自身 OOM 时丢头部 round 重试）
        SummaryAttempt attempt = summarizeWithPtlRetry(prev, history, splitIdx, oldMsgs);
        if (attempt.terminated()) {
            // attempt 内部已经 recordFailure
            return false;
        }
        String summary = attempt.summary();

        // 5) 重建：[system] + [user(摘要)] + [assistant("好的")] + 保留尾部
        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user(SUMMARY_MARKER + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(rebuilt);
        history.clear();
        history.addAll(rebuilt);
        // 成功压缩：清零失败计数，让下次失败重新累计
        if (consecutiveFailures > 0) {
            log.info("conversation compaction succeeded; reset failure counter from {}", consecutiveFailures);
            consecutiveFailures = 0;
        }
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, mode=%s, summary chars %d",
                currentTokens, afterTokens, oldMsgs.size() + systemEnd, rebuilt.size(),
                prev != null ? "incremental" : "full",
                summary.length()));
        return true;
    }

    /**
     * 带 PTL retry 的摘要包装。
     *
     * <p>每次 LLM 调用按以下规则处理：
     * <ul>
     *   <li>成功 → 返回 summary</li>
     *   <li>抛 IOException 且消息含 PTL 关键词 → 丢掉 oldMsgs 头部
     *       {@link #PTL_RETRY_DROP_RATIO} 的 user 边界对齐 round，重试
     *       （最多 {@link #MAX_PTL_RETRIES} 次）</li>
     *   <li>抛 IOException 且非 PTL → 直接 recordFailure 并返回 giveUp</li>
     *   <li>3 次 PTL 重试仍失败 → recordFailure(ptl_exhausted) 并返回 giveUp</li>
     *   <li>summary 空 → recordFailure(empty_summary) 并返回 giveUp</li>
     * </ul>
     *
     * <p>注意：增量摘要场景（prev != null）只对 newMsgs 做 PTL retry，不丢
     * prev.summaryText（那是上一轮的事实，丢了等于失忆）。如果 newMsgs
     * 太大触发 PTL，丢 newMsgs 头部即可。
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

                List<LlmClient.Message> trimmed = dropOldestRoundsByRatio(currentMsgs, PTL_RETRY_DROP_RATIO);
                if (trimmed.size() == currentMsgs.size() || trimmed.isEmpty()) {
                    // 实在切不动了（消息太少或全是 user 边界外的内容）→ 放弃 PTL retry
                    log.warn("conversation summary PTL but cannot drop more rounds; give up", e);
                    recordFailure("ptl_uncuttable");
                    return SummaryAttempt.giveUp();
                }
                int dropped = currentMsgs.size() - trimmed.size();
                log.info("conversation summary PTL on attempt {}/{}: dropped {} oldest messages, retrying with {}",
                        ptlAttempts, MAX_PTL_RETRIES, dropped, trimmed.size());
                currentMsgs = trimmed;
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

    /**
     * 按 user 边界把消息列表切成 round（每个 round 以 user 开头），丢掉头部
     * 占比 {@code dropRatio} 的 round（向上取整，至少丢 1 个），保留剩下的。
     *
     * <p>关键约束：保留段必须以 user 消息起头，避免出现 "tool_result 没有
     * 对应 tool_call" 的协议错误。如果丢掉头部后第一条不是 user，会继续
     * 往后丢直到对齐。
     */
    static List<LlmClient.Message> dropOldestRoundsByRatio(List<LlmClient.Message> messages, double dropRatio) {
        if (messages == null || messages.size() <= 1) return List.of();

        // 1) 切分成 round：每个 round 从 user 边界开始
        List<Integer> roundStarts = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role())) {
                roundStarts.add(i);
            }
        }
        if (roundStarts.size() <= 1) {
            // 只有 0-1 个 user 边界 → 没法切，调用方应放弃 PTL retry
            return messages;
        }

        // 2) 计算丢多少个 round（至少 1 个）
        int totalRounds = roundStarts.size();
        int dropCount = (int) Math.ceil(totalRounds * dropRatio);
        dropCount = Math.max(1, Math.min(dropCount, totalRounds - 1)); // 至少留 1 个 round

        // 3) 切割：保留从 roundStarts.get(dropCount) 开始的部分
        int keepFrom = roundStarts.get(dropCount);
        return new ArrayList<>(messages.subList(keepFrom, messages.size()));
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
        if (content == null || !content.startsWith(SUMMARY_MARKER)) return null;
        String summaryText = content.substring(SUMMARY_MARKER.length()).trim();
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
     *   <li><b>Map</b>: 把整段历史按 {@link #MAP_CHUNK_CHARS} 字符分片，
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

        // 2) 单片场景走原逻辑：直接一次摘要
        if (full.length() <= MAP_CHUNK_CHARS) {
            return summarizeSingle(full.toString());
        }

        // 3) Map: 切片后逐片摘要
        List<String> chunks = splitIntoChunks(full.toString(), MAP_CHUNK_CHARS);
        log.info("Map-Reduce summarize: {} chars -> {} chunks", full.length(), chunks.size());
        List<String> mapSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String mapPrompt = String.format(MAP_PROMPT, i + 1, chunks.size(), chunks.get(i));
            String mapSummary = chatOnce(
                    "你是一个对话摘要助手，专注于本片段事实保留，不输出片段外信息。",
                    mapPrompt);
            if (mapSummary != null && !mapSummary.isBlank()) {
                mapSummaries.add(mapSummary.trim());
            }
        }
        if (mapSummaries.isEmpty()) {
            return null;
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
        // 新增内容如果太大，先对新增部分做 Map-Reduce，再传给 INCREMENTAL_PROMPT
        // 这是兜底：增量场景下新增内容通常 < MAP_CHUNK_CHARS，不会触发
        String newDigest;
        if (newContent.length() <= MAP_CHUNK_CHARS) {
            newDigest = newContent.toString();
        } else {
            log.info("Incremental: new content {} chars > {} cap, sub-summarize first",
                    newContent.length(), MAP_CHUNK_CHARS);
            List<String> chunks = splitIntoChunks(newContent.toString(), MAP_CHUNK_CHARS);
            List<String> mapSummaries = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String mapPrompt = String.format(MAP_PROMPT, i + 1, chunks.size(), chunks.get(i));
                String mapSummary = chatOnce(
                        "你是一个对话摘要助手，专注于本片段事实保留，不输出片段外信息。",
                        mapPrompt);
                if (mapSummary != null && !mapSummary.isBlank()) {
                    mapSummaries.add(mapSummary.trim());
                }
            }
            newDigest = String.join("\n\n", mapSummaries);
        }
        String prompt = String.format(INCREMENTAL_PROMPT, previousSummary, newDigest);
        return chatOnce(
                "你是一个滚动摘要维护助手。整合新对话进已有摘要，必须保留已有摘要里的所有事实。",
                prompt);
    }

    /**
     * Reduce: 多片摘要合并。
     * <p>如果一次性合并的字符总量超过 {@link #MAP_CHUNK_CHARS}，
     * 先做"二次 Map"——每 {@link #MAX_REDUCE_FANIN} 片合并成一段中间摘要，
     * 再递归 Reduce，避免 Reduce prompt 撑爆 window。
     */
    private String reduceSummaries(List<String> summaries) throws IOException {
        if (summaries.size() == 1) {
            return summaries.get(0);
        }
        // 估算 join 后总长
        int totalChars = summaries.stream().mapToInt(String::length).sum() + summaries.size() * 30;
        if (totalChars <= MAP_CHUNK_CHARS && summaries.size() <= MAX_REDUCE_FANIN) {
            // 一次性 Reduce
            return doReduceOnce(summaries);
        }
        // 否则分批先合并降阶，再递归
        List<String> intermediate = new ArrayList<>();
        for (int i = 0; i < summaries.size(); i += MAX_REDUCE_FANIN) {
            List<String> batch = summaries.subList(i, Math.min(i + MAX_REDUCE_FANIN, summaries.size()));
            if (batch.size() == 1) {
                intermediate.add(batch.get(0));
            } else {
                intermediate.add(doReduceOnce(batch));
            }
        }
        return reduceSummaries(intermediate);
    }

    private String doReduceOnce(List<String> summaries) throws IOException {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            joined.append("--- 片段摘要 ").append(i + 1).append(" / ").append(summaries.size()).append(" ---\n");
            joined.append(summaries.get(i)).append("\n\n");
        }
        String prompt = String.format(REDUCE_PROMPT, joined);
        return chatOnce("你是一个摘要合并助手，必须保留所有片段里出现过的精确实体原文。", prompt);
    }

    private String chatOnce(String systemPrompt, String userPrompt) throws IOException {
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system(systemPrompt),
                LlmClient.Message.user(userPrompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    /**
     * 按字符上限切片。尽量在双换行（消息边界）切，否则在硬上限处切。
     * 不再 first-N 截断——整段历史都会进 LLM 视野。
     */
    private static List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(start + chunkSize, n);
            if (end < n) {
                // 优先在 "\n\n" 边界切
                int boundary = text.lastIndexOf("\n\n", end);
                if (boundary > start + chunkSize / 2) {
                    end = boundary + 2; // 包含 \n\n
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
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

        // 从头开始删除消息，直到 token 降到目标以下
        int toRemove = 0;
        int accumulatedTokens = currentTokens;
        for (int i = systemEnd; i < history.size() && accumulatedTokens > targetTokens; i++) {
            int msgTokens = TokenBudget.estimateMessagesTokens(List.of(history.get(i)));
            accumulatedTokens -= msgTokens;
            toRemove++;
        }

        // 至少保留 system + 3 条消息（降级标记 + assistant + 1条用户消息）
        int minKeep = 3;
        toRemove = Math.min(toRemove, history.size() - systemEnd - minKeep);

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
        preserved.add(LlmClient.Message.user(
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
        return retainRecentTokens;
    }
}
