package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 27-E 端到端稳定性：连续多轮压缩后，滚动摘要不随轮次线性增长。
 *
 * <p>这是现有压缩测试唯一未覆盖的 27-E 场景。其余场景已分别由
 * {@link ConversationHistoryCompactorTest}（tool_call 配对、user 边界、单次 token 下降、
 * 增量不套娃、熔断、PTL）与 {@link SummaryGarbageCollectorTest}（按段折叠/截断）覆盖。
 *
 * <p><b>边界（诚实标注）</b>：覆盖事实 A→B→C 只留 C、已完成里程碑折一行 等属于 LLM 摘要的
 * <i>语义质量</i>，依赖真实模型输出，无法用固定 stub 确定性断言——那类验证需放
 * test-benchmark + API key 的集成评测，不在本单测范围。本测试只验证程序化、可确定性断言的
 * "摘要有界性"（增量摘要 + capSummarySize 程序化 GC 联动的结果）。
 */
class ConversationHistoryCompactorStabilityTest {

    private static String x(int n) {
        return "x".repeat(n);
    }

    /** 九段摘要，把膨胀内容堆到低优先段「问题解决过程」（GC 的 TRUNCATE_ORDER 覆盖它）。 */
    private static String nineSection(String process) {
        return "## 主要请求与意图\n重构记忆模块\n## 关键技术概念\n无\n## 文件和代码\n无\n"
                + "## 踩过的坑和修复\n无\n## 问题解决过程\n" + process
                + "\n## 逐条用户消息\n无\n## 待办任务\n无\n## 当前在做什么\n无\n## 下一步\n无";
    }

    /**
     * 模拟真实增量摘要的"只追加"膨胀：每轮往低优先段再堆 20k 字符。
     * 若没有程序化 GC，5 轮后摘要会线性堆到 ~100k；验证 capSummarySize 持续把它压回
     * MAX_SUMMARY_CHARS 附近，峰值有界、不随轮次线性增长。
     */
    @Test
    void repeatedCompactionKeepsSummaryBounded() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) {
                return nineSection(x(20_000));
            }

            @Override
            protected String summarizeIncremental(String previousSummary, List<LlmClient.Message> newMessages) {
                // 增量：在上轮（已被 GC 过的）摘要低优先段继续追加，模拟单调膨胀
                RollingSummary prev = RollingSummary.parse(previousSummary);
                prev.set("问题解决过程", prev.get("问题解决过程") + x(20_000));
                return prev.render();
            }
        };

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYS"));

        int maxSummaryLen = 0;
        int compactions = 0;
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 4; i++) {
                history.add(LlmClient.Message.user("Q" + round + "_" + i + " " + x(2_000)));
                history.add(LlmClient.Message.assistant("A" + round + "_" + i + " " + x(2_000)));
            }
            boolean compacted = c.compactIfNeeded(history, 100);
            if (compacted) {
                compactions++;
                // 重建后 [system, user(摘要), assistant(ack), 保留尾部]，摘要在 index 1
                String summary = history.get(1).content();
                assertTrue(summary.contains(ConversationHistoryCompactor.SUMMARY_MARKER.trim())
                                || summary.contains("主要请求与意图"),
                        "头部应是摘要消息");
                maxSummaryLen = Math.max(maxSummaryLen, summary.length());
            }
        }

        assertTrue(compactions >= 3, "应发生多次压缩，实际: " + compactions);
        assertTrue(maxSummaryLen <= ConversationHistoryCompactor.MAX_SUMMARY_CHARS + 2_000,
                "连续压缩后摘要应被程序化 GC 收敛在上限附近，实际峰值: " + maxSummaryLen);
        // 反证：若无 GC，5 轮增量会线性堆到 ~100k；这里远小于，证明非线性增长
        assertTrue(maxSummaryLen < 40_000,
                "摘要峰值远小于无 GC 时的线性累积量(~100k)，实际: " + maxSummaryLen);
    }

    /**
     * 第二轮起走增量路径：验证连续压缩不会反复全量重压旧内容（增量摘要的核心收益），
     * 同时摘要峰值仍受 GC 约束。
     */
    @Test
    void incrementalPathEngagesAcrossRoundsWithoutSummaryBlowup() {
        int[] fullCalls = {0};
        int[] incrementalCalls = {0};
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) {
                fullCalls[0]++;
                return nineSection("首轮全量摘要 " + x(2_000));
            }

            @Override
            protected String summarizeIncremental(String previousSummary, List<LlmClient.Message> newMessages) {
                incrementalCalls[0]++;
                RollingSummary prev = RollingSummary.parse(previousSummary);
                prev.set("问题解决过程", prev.get("问题解决过程") + "\n增量" + incrementalCalls[0] + " " + x(1_000));
                return prev.render();
            }
        };

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYS"));
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 4; i++) {
                history.add(LlmClient.Message.user("Q" + round + "_" + i + " " + x(2_000)));
                history.add(LlmClient.Message.assistant("A" + round + "_" + i + " " + x(2_000)));
            }
            c.compactIfNeeded(history, 100);
        }

        assertTrue(fullCalls[0] <= 1, "全量摘要至多 1 次（仅首轮），实际: " + fullCalls[0]);
        assertTrue(incrementalCalls[0] >= 2, "后续轮次应走增量路径，实际: " + incrementalCalls[0]);
        assertTrue(history.get(1).content().length() <= ConversationHistoryCompactor.MAX_SUMMARY_CHARS + 2_000,
                "增量累积下摘要仍受 GC 约束");
    }
}
