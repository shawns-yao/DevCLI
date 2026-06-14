package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * microcompact（第 0 层）测试：截断单条超大消息，不调 LLM、不删消息、不破坏 tool_call 配对。
 */
class ConversationHistoryCompactorMicrocompactTest {

    @Test
    void truncatesOversizeNonLastMessageAndKeepsMetadata() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user("Q"));
        // 30k 字符的工具结果，非最后一条 → 超普通阈值 24k 被截断
        history.add(new LlmClient.Message("tool", "x".repeat(30_000), null, null, "c1"));
        history.add(LlmClient.Message.assistant("done"));

        boolean changed = c.microcompactOversizeMessages(history);

        assertTrue(changed);
        String compacted = history.get(2).content();
        assertTrue(compacted.length() < 30_000, "超大工具结果应被截断");
        assertTrue(compacted.contains("microcompact 截断"), "应保留截断标记");
        assertEquals("c1", history.get(2).toolCallId(), "截断不应丢 toolCallId（保 tool_call 配对）");
        assertEquals("tool", history.get(2).role(), "role 不变");
        assertEquals(4, history.size(), "microcompact 不删消息");
    }

    @Test
    void lastMessageUsesLooserThreshold() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);

        // 30k 字符作为最后一条：< 48k 宽松阈值 → 不截断（保护当前上下文）
        List<LlmClient.Message> asLast = new ArrayList<>();
        asLast.add(LlmClient.Message.user("x".repeat(30_000)));
        assertFalse(c.microcompactOversizeMessages(asLast), "30k 作为最后一条不应被截断");

        // 同样 30k 但非最后一条 → 超普通阈值 24k → 截断
        List<LlmClient.Message> notLast = new ArrayList<>();
        notLast.add(LlmClient.Message.user("x".repeat(30_000)));
        notLast.add(LlmClient.Message.assistant("end"));
        assertTrue(c.microcompactOversizeMessages(notLast), "30k 非最后一条应被截断");
    }

    @Test
    void lastMessageStillTruncatedAboveAbsoluteCap() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.user("x".repeat(60_000))); // 最后一条但超 48k 绝对上限

        boolean changed = c.microcompactOversizeMessages(history);

        assertTrue(changed, "最后一条超绝对上限仍须截断，避免单条撑爆保留区");
        assertTrue(history.get(0).content().length() < 60_000);
    }

    @Test
    void skipsMultimodalMessages() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        List<LlmClient.ContentPart> parts = List.of(
                LlmClient.ContentPart.text("x".repeat(30_000)),
                LlmClient.ContentPart.imageBase64("base64data", "image/png"));
        history.add(LlmClient.Message.user(parts)); // hasContentParts() == true
        history.add(LlmClient.Message.assistant("end"));

        assertFalse(c.microcompactOversizeMessages(history), "带图片附件的消息应跳过 microcompact");
    }

    @Test
    void doesNotTruncateBelowThreshold() {
        // 20k 字符 < 24k 阈值：不截断。这同时保护既有测试里 longText(20000) 的消息不被 microcompact 改动。
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.user("Q"));
        history.add(new LlmClient.Message("tool", "x".repeat(20_000), null, null, "c1"));
        history.add(LlmClient.Message.assistant("end"));

        assertFalse(c.microcompactOversizeMessages(history), "低于阈值不应截断");
    }

    @Test
    void microcompactAloneAvoidsLlmSummarization() {
        AtomicInteger summarizeCalls = new AtomicInteger();
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) {
                summarizeCalls.incrementAndGet();
                return "SUMMARY";
            }

            @Override
            protected String summarizeIncremental(String previousSummary, List<LlmClient.Message> newMessages) {
                summarizeCalls.incrementAndGet();
                return "SUMMARY";
            }
        };
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user("hi"));
        // 单条巨型工具结果（120k 字符 ≈ 30k token），非最后一条
        history.add(new LlmClient.Message("tool", "x".repeat(120_000), null, null, "c1"));
        history.add(LlmClient.Message.assistant("done"));

        int before = TokenBudget.estimateMessagesTokens(history);
        assertTrue(before > 5_000, "前提：micro 前应远超 trigger");

        boolean compacted = c.compactIfNeeded(history, 5_000);

        assertFalse(compacted, "micro-only 不算历史压缩，返回 false 避免调用方打印误导提示");
        assertEquals(0, summarizeCalls.get(), "micro 扛住后不应调 LLM 摘要");
        assertTrue(history.get(2).content().length() < 120_000, "巨型工具结果应被截断");
        assertEquals(4, history.size(), "micro 不删消息");
        assertTrue(TokenBudget.estimateMessagesTokens(history) < 5_000, "micro 后应低于 trigger");
    }
}
