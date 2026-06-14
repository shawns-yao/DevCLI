package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 九段结构化摘要的集成测试：full compact 产出九段、超长摘要被程序化 GC 裁剪。
 */
class ConversationHistoryCompactorNineSectionTest {

    private static String longText(int n) {
        return "x".repeat(n);
    }

    private static List<LlmClient.Message> buildBigHistory() {
        List<LlmClient.Message> h = new ArrayList<>();
        h.add(LlmClient.Message.system("SYSTEM"));
        for (int i = 0; i < 6; i++) {
            h.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            h.add(LlmClient.Message.assistant("A" + i + " " + longText(2_000)));
        }
        return h;
    }

    @Test
    void oversizeNineSectionSummaryGetsProgrammaticallyGCd() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 2_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) {
                // 超长九段摘要：问题解决过程段塞 20k 字符（低优先，应被 GC 截）
                return "## 主要请求与意图\n重构记忆\n## 关键技术概念\n无\n## 文件和代码\n无\n"
                        + "## 踩过的坑和修复\n无\n## 问题解决过程\n" + longText(20_000)
                        + "\n## 逐条用户消息\n无\n## 待办任务\n无\n## 当前在做什么\n无\n## 下一步\n无";
            }
        };
        List<LlmClient.Message> history = buildBigHistory();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        // 重建后头部是摘要 user：[system, user(摘要), assistant(ack), 保留尾部]
        String summaryMsg = history.get(1).content();
        assertTrue(summaryMsg.contains("主要请求与意图"), "重建后应是九段结构");
        assertTrue(summaryMsg.contains("重构记忆"), "高优先段(意图)应保留原文");
        assertTrue(summaryMsg.contains("已折叠"), "超长低优先段(问题解决过程)应被程序化 GC 截断");
        assertTrue(summaryMsg.length() < 20_000, "整体应被裁剪，不再是 20k+ 原样");
    }
}
