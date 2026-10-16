package com.devcli.memory;

import com.devcli.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;

/** 摘要之外保留预算内的用户原文；派生注入和工具图片说明不冒充用户意图。 */
final class UserMessageRetention {
    private static final int MAX_TOKENS = 20_000;
    private UserMessageRetention() { }

    static List<LlmClient.Message> select(List<LlmClient.Message> history, int remainingTokens) {
        int budget = Math.min(MAX_TOKENS, Math.max(0, remainingTokens));
        List<LlmClient.Message> retained = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            var message = history.get(i);
            if (!"user".equals(message.role()) || !List.of(LlmClient.MessageSource.USER,
                    LlmClient.MessageSource.STEERING, LlmClient.MessageSource.FOLLOW_UP).contains(message.source())) continue;
            var text = message.withoutImageContent().withoutReasoningContent();
            int tokens = TokenBudget.estimateMessagesTokens(List.of(text));
            if (tokens > budget) break;
            retained.add(0, text);
            budget -= tokens;
        }
        return List.copyOf(retained);
    }
}
