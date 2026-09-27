package com.devcli.hitl;

import com.devcli.llm.LlmClient;

import java.util.ArrayList;
import java.util.Collections;
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

    private static final int MAX_ENTRY_CHARS = 1_200;
    private static final int MAX_TOTAL_CHARS = 12_000;
    private static final String EMPTY_NOTICE = "（本次会话尚无可信用户意图）";

    private TrustedIntentContext() {
    }

    public static String render(List<LlmClient.Message> history) {
        List<String> entries = new ArrayList<>();
        int used = 0;
        if (history != null) {
            for (int index = history.size() - 1; index >= 0 && used < MAX_TOTAL_CHARS; index--) {
                String entry = entry(history.get(index));
                if (entry == null || used + entry.length() > MAX_TOTAL_CHARS) {
                    continue;
                }
                entries.add(entry);
                used += entry.length();
            }
        }
        Collections.reverse(entries);

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
                    : "User: " + neutralize(truncate(content.strip()));
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
        return calls.isEmpty() ? null : truncate(calls.toString());
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ENTRY_CHARS
                ? text
                : text.substring(0, MAX_ENTRY_CHARS) + "…（本条已截断）";
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
