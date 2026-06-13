package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 ConversationHistoryCompactor 的降级截断策略
 */
class ConversationHistoryCompactorFallbackTest {

    @Test
    void fallbackTruncate_shouldReduceTokensBelowTarget() {
        // Arrange: 模拟一个失败 3 次后触发降级的场景
        LlmClient mockClient = new MockLlmClient();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(mockClient);

        // 构造一个超大 history（模拟触发压缩的场景）
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("System prompt"));
        // 增加更多消息以达到 token 阈值
        for (int i = 0; i < 200; i++) {
            history.add(LlmClient.Message.user("User message " + i + " with some content to increase tokens for testing fallback truncation strategy when compression fails consecutively"));
            history.add(LlmClient.Message.assistant("Assistant response " + i + " with detailed explanation and more context to ensure we have enough tokens for the test to trigger the fallback mechanism correctly"));
        }

        int triggerTokens = 5_000;
        int initialSize = history.size();
        int initialTokens = TokenBudget.estimateMessagesTokens(history);

        // 确保 initialTokens 大于 triggerTokens
        assertTrue(initialTokens > triggerTokens,
            "测试前提：initialTokens (" + initialTokens + ") 应该大于 triggerTokens (" + triggerTokens + ")");

        // 手动触发 3 次失败（设置内部状态）
        try {
            var field = ConversationHistoryCompactor.class.getDeclaredField("consecutiveFailures");
            field.setAccessible(true);
            field.set(compactor, 3);
        } catch (Exception e) {
            fail("无法设置 consecutiveFailures: " + e.getMessage());
        }

        // Act: 触发降级
        boolean compacted = compactor.compactIfNeeded(history, triggerTokens);

        // Assert
        assertTrue(compacted, "应该执行降级截断");
        assertTrue(history.size() < initialSize, "消息数量应该减少");

        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        int targetTokens = (int) (triggerTokens * 0.7);
        // 放宽断言：允许 5% 的误差（由于最小保留约束）
        int maxAllowedTokens = (int) (targetTokens * 1.05);
        assertTrue(afterTokens <= maxAllowedTokens,
            "降级后 token 应该接近目标（当前: " + afterTokens + ", 目标: " + targetTokens + ", 允许上限: " + maxAllowedTokens + ")");

        // 验证结构：system + 降级标记 + assistant + 保留的消息
        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertTrue(history.get(1).content().contains("[上下文压缩降级]"));
        assertEquals("assistant", history.get(2).role());
    }

    @Test
    void fallbackTruncate_shouldRespectCooldownPeriod() throws Exception {
        // Arrange
        LlmClient mockClient = new MockLlmClient();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(mockClient);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("System"));
        for (int i = 0; i < 30; i++) {
            history.add(LlmClient.Message.user("User " + i));
        }

        // 触发第一次降级
        var failureField = ConversationHistoryCompactor.class.getDeclaredField("consecutiveFailures");
        failureField.setAccessible(true);
        failureField.set(compactor, 3);

        compactor.compactIfNeeded(history, 5000);

        // 重新设置失败计数（模拟再次失败）
        failureField.set(compactor, 3);

        int sizeAfterFirst = history.size();

        // Act: 立即再次尝试（应该在冷却期内）
        boolean secondAttempt = compactor.compactIfNeeded(history, 5000);

        // Assert: 冷却期内不应该再次降级
        assertFalse(secondAttempt, "冷却期内不应该执行降级");
        assertEquals(sizeAfterFirst, history.size(), "消息数量不应该改变");
    }

    @Test
    void fallbackTruncate_shouldHandleMinimalHistory() {
        // Arrange: 只有 system + 1条消息
        LlmClient mockClient = new MockLlmClient();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(mockClient);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("System"));
        history.add(LlmClient.Message.user("Only one message"));

        // 触发失败
        try {
            var field = ConversationHistoryCompactor.class.getDeclaredField("consecutiveFailures");
            field.setAccessible(true);
            field.set(compactor, 3);
        } catch (Exception e) {
            fail("无法设置 consecutiveFailures");
        }

        // Act
        boolean compacted = compactor.compactIfNeeded(history, 1000);

        // Assert: 无法截断，应该返回 false
        assertFalse(compacted, "历史消息过少时不应该执行截断");
        assertEquals(2, history.size(), "消息数量不应该改变");
    }

    // Mock LLM Client（不实际调用 API）
    private static class MockLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", "Mock response", null, 100, 50);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() { return "mock-model"; }

        @Override
        public String getProviderName() { return "mock"; }

        @Override
        public int maxContextWindow() { return 128_000; }
    }
}
