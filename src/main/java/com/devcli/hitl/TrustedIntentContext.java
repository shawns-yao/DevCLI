package com.devcli.hitl;

import com.devcli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 为权限分类器提取可信意图上下文。
 *
 * <p>只保留当前会话中的真实用户消息，以及 Assistant 已经发起的结构化工具调用。Assistant 自述、
 * 工具结果、系统注入、插件内容和委派报告都不是用户授权证据，直接排除。</p>
 */
public final class TrustedIntentContext {
    static final String OPEN_TAG = "<intent_context>";
    static final String CLOSE_TAG = "</intent_context>";

    private static final int MAX_TOTAL_CHARS = 12_000;
    private static final String EMPTY_NOTICE = "（本次会话尚无可信用户意图）";

    private TrustedIntentContext() {
    }

    public static String render(List<LlmClient.Message> history) {
        List<String> entries = new ArrayList<>();
        int used = 0;
        if (history != null) {
            for (LlmClient.Message message : history) {
                String entry = entry(message);
                if (entry == null) continue;
                if (entry.length() + 1 > MAX_TOTAL_CHARS - used) throw new IncompleteContextException();
                entries.add(entry);
                used += entry.length() + 1;
            }
        }

        StringBuilder result = new StringBuilder(OPEN_TAG).append('\n');
        if (entries.isEmpty()) {
            result.append(EMPTY_NOTICE).append('\n');
        } else {
            entries.forEach(entry -> result.append(entry).append('\n'));
        }
        return result.append(CLOSE_TAG).toString();
    }

    private static String entry(LlmClient.Message message) {
        if (message == null) {
            return null;
        }
        LlmClient.MessageSource source = message.source();
        if (source == LlmClient.MessageSource.USER
                || source == LlmClient.MessageSource.STEERING
                || source == LlmClient.MessageSource.FOLLOW_UP) {
            String content = message.content();
            return content == null || content.isBlank()
                    ? null
                    : "User: " + neutralize(content.strip());
        }
        if (source != LlmClient.MessageSource.ASSISTANT || message.toolCalls() == null) {
            return null;
        }
        StringBuilder calls = new StringBuilder();
        for (LlmClient.ToolCall call : message.toolCalls()) {
            if (call == null || call.function() == null) {
                continue;
            }
            if (!calls.isEmpty()) {
                calls.append('\n');
            }
            String payload = call.function().name() + " " + call.function().arguments();
            calls.append("ToolCall: ").append(neutralize(payload));
        }
        return calls.isEmpty() ? null : calls.toString();
    }

    /** 不允许将缺失约束的投影当作完整授权上下文。 */
    public static final class IncompleteContextException extends IllegalStateException {
        public IncompleteContextException() {
            super("可信用户意图超出预算或历史已缺失，无法安全自动审批；请单次确认此操作");
        }
    }

    static String neutralize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String escaped = text
                .replace(OPEN_TAG, "\\" + OPEN_TAG)
                .replace(CLOSE_TAG, "\\" + CLOSE_TAG);
        return escaped.replaceAll("(?m)^([ \\t]*)(User|ToolCall):", "$1$2\\\\:");
    }
}
