package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically extracts protected entities from history messages. */
public final class CompactionFactExtractor {
    private static final Pattern PATH = Pattern.compile("(?i)(?:[A-Za-z]:\\\\|/)[^\\s\\\"']+");
    private static final Pattern ERROR = Pattern.compile(
            "(?i)\\b(?:error|exit[ _-]?code|错误码)\\s*[:=]?\\s*([A-Z0-9_-]+)");
    private static final Pattern COMMAND = Pattern.compile("(?m)^\\s*(?:[$>]\\s*)?((?:mvn|java|javac|git|docker)\\s+[^\\r\\n]+)");
    private static final Pattern CONFIG = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]{2,}|devcli\\.[a-z0-9_.-]+)\\s*=\\s*([^\\s,;]+)");
    private static final Pattern SYMBOL = Pattern.compile("\\b(?:class|interface|enum|record|method)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern UNRESOLVED = Pattern.compile("(?i)(?:未解决|待处理|unresolved|pending)[:：]?\\s*([^\\r\\n]+)");
    private static final Pattern DECISION = Pattern.compile("(?i)(?:决定|采用|改为|use|choose)\\s+([^\\s:=：]+)\\s*(?:为|=|:|：)\\s*([^\\r\\n,]+)");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])\\b\\d+(?:\\.\\d+)?(?:%|ms|s|K|M)?\\b");
    private static final Pattern MODIFIED = Pattern.compile("(?i)(?:modified|修改(?:了|文件)?|changed)[:：]?\\s*((?:[A-Za-z]:\\\\|/|src/)[^\\s,;]+)");

    public void extract(List<LlmClient.Message> messages, CompactionFactLedger ledger) {
        extract(messages, ledger, null);
    }

    public void extract(List<LlmClient.Message> messages,
                        CompactionFactLedger ledger,
                        CompactionContext context) {
        if (messages == null || ledger == null) return;
        String sessionId = context == null ? "" : context.sessionId();
        long contextEpoch = context == null ? 0L : context.contextEpoch();
        for (int index = 0; index < messages.size(); index++) {
            LlmClient.Message message = messages.get(index);
            long sequence = sourceSequence(index, messages.size(), message, context);
            String sourceMessageId = sessionId.isBlank()
                    ? "history:" + index
                    : sessionId + ":message:" + index + ":event:" + sequence;
            String sourceToolCallId = message == null || message.toolCallId() == null
                    ? "" : message.toolCallId();
            String text = message == null || message.content() == null ? "" : message.content();
            Matcher paths = PATH.matcher(text);
            while (paths.find()) add(ledger, CompactionFactLedger.Type.FILE_PATH, paths.group(),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher errors = ERROR.matcher(text);
            while (errors.find()) add(ledger, CompactionFactLedger.Type.ERROR_CODE, errors.group(1),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher commands = COMMAND.matcher(text);
            while (commands.find()) add(ledger, CompactionFactLedger.Type.COMMAND, commands.group(1).trim(),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher configs = CONFIG.matcher(text);
            while (configs.find()) add(ledger, CompactionFactLedger.Type.CONFIG_VALUE,
                    configs.group(1) + "=" + configs.group(2), sourceMessageId, sourceToolCallId,
                    sequence, contextEpoch);
            Matcher symbols = SYMBOL.matcher(text);
            while (symbols.find()) add(ledger, CompactionFactLedger.Type.SYMBOL, symbols.group(1),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher unresolved = UNRESOLVED.matcher(text);
            while (unresolved.find()) add(ledger, CompactionFactLedger.Type.UNRESOLVED_ITEM,
                    unresolved.group(1).trim(), sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher decisions = DECISION.matcher(text);
            while (decisions.find()) ledger.putLatestDecision(decisions.group(1), decisions.group(2),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher numbers = NUMBER.matcher(text);
            while (numbers.find()) add(ledger, CompactionFactLedger.Type.NUMBER, numbers.group(),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            Matcher modified = MODIFIED.matcher(text);
            while (modified.find()) add(ledger, CompactionFactLedger.Type.MODIFIED_FILE, modified.group(1),
                    sourceMessageId, sourceToolCallId, sequence, contextEpoch);
            if (message != null && message.toolCalls() != null) {
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    if (call == null || call.function() == null) continue;
                    String args = call.function().arguments() == null ? "" : call.function().arguments();
                    Matcher toolPaths = PATH.matcher(args);
                    String callId = call.id() == null ? sourceToolCallId : call.id();
                    while (toolPaths.find()) add(ledger, CompactionFactLedger.Type.FILE_PATH, toolPaths.group(),
                            sourceMessageId, callId, sequence, contextEpoch);
                }
            }
        }
    }

    private static void add(CompactionFactLedger ledger, CompactionFactLedger.Type type, String value,
                            String sourceMessageId, String sourceToolCallId,
                            long sequence, long contextEpoch) {
        ledger.put(new CompactionFactLedger.Fact(type.name() + ":" + value, type, value,
                sourceMessageId, sourceToolCallId,
                type == CompactionFactLedger.Type.UNRESOLVED_ITEM
                        ? CompactionFactLedger.FactStatus.UNRESOLVED
                        : CompactionFactLedger.FactStatus.ACTIVE,
                sequence, contextEpoch));
    }

    private static long sourceSequence(int index, int messageCount,
                                       LlmClient.Message message, CompactionContext context) {
        if (context == null) return index + 1L;
        Long mapped = context.sourceMessageEventIdsByFingerprint().get(messageKey(message));
        if (mapped != null && mapped > 0) return mapped;
        if (context.sourceMessageEventIds().size() == messageCount
                && index < context.sourceMessageEventIds().size()) {
            long eventId = context.sourceMessageEventIds().get(index);
            if (eventId > 0) return eventId;
        }
        if (context.sourceEventEnd() > 0) {
            // Runtime 没有逐条消息事件时，使用真实来源范围的末端游标，
            // 避免把消息下标伪装成 Runtime event id。
            return context.sourceEventEnd();
        }
        if (context.historySequence() > 0) {
            long suffixStart = context.historySequence() - Math.max(1, messageCount);
            return Math.max(1L, suffixStart + index + 1L);
        }
        return index + 1L;
    }

    /** Stable message identity shared by Runtime event projection and fact extraction. */
    public static String messageKey(LlmClient.Message message) {
        if (message == null) return "";
        StringBuilder value = new StringBuilder()
                .append(message.role()).append('\n')
                .append(message.content()).append('\n')
                .append(message.reasoningContent()).append('\n')
                .append(message.toolCallId()).append('\n')
                .append(message.imagePartCount()).append('\n');
        if (message.toolCalls() != null) {
            message.toolCalls().forEach(call -> {
                if (call != null && call.function() != null) {
                    value.append(call.id()).append('|')
                            .append(call.function().name()).append('|')
                            .append(call.function().arguments()).append('\n');
                }
            });
        }
        return value.toString();
    }
}
