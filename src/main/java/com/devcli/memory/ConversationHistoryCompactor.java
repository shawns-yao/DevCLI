package com.devcli.memory;

import com.devcli.llm.LlmClient;
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
 * <p>第 0 层 microcompact：在任何 LLM 摘要之前，先把单条超大消息（通常是大工具结果）头尾截断
 * （{@link #microcompactOversizeMessages}，不调 LLM、不删消息）。这既能在很多情况下直接把 token
 * 压回阈值、省掉摘要，又保证后续保留区不被单条巨型消息撑爆（避免单条 100k 导致 splitIdx==systemEnd
 * 而 skip）。MVP 用头尾截断，预留 offload 落盘（可重新取回）的扩展点。
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
 * - 摘要输出为固定九段结构化（{@link RollingSummary}，对标 Claude Code /compact 模板）；
 *   超长时先由 {@link SummaryGarbageCollector} 程序化按段裁剪（不调 LLM），不够再 LLM recompress 兜底
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

    // ── microcompact（第 0 层）：截断单条超大消息，不调 LLM ──
    /**
     * 普通消息触发 microcompact 截断的 content 字符阈值。超过则头尾保留、中间省略。
     * 主要命中大工具结果（read_file 大文件 / bash 刷屏 / search 大结果）。
     */
    static final int MICRO_COMPACT_TRIGGER_CHARS = 24_000;
    /** 普通超大消息截断后保留的头部字符数。 */
    private static final int MICRO_COMPACT_HEAD_CHARS = 2_000;
    /** 普通超大消息截断后保留的尾部字符数（错误码 / 结论常在结尾）。 */
    private static final int MICRO_COMPACT_TAIL_CHARS = 1_000;
    /**
     * 最后一条消息（通常是当前请求或刚执行的工具结果）的截断阈值，比普通消息宽松，
     * 尽量不动当前上下文；只有超过这个绝对上限才截，避免单条就撑爆保留区。
     */
    static final int MICRO_COMPACT_LAST_TRIGGER_CHARS = 48_000;
    /** 最后一条消息截断后保留的头部字符数（更宽松）。 */
    private static final int MICRO_COMPACT_LAST_HEAD_CHARS = 6_000;
    /** 最后一条消息截断后保留的尾部字符数（更宽松）。 */
    private static final int MICRO_COMPACT_LAST_TAIL_CHARS = 3_000;

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
     * 滚动摘要的字符上限。增量摘要"只追加不删除"会让摘要单调膨胀，
     * 超过此上限时触发一次"摘要的摘要"再压缩（保留精确实体与最终决策）。
     * 再压缩失败时保留原摘要并打日志，不阻断压缩主流程。
     */
    static final int MAX_SUMMARY_CHARS = 16_000;

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
            "input is too long",
            "request too large",
            "tokens exceeds",
            "exceed the model",
    };

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成结构化摘要，严格按以下九个 Markdown 段落输出（标题用 ## 开头，无内容写"无"）：

            ## 主要请求与意图
            ## 关键技术概念
            ## 文件和代码
            ## 踩过的坑和修复
            ## 问题解决过程
            ## 逐条用户消息
            ## 待办任务
            ## 当前在做什么
            ## 下一步

            要求：精确实体（文件名/路径/数字/错误码）保留原文；决策被覆盖时只保留最终值；
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
            下面是一段长对话被切成多片后各自的摘要。请合并成一份完整摘要，严格按以下九个 Markdown 段落输出（标题用 ## 开头，无内容写"无"）：

            ## 主要请求与意图
            ## 关键技术概念
            ## 文件和代码
            ## 踩过的坑和修复
            ## 问题解决过程
            ## 逐条用户消息
            ## 待办任务
            ## 当前在做什么
            ## 下一步

            要求：所有片段里的精确实体（文件名/路径/数字/错误码）必须以原文出现；决策被覆盖（先 A 后 B 最终 C）只保留"最终是 C"；不遗漏任何片段事实；不加段落外前缀。

            === 各片段摘要 ===
            %s
            === 各片段摘要结束 ===
            """;

    private static final String INCREMENTAL_PROMPT = """
            你在维护一份九段式滚动摘要。下面是已有摘要（九段）和自上次以来的新对话，
            请把新对话的关键信息按段整合进已有摘要，输出更新后的完整九段摘要（同样的 ## 段落结构）：

            ## 主要请求与意图
            ## 关键技术概念
            ## 文件和代码
            ## 踩过的坑和修复
            ## 问题解决过程
            ## 逐条用户消息
            ## 待办任务
            ## 当前在做什么
            ## 下一步

            要求：新信息并入对应段；决策被覆盖时该段只保留最终值；精确实体（文件名/路径/数字/错误码）保留原文；
            "逐条用户消息"段在末尾追加新出现的用户消息要点；丢弃过渡话术；不加段落外前缀。

            === 已有摘要（九段） ===
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
    private final int retainRecentTokens;
    /** 九段摘要的程序化垃圾回收（capSummarySize 优先用它裁剪，不调 LLM）。 */
    private final SummaryGarbageCollector summaryGc = new SummaryGarbageCollector();

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
     * @return 是否做了历史级压缩（LLM 摘要或降级截断）；仅 microcompact 截断单条超大消息
     *         不改变历史结构，返回 false（截断已在 content 留标记 + log，调用方无需提示）
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;

        // 第 0 层 microcompact：先截断单条超大消息（不调 LLM）。这既能在很多情况下直接把
        // token 压回阈值内、省掉一次 LLM 摘要，又保证后续 full compact 的保留区不会被单条巨型
        // 消息撑爆（解决"单条 100k 让 splitIdx==systemEnd 而 skip"的盲区）。即使在熔断/冷却期
        // 也执行——这是降级时最划算的廉价压缩。
        boolean microChanged = microcompactOversizeMessages(history);
        if (TokenBudget.estimateMessagesTokens(history) < triggerTokens) {
            if (microChanged) {
                log.info("microcompact alone brought conversation below trigger; skip LLM summarization");
            }
            // micro 只是后台截断单条超大消息、不改变历史结构（不摘要、不删消息），不视为"历史压缩"，
            // 返回 false 避免调用方打印"已压缩为摘要"的误导提示。截断已在 content 留标记 + log，非静默丢弃。
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
        summary = capSummarySize(summary);

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
     * 第 0 层 microcompact：把单条 content 超阈值的消息头尾截断、中间用标记替代，原地修改 history。
     * 不调 LLM、不删消息（保持 tool_call/tool_result 配对），是最廉价的一层压缩，先于任何 LLM
     * 摘要执行，也用于熔断/冷却期降级。
     *
     * <p>MVP 用头尾截断；后续可把 {@link #compactOversizeContent} 换成"原文 offload 落盘、
     * context 留路径引用（可重新取回）"的实现，即 Claude Code 式 microcompaction。
     *
     * <p>保护规则：
     * <ul>
     *   <li>最后一条消息阈值更宽松（{@link #MICRO_COMPACT_LAST_TRIGGER_CHARS}），尽量不动当前上下文</li>
     *   <li>带图片附件（contentParts）的消息跳过，避免破坏多模态结构</li>
     * </ul>
     *
     * @return 是否有消息被截断
     */
    boolean microcompactOversizeMessages(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) return false;
        int lastIdx = history.size() - 1;
        boolean changed = false;
        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message msg = history.get(i);
            if (msg.hasContentParts()) continue; // 跳过多模态消息
            String content = msg.content();
            if (content == null) continue;

            boolean isLast = i == lastIdx;
            int trigger = isLast ? MICRO_COMPACT_LAST_TRIGGER_CHARS : MICRO_COMPACT_TRIGGER_CHARS;
            if (content.length() <= trigger) continue;

            int head = isLast ? MICRO_COMPACT_LAST_HEAD_CHARS : MICRO_COMPACT_HEAD_CHARS;
            int tail = isLast ? MICRO_COMPACT_LAST_TAIL_CHARS : MICRO_COMPACT_TAIL_CHARS;
            String compacted = compactOversizeContent(content, head, tail);
            if (compacted.length() < content.length()) {
                history.set(i, new LlmClient.Message(
                        msg.role(), compacted, msg.reasoningContent(), msg.toolCalls(), msg.toolCallId()));
                changed = true;
                log.info("microcompact truncated message[{}] role={}: {} -> {} chars",
                        i, msg.role(), content.length(), compacted.length());
            }
        }
        return changed;
    }

    /**
     * 头尾截断单条超大 content：保留头 {@code headChars} + 尾 {@code tailChars}，中间替换为标记。
     * 中间能省出的量太小（标记反而更长）时返回原文不截断。
     *
     * <p>扩展点：后续可改为把原文写盘、返回 "[结果已存盘 path=… 可重读]" 的引用形态，
     * 使被截断内容可重新取回。
     */
    private static String compactOversizeContent(String content, int headChars, int tailChars) {
        int removed = content.length() - headChars - tailChars;
        if (removed <= 200) {
            return content;
        }
        String head = content.substring(0, headChars);
        String tail = content.substring(content.length() - tailChars);
        return head
                + "\n\n[... microcompact 截断 " + removed + " 字符；已保留头尾，"
                + "完整内容可重新执行对应工具获取 ...]\n\n"
                + tail;
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

    /**
     * 滚动摘要超过 {@link #MAX_SUMMARY_CHARS} 时做一次"摘要的摘要"。
     * 再压缩失败或结果无效时保留原摘要（显式降级，打日志），不阻断压缩主流程。
     */
    private String capSummarySize(String summary) {
        if (summary == null || summary.length() <= MAX_SUMMARY_CHARS) {
            return summary;
        }
        // 先程序化 GC（不调 LLM）：解析九段 → 按段裁剪 → 渲染
        RollingSummary parsed = RollingSummary.parse(summary);
        if (!parsed.isEmpty()) {
            summaryGc.gc(parsed, MAX_SUMMARY_CHARS);
            String collected = parsed.render();
            if (collected.length() < summary.length()) {
                log.info("rolling summary GC'd: {} -> {} chars", summary.length(), collected.length());
                if (collected.length() <= MAX_SUMMARY_CHARS) {
                    return collected;
                }
                summary = collected; // GC 缩小但仍超，继续 LLM 兜底
            }
        }
        // 程序化 GC 不足（非九段格式无法解析，或裁后仍超）→ LLM recompress 兜底
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
