package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorTest {

    @Test
    void doesNothingWhenBelowTrigger() {
        StubCompactor c = new StubCompactor("MOCK SUMMARY", 3);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("hi"));

        boolean compacted = c.compactIfNeeded(history, 100_000);

        assertFalse(compacted);
        assertEquals(2, history.size());
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void doesNothingWhenNoSplitPossible() {
        // 旧测试：retainRecent=3 但 user 只有 2 → 跳过
        // 新测试：retainTokens 太大，从尾巴累计找不到能切的 user 边界 → 跳过
        // 5_000 token 预算下，整段 history 总共才 ~80k token、4 个 user 消息，每条 ~20k；
        // 累计倒数第 1 个 user 就已经 ≥ 5k → splitIdx 落到那个 user，前面只有 1 条 user 可压
        // 这里用一个极端的 retainTokens 让 split 落到 systemEnd
        StubCompactor c = new StubCompactor("MOCK SUMMARY", 1_000_000_000, true); // 极大保留预算
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user(longText(20_000)));
        history.add(LlmClient.Message.assistant(longText(20_000)));
        history.add(LlmClient.Message.user(longText(20_000)));
        history.add(LlmClient.Message.assistant(longText(20_000)));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted, "保留预算大于整段 history 时，splitIdx 落到 systemEnd 应跳过");
        assertEquals(5, history.size());
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void compactsOldRoundsAndKeepsRecentTurns() {
        // 6 轮 user/assistant，纯英文每条 5000 chars ≈ 1250 token，整段 ~15k token
        // retainTokens=3_000：从尾巴累计 → A5+Q5 ≈ 2500，再走 A4 累计 3750，过线
        //                      → 找到首达标 user Q5；继续找下一个 → Q4 也达标
        //                      → splitIdx = Q4 的 idx，保留尾部 = Q4 起算 = 4 条消息
        StubCompactor c = new StubCompactor("MOCK SUMMARY OF OLD CONTENT", 3_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM_PROMPT"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("Q" + i + ": " + longText(5_000)));
            history.add(LlmClient.Message.assistant("A" + i + ": " + longText(5_000)));
        }

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        assertEquals(1, c.summarizeCalls.get());

        // 验证不变量：[system, user(摘要), assistant(确认), 保留尾部从某个 user 起算]
        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertTrue(history.get(1).content().contains("已压缩的历史对话摘要"));
        assertTrue(history.get(1).content().contains("MOCK SUMMARY OF OLD CONTENT"));
        assertEquals("assistant", history.get(2).role());
        assertEquals("user", history.get(3).role(),
                "保留区第一条必须是 user，否则违反 OpenAI chat 协议");
        // 保留尾部至少包含最后一对 user/assistant
        assertEquals("assistant", history.get(history.size() - 1).role());
        assertTrue(history.get(history.size() - 1).content().startsWith("A5"));
    }

    @Test
    void preservesToolCallPairAtSplitBoundary() {
        // 故意构造一个尾部带 tool_call/tool_result 的形态，验证不会被切断。
        // retainTokens=5：从尾巴累计 → RecentA2(2)+RecentQ2(4 user) < 5 不返回
        //                  → +done1+tool+tc_arg = 累计 12 → 遇到 RecentQ1(14 user) ≥ 5 → 返回 idx=5
        //                  splitIdx=5, 压缩区 = OldQ1/OldA1/OldQ2/OldA2，
        //                  保留区从 RecentQ1 开始包含所有 tool_call 配对
        StubCompactor c = new StubCompactor("SUMMARY", 5, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user("OldQ1: " + longText(3_000)));
        history.add(LlmClient.Message.assistant("OldA1"));
        history.add(LlmClient.Message.user("OldQ2"));
        history.add(LlmClient.Message.assistant("OldA2"));
        history.add(LlmClient.Message.user("RecentQ1"));
        List<LlmClient.ToolCall> tcs1 = List.of(new LlmClient.ToolCall("c1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        history.add(LlmClient.Message.assistant(null, null, tcs1));
        history.add(new LlmClient.Message("tool", "file content", null, null, "c1"));
        history.add(LlmClient.Message.assistant("done1"));
        history.add(LlmClient.Message.user("RecentQ2"));
        history.add(LlmClient.Message.assistant("RecentA2"));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        // 找到摘要后的第一个非摘要 user
        int firstUserAfterSummary = -1;
        for (int i = 0; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())
                    && !history.get(i).content().contains("已压缩的历史对话摘要")) {
                firstUserAfterSummary = i;
                break;
            }
        }
        assertTrue(firstUserAfterSummary > 0);
        // 关键不变量：保留区 tool_call 与 tool_result 配对完整
        java.util.Set<String> openCalls = new java.util.HashSet<>();
        java.util.Set<String> seenResults = new java.util.HashSet<>();
        for (int i = firstUserAfterSummary; i < history.size(); i++) {
            LlmClient.Message m = history.get(i);
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    openCalls.add(tc.id());
                }
            }
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                seenResults.add(m.toolCallId());
            }
        }
        assertEquals(openCalls, seenResults,
                "保留区 tool_call 与 tool_result 必须配对完整，不应被切到压缩区");
    }

    @Test
    void emptySummaryAbortsCompaction() {
        StubCompactor c = new StubCompactor("", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        int before = history.size();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted);
        assertEquals(before, history.size());
    }

    @Test
    void llmFailureDoesNotCorruptHistory() {
        StubCompactor c = new StubCompactor(null, 2) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                summarizeCalls.incrementAndGet();
                throw new IOException("LLM unavailable");
            }
        };
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        int before = history.size();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted);
        assertEquals(before, history.size());
    }

    @Test
    void splitPointAlwaysLandsOnUserRole() {
        // 旧轮次以 assistant 结尾，新轮次以 user 起头：
        // 验证 splitIdx 不会停在 assistant 上（否则尾部从 assistant 起头，违反 chat 协议）。
        StubCompactor c = new StubCompactor("SUMMARY", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i + " " + longText(2_000)));
        }

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        // [system, user(summary), assistant(ack), user(...), ...] 第 3 条必须是 user
        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertEquals("assistant", history.get(2).role());
        assertEquals("user", history.get(3).role(),
                "split 后保留区必须以 user 起头，否则违反 OpenAI chat 协议");
    }

    @Test
    void summarizeReceivesCompleteToolCallPairs() {
        // 关键契约：旧区域里的 assistant(tool_call) + tool(result) 必须成对进入 summarize 输入，
        // 不能出现 tool_call 进了 summarize、tool_result 留在保留尾部（反过来同理）。
        // 这是 "split 必落在 user 边界" 间接保证的不变量，需要显式断言。
        AtomicInteger orphanToolCallInOld = new AtomicInteger();
        AtomicInteger orphanToolResultInOld = new AtomicInteger();
        AtomicInteger orphanToolResultInTail = new AtomicInteger();
        // retainTokens=10：从尾巴累计 → RecentA2/Q2/A1/Q1 累计 ~8 → OldA2(1) 累计 9 → OldQ2(1 user) 累计 10 ≥ 10 → 返回
        //                  splitIdx=OldQ2 idx, 压缩区 = OldQ1+tool_call 整对，保留区 = OldA2 之后
        //                  ⚠️ 但 splitIdx 落 OldQ2 时保留区第一条是 OldQ2(user) ✓
        //                  压缩区 = OldQ1(user) + assistant(tcsOld1) + tool(old-1) + OldA1，保留 old-1 配对
        //                  OldQ2 起的尾部：OldQ2 + assistant(tcsOld2) + tool(old-2) + OldA2 + RecentQ1/A1/Q2/A2
        //                  ⚠️ 这样 old-2 的 tool_call+tool 都在保留区，old-1 的都在压缩区，配对都不破
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 10, true) {
            @Override
            protected String summarize(List<LlmClient.Message> oldMsgs) {
                // 检查 oldMsgs 中所有 tool_call 都能在同一个列表里找到对应 tool_call_id 的 tool result
                List<String> toolCallIds = new ArrayList<>();
                List<String> toolResultIds = new ArrayList<>();
                for (LlmClient.Message m : oldMsgs) {
                    if (m.toolCalls() != null) {
                        for (LlmClient.ToolCall tc : m.toolCalls()) {
                            toolCallIds.add(tc.id());
                        }
                    }
                    if ("tool".equals(m.role()) && m.toolCallId() != null) {
                        toolResultIds.add(m.toolCallId());
                    }
                }
                for (String id : toolCallIds) {
                    if (!toolResultIds.contains(id)) orphanToolCallInOld.incrementAndGet();
                }
                for (String id : toolResultIds) {
                    if (!toolCallIds.contains(id)) orphanToolResultInOld.incrementAndGet();
                }
                return "SUMMARY";
            }
        };

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        // 旧轮次 1：完整 tool_call → tool_result 对（应整对进 summarize）
        history.add(LlmClient.Message.user("OldQ1 " + longText(3_000)));
        List<LlmClient.ToolCall> tcsOld1 = List.of(new LlmClient.ToolCall("old-1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        history.add(LlmClient.Message.assistant(null, null, tcsOld1));
        history.add(new LlmClient.Message("tool", "old content 1", null, null, "old-1"));
        history.add(LlmClient.Message.assistant("OldA1"));
        // 旧轮次 2：再来一对，验证多对都能成对进 summarize
        history.add(LlmClient.Message.user("OldQ2 " + longText(3_000)));
        List<LlmClient.ToolCall> tcsOld2 = List.of(new LlmClient.ToolCall("old-2",
                new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}")));
        history.add(LlmClient.Message.assistant(null, null, tcsOld2));
        history.add(new LlmClient.Message("tool", "old content 2", null, null, "old-2"));
        history.add(LlmClient.Message.assistant("OldA2"));
        // 保留区两轮（每轮 user/assistant ~50 字符）
        history.add(LlmClient.Message.user("RecentQ1"));
        history.add(LlmClient.Message.assistant("RecentA1"));
        history.add(LlmClient.Message.user("RecentQ2"));
        history.add(LlmClient.Message.assistant("RecentA2"));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        assertEquals(0, orphanToolCallInOld.get(),
                "旧区域不应出现孤立 tool_call（其 tool_result 被切到保留区）");
        assertEquals(0, orphanToolResultInOld.get(),
                "旧区域不应出现孤立 tool_result（其 tool_call 被切到保留区）");
        // 保留区不应该残留任何 tool_call_id 是 old-* 的 tool message
        for (int i = 1; i < history.size(); i++) {
            LlmClient.Message m = history.get(i);
            if (i == 1 && m.content() != null && m.content().contains("已压缩的历史对话摘要")) continue;
            if ("tool".equals(m.role()) && m.toolCallId() != null
                    && m.toolCallId().startsWith("old-")) {
                orphanToolResultInTail.incrementAndGet();
            }
        }
        assertEquals(0, orphanToolResultInTail.get(),
                "保留尾部不应残留属于旧轮次的 tool_result");
    }

    @Test
    void compactionStrictlyReducesTokenCount() {
        // 简短摘要 + 巨大旧轮次 → 压缩后整体 token 必须严格下降
        StubCompactor c = new StubCompactor("SHORT SUMMARY", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(8_000)));
            history.add(LlmClient.Message.assistant("A" + i + " " + longText(8_000)));
        }
        int before = TokenBudget.estimateMessagesTokens(history);

        boolean compacted = c.compactIfNeeded(history, 100);
        int after = TokenBudget.estimateMessagesTokens(history);

        assertTrue(compacted);
        assertTrue(after < before,
                "压缩后 token 必须严格小于压缩前，否则 trigger 永远满足导致死循环");
    }

    @Test
    void handlesHistoryWithoutSystemPrompt() {
        // systemEnd=0 路径：纯 user/assistant 列表也能安全压缩
        // 整段 token：5 轮，每轮 user ~750 + assistant ~1 ≈ 751，整段 ~3755 token
        // retainTokens=200：从尾巴累计 → A4(1)+Q4(750 命中) ≥ 200 → 首达标 user=Q4
        //                   → A3(1)+Q3(750 命中) → splitIdx=Q3 idx=6
        //                   保留尾部 = Q3,A3,Q4,A4 共 4 条
        StubCompactor c = new StubCompactor("SUMMARY", 200, true);
        List<LlmClient.Message> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(3_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        assertEquals("user", history.get(0).role());
        assertTrue(history.get(0).content().contains("已压缩的历史对话摘要"));
        assertEquals("assistant", history.get(1).role());
        assertEquals("user", history.get(2).role(),
                "保留区必须以 user 起头");
        long userCount = history.stream().filter(m -> "user".equals(m.role())).count();
        assertTrue(userCount >= 2, "应至少有摘要 user + 至少 1 个保留 user");
    }

    @Test
    void detectsPreviousSummaryAndUsesIncrementalPath() {
        // PR-1 新增：当 history 头已有上一轮压缩留下的摘要 user 时，
        // 走 summarizeIncremental 而不是 summarize，避免摘要套娃
        StubCompactor c = new StubCompactor("INCREMENTAL_OUTPUT", 2_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        // 模拟"上一轮压缩留下的摘要"：以 SUMMARY_MARKER 起头的 user + assistant 确认
        history.add(LlmClient.Message.user(
                ConversationHistoryCompactor.SUMMARY_MARKER + "old summary content"));
        history.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        // 之后又积累的对话
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("NewQ" + i + " " + longText(3_000)));
            history.add(LlmClient.Message.assistant("NewA" + i));
        }

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        // 关键：走的是增量路径，summarize 应该 0 次，summarizeIncremental 应该 1 次
        assertEquals(0, c.summarizeCalls.get(), "增量场景不应再调全量 summarize");
        assertEquals(1, c.incrementalCalls.get(), "应走 summarizeIncremental 路径");
        // 重建后头部应该是新的摘要 user，摘要文本是 stub 输出
        assertTrue(history.get(1).content().contains("INCREMENTAL_OUTPUT"));
        assertFalse(history.get(1).content().contains("old summary content"),
                "重建后老摘要应被新摘要替换，不残留");
    }

    private static String longText(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) sb.append('x');
        return sb.toString();
    }

    @Test
    void circuitBreakerTripsAfterMaxConsecutiveFailures() {
        // 模拟连续 LLM 失败：第 1、2、3 次返回 IOException → 第 4 次起触发降级截断
        AtomicInteger summarizeAttempts = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                summarizeAttempts.incrementAndGet();
                throw new IOException("simulated LLM outage");
            }
        };
        List<LlmClient.Message> history = buildBigHistory();

        for (int i = 0; i < ConversationHistoryCompactor.MAX_CONSECUTIVE_FAILURES; i++) {
            assertFalse(c.compactIfNeeded(history, 100), "失败应返回 false");
        }
        assertTrue(c.isCircuitOpen(), "达到上限应熔断");
        assertEquals(ConversationHistoryCompactor.MAX_CONSECUTIVE_FAILURES, summarizeAttempts.get());

        // 熔断后再次调用：触发降级截断（返回 true），不再调用 summarize
        int historyBeforeFallback = history.size();
        assertTrue(c.compactIfNeeded(history, 100), "熔断后应执行降级截断");
        assertTrue(history.size() < historyBeforeFallback, "降级截断应减少消息数量");
        assertEquals(ConversationHistoryCompactor.MAX_CONSECUTIVE_FAILURES, summarizeAttempts.get(),
                "降级截断不应调用 LLM");
    }

    @Test
    void circuitBreakerResetsOnSuccessfulCompaction() {
        // 前两次失败、第 3 次成功 → consecutiveFailures 应回到 0
        AtomicInteger attempt = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                int n = attempt.incrementAndGet();
                if (n <= 2) throw new IOException("transient failure " + n);
                return "RECOVERY SUMMARY";
            }
        };
        // 失败 1
        assertFalse(c.compactIfNeeded(buildBigHistory(), 100));
        assertEquals(1, c.getConsecutiveFailures());
        // 失败 2
        assertFalse(c.compactIfNeeded(buildBigHistory(), 100));
        assertEquals(2, c.getConsecutiveFailures());
        // 成功 3 → 应清零
        assertTrue(c.compactIfNeeded(buildBigHistory(), 100));
        assertEquals(0, c.getConsecutiveFailures());
        assertFalse(c.isCircuitOpen());
    }

    @Test
    void emptySummaryAlsoCountsAsFailure() {
        AtomicInteger attempt = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) {
                attempt.incrementAndGet();
                return "   "; // 空白摘要
            }
        };
        for (int i = 0; i < ConversationHistoryCompactor.MAX_CONSECUTIVE_FAILURES; i++) {
            assertFalse(c.compactIfNeeded(buildBigHistory(), 100));
        }
        assertTrue(c.isCircuitOpen(), "空摘要也应触发熔断");
    }

    @Test
    void manualResetAllowsCompactionAgain() {
        AtomicInteger attempt = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                int n = attempt.incrementAndGet();
                // 前 3 次失败、之后成功
                if (n <= ConversationHistoryCompactor.MAX_CONSECUTIVE_FAILURES) {
                    throw new IOException("burst failure " + n);
                }
                return "POST-RESET SUMMARY";
            }
        };
        // 把熔断器打到熔断
        for (int i = 0; i < ConversationHistoryCompactor.MAX_CONSECUTIVE_FAILURES; i++) {
            c.compactIfNeeded(buildBigHistory(), 100);
        }
        assertTrue(c.isCircuitOpen());

        // 手动 reset
        c.resetCircuitBreaker();
        assertFalse(c.isCircuitOpen());
        assertEquals(0, c.getConsecutiveFailures());

        // 之后能再次成功压缩
        assertTrue(c.compactIfNeeded(buildBigHistory(), 100));
    }

    @Test
    void splitImpossibleDoesNotIncrementFailureCounter() {
        // 结构性 split 失败（retainTokens 太大）不算 LLM 失败，不应触发熔断
        StubCompactor c = new StubCompactor("MOCK", 1_000_000_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user(longText(20_000)));

        for (int i = 0; i < 10; i++) {
            assertFalse(c.compactIfNeeded(history, 100));
        }
        assertEquals(0, c.getConsecutiveFailures(), "结构性 split 失败不应进入熔断计数");
        assertFalse(c.isCircuitOpen());
    }

    // ─────────────────────────────────────────────────────────
    // PTL retry 测试：摘要调用自身 OOM 时按 user 边界丢头部 round 重试
    // ─────────────────────────────────────────────────────────

    @Test
    void ptlRetrySucceedsOnSecondAttemptAfterDroppingOldestRound() {
        // 第 1 次摘要返回 prompt-too-long，第 2 次（消息变少后）成功
        AtomicInteger attempt = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                int n = attempt.incrementAndGet();
                if (n == 1) {
                    throw new IOException("prompt is too long: 250000 tokens exceeds maximum 200000");
                }
                return "RECOVERY AFTER PTL";
            }
        };

        boolean ok = c.compactIfNeeded(buildBigHistory(), 100);
        assertTrue(ok, "PTL retry 应该让第 2 次摘要成功");
        assertEquals(2, attempt.get(), "应当尝试 2 次：第 1 次 PTL，第 2 次成功");
        assertEquals(0, c.getConsecutiveFailures(), "成功后失败计数应清零");
    }

    @Test
    void ptlRetryGivesUpAfterMaxAttempts() {
        // 连续 4 次（首次 + 3 次 retry）都返回 PTL → 计入 1 次 consecutiveFailures
        AtomicInteger attempt = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                attempt.incrementAndGet();
                throw new IOException("context length exceeded: too many tokens");
            }
        };

        assertFalse(c.compactIfNeeded(buildBigHistory(), 100));
        assertEquals(1 + ConversationHistoryCompactor.MAX_PTL_RETRIES, attempt.get(),
                "应当尝试首次 + MAX_PTL_RETRIES 次 retry");
        assertEquals(1, c.getConsecutiveFailures(), "PTL exhausted 应计入一次 consecutiveFailures");
    }

    @Test
    void nonPtlIoExceptionGoesDirectlyToFailureCounter() {
        // network / auth 类错误不应触发 PTL retry
        AtomicInteger attempt = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                attempt.incrementAndGet();
                throw new IOException("connection reset by peer");
            }
        };

        assertFalse(c.compactIfNeeded(buildBigHistory(), 100));
        assertEquals(1, attempt.get(), "非 PTL 错误不应重试");
        assertEquals(1, c.getConsecutiveFailures());
    }

    @Test
    void ptlRetryDropsRoundsAtUserBoundary() {
        // 验证 dropOldestRoundsByRatio 切割点对齐 user 边界
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.user("Q1"));
        messages.add(LlmClient.Message.assistant("A1"));
        messages.add(LlmClient.Message.user("Q2"));
        messages.add(LlmClient.Message.assistant("A2"));
        messages.add(LlmClient.Message.user("Q3"));
        messages.add(LlmClient.Message.assistant("A3"));
        messages.add(LlmClient.Message.user("Q4"));
        messages.add(LlmClient.Message.assistant("A4"));
        messages.add(LlmClient.Message.user("Q5"));
        messages.add(LlmClient.Message.assistant("A5"));

        // 5 个 round，drop 20% = ceil(1) = 1 round → 保留 4 个 round = 8 条消息
        List<LlmClient.Message> trimmed = ConversationHistoryCompactor
                .dropOldestRoundsByRatio(messages, 0.20);
        assertEquals(8, trimmed.size());
        assertEquals("user", trimmed.get(0).role(), "保留段必须以 user 起头");
        assertEquals("Q2", trimmed.get(0).content());
    }

    @Test
    void isPromptTooLongErrorRecognizesCommonPhrasings() {
        // 各家 provider 错误措辞都应能识别
        assertTrue(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("prompt is too long")));
        assertTrue(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("context length exceeded the model's maximum")));
        assertTrue(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("input is too long: 300000 tokens")));
        assertTrue(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("Request too large for this model")));
        // 嵌套 cause 也应识别
        assertTrue(ConversationHistoryCompactor.isPromptTooLongError(
                new RuntimeException("wrapper", new IOException("tokens exceeds maximum"))));
        // 非 PTL 错误
        assertFalse(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("connection refused")));
        assertFalse(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("401 unauthorized")));
        // max_tokens 是输出参数配置错误，不是 prompt 超长，不应触发丢历史重试
        assertFalse(ConversationHistoryCompactor.isPromptTooLongError(
                new IOException("max_tokens must be greater than thinking.budget_tokens")));
    }

    /** 构造一段足够大、可以 split 的 history。 */
    private static List<LlmClient.Message> buildBigHistory() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM"));
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i + " " + longText(2_000)));
        }
        return history;
    }

    /** 测试用 stub：summarize 返回固定字符串，避免真实 LLM 依赖。 */
    private static class StubCompactor extends ConversationHistoryCompactor {
        final AtomicInteger summarizeCalls = new AtomicInteger();
        final AtomicInteger incrementalCalls = new AtomicInteger();
        private final String mockSummary;

        /** 兼容老 API：retainRecent ≈ 1000 token/轮 折算。 */
        StubCompactor(String mockSummary, int retainRecent) {
            super(null, retainRecent);
            this.mockSummary = mockSummary;
        }

        /** 新 API：直接传 token 预算。 */
        StubCompactor(String mockSummary, int retainTokens, boolean tokensFlag) {
            super(null, retainTokens, tokensFlag);
            this.mockSummary = mockSummary;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) throws IOException {
            summarizeCalls.incrementAndGet();
            return mockSummary;
        }

        @Override
        protected String summarizeIncremental(String previousSummary,
                                              List<LlmClient.Message> newMessages) throws IOException {
            incrementalCalls.incrementAndGet();
            return mockSummary;
        }
    }
}
