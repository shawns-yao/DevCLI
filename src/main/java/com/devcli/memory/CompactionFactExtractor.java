package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically extracts protected entities from history messages. */
public final class CompactionFactExtractor {
    private static final Pattern PATH = Pattern.compile("(?i)(?:[A-Za-z]:\\\\|/)[^\\s\\\"']+");
    private static final Pattern ERROR = Pattern.compile("(?i)\\b(?:error|exit code|错误码)\\s*[:=]?\\s*([A-Z0-9_-]+)");
    private static final Pattern COMMAND = Pattern.compile("(?m)^\\s*(?:[$>]\\s*)?((?:mvn|java|javac|git|docker)\\s+[^\\r\\n]+)");
    private static final Pattern CONFIG = Pattern.compile("(?m)\\b([A-Z][A-Z0-9_]{2,}|devcli\\.[a-z0-9_.-]+)\\s*=\\s*([^\\s,;]+)");
    private static final Pattern SYMBOL = Pattern.compile("\\b(?:class|interface|enum|record|method)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern UNRESOLVED = Pattern.compile("(?i)(?:未解决|待处理|unresolved|pending)[:：]?\\s*([^\\r\\n]+)");
    private static final Pattern DECISION = Pattern.compile("(?i)(?:决定|采用|改为|use|choose)\\s+([^\\s:=：]+)\\s*(?:为|=|:|：)\\s*([^\\r\\n,]+)");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])\\b\\d+(?:\\.\\d+)?(?:%|ms|s|K|M)?\\b");
    private static final Pattern MODIFIED = Pattern.compile("(?i)(?:modified|修改(?:了|文件)?|changed)[:：]?\\s*((?:[A-Za-z]:\\\\|/|src/)[^\\s,;]+)");

    public void extract(List<LlmClient.Message> messages, CompactionFactLedger ledger) {
        if (messages == null || ledger == null) return;
        for (int index = 0; index < messages.size(); index++) {
            LlmClient.Message message = messages.get(index);
            String sourceMessageId = "history:" + index;
            String sourceToolCallId = message == null || message.toolCallId() == null
                    ? "" : message.toolCallId();
            String text = message == null || message.content() == null ? "" : message.content();
            Matcher paths = PATH.matcher(text);
            while (paths.find()) add(ledger, CompactionFactLedger.Type.FILE_PATH, paths.group(),
                    sourceMessageId, sourceToolCallId, index + 1);
            Matcher errors = ERROR.matcher(text);
            while (errors.find()) add(ledger, CompactionFactLedger.Type.ERROR_CODE, errors.group(1),
                    sourceMessageId, sourceToolCallId, index + 1);
            Matcher commands = COMMAND.matcher(text);
            while (commands.find()) add(ledger, CompactionFactLedger.Type.COMMAND, commands.group(1).trim(),
                    sourceMessageId, sourceToolCallId, index + 1);
            Matcher configs = CONFIG.matcher(text);
            while (configs.find()) add(ledger, CompactionFactLedger.Type.CONFIG_VALUE,
                    configs.group(1) + "=" + configs.group(2), sourceMessageId, sourceToolCallId, index + 1);
            Matcher symbols = SYMBOL.matcher(text);
            while (symbols.find()) add(ledger, CompactionFactLedger.Type.SYMBOL, symbols.group(1),
                    sourceMessageId, sourceToolCallId, index + 1);
            Matcher unresolved = UNRESOLVED.matcher(text);
            while (unresolved.find()) add(ledger, CompactionFactLedger.Type.UNRESOLVED_ITEM,
                    unresolved.group(1).trim(), sourceMessageId, sourceToolCallId, index + 1);
            Matcher decisions = DECISION.matcher(text);
            while (decisions.find()) ledger.putLatestDecision(decisions.group(1), decisions.group(2),
                    sourceMessageId, sourceToolCallId, index + 1, 0);
            Matcher numbers = NUMBER.matcher(text);
            while (numbers.find()) add(ledger, CompactionFactLedger.Type.NUMBER, numbers.group(),
                    sourceMessageId, sourceToolCallId, index + 1);
            Matcher modified = MODIFIED.matcher(text);
            while (modified.find()) add(ledger, CompactionFactLedger.Type.MODIFIED_FILE, modified.group(1),
                    sourceMessageId, sourceToolCallId, index + 1);
            if (message != null && message.toolCalls() != null) {
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    if (call == null || call.function() == null) continue;
                    String args = call.function().arguments() == null ? "" : call.function().arguments();
                    Matcher toolPaths = PATH.matcher(args);
                    String callId = call.id() == null ? sourceToolCallId : call.id();
                    while (toolPaths.find()) add(ledger, CompactionFactLedger.Type.FILE_PATH, toolPaths.group(),
                            sourceMessageId, callId, index + 1);
                }
            }
        }
    }

    private static void add(CompactionFactLedger ledger, CompactionFactLedger.Type type, String value,
                            String sourceMessageId, String sourceToolCallId, long sequence) {
        ledger.put(new CompactionFactLedger.Fact(type.name() + ":" + value, type, value,
                sourceMessageId, sourceToolCallId,
                type == CompactionFactLedger.Type.UNRESOLVED_ITEM
                        ? CompactionFactLedger.FactStatus.UNRESOLVED
                        : CompactionFactLedger.FactStatus.ACTIVE,
                sequence, 0));
    }
}
