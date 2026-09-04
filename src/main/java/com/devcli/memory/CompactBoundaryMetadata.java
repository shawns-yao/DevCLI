package com.devcli.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 历史压缩摘要前置的结构化边界信息。
 */
public record CompactBoundaryMetadata(
        String compactType,
        String trigger,
        String mode,
        int preTokens,
        int postTokens,
        int originalMessages,
        int rebuiltMessages,
        int retainedMessages,
        int summaryChars,
        List<String> loadedSkills,
        String ragEpoch,
        String mcpToolSnapshot,
        boolean postCompactRestoreEnabled,
        int protectedConstraints,
        int restoredConstraints,
        String semanticGuardStatus,
        String sourceHash,
        int sourceStart,
        int sourceEnd,
        String projectionHash,
        long sourceEventStart,
        long sourceEventEnd
) {
    private static final String START = "<compact_boundary>";
    private static final String END = "</compact_boundary>";

    public CompactBoundaryMetadata {
        loadedSkills = loadedSkills == null ? List.of() : List.copyOf(loadedSkills);
        ragEpoch = blankToNone(ragEpoch);
        mcpToolSnapshot = blankToNone(mcpToolSnapshot);
        protectedConstraints = Math.max(0, protectedConstraints);
        restoredConstraints = Math.max(0, restoredConstraints);
        semanticGuardStatus = blankToNone(semanticGuardStatus);
        sourceHash = blankToNone(sourceHash);
        sourceStart = Math.max(0, sourceStart);
        sourceEnd = Math.max(sourceStart, sourceEnd);
        projectionHash = blankToNone(projectionHash);
        sourceEventStart = Math.max(0L, sourceEventStart);
        sourceEventEnd = Math.max(sourceEventStart, sourceEventEnd);
    }

    public CompactBoundaryMetadata(
            String compactType,
            String trigger,
            String mode,
            int preTokens,
            int postTokens,
            int originalMessages,
            int rebuiltMessages,
            int retainedMessages,
            int summaryChars) {
        this(compactType, trigger, mode, preTokens, postTokens, originalMessages, rebuiltMessages,
                retainedMessages, summaryChars, List.of(), "none", "none", false,
                0, 0, "none", "none", 0, 0, "none", 0, 0);
    }

    public CompactBoundaryMetadata(
            String compactType,
            String trigger,
            String mode,
            int preTokens,
            int postTokens,
            int originalMessages,
            int rebuiltMessages,
            int retainedMessages,
            int summaryChars,
            List<String> loadedSkills,
            String ragEpoch,
            String mcpToolSnapshot,
            boolean postCompactRestoreEnabled) {
        this(compactType, trigger, mode, preTokens, postTokens, originalMessages, rebuiltMessages,
                retainedMessages, summaryChars, loadedSkills, ragEpoch, mcpToolSnapshot,
                postCompactRestoreEnabled, 0, 0, "none", "none", 0, 0, "none", 0, 0);
    }

    /** 兼容旧内存下标字段；新代码应使用 sourceEventStart/sourceEventEnd。 */
    public int sourceStart() {
        return sourceStart;
    }

    public int sourceEnd() {
        return sourceEnd;
    }

    public String renderBoundaryBlock() {
        return START + "\n"
                + "compactType=" + compactType + "\n"
                + "trigger=" + trigger + "\n"
                + "mode=" + mode + "\n"
                + "preTokens=" + preTokens + "\n"
                + "postTokens=" + postTokens + "\n"
                + "originalMessages=" + originalMessages + "\n"
                + "rebuiltMessages=" + rebuiltMessages + "\n"
                + "retainedMessages=" + retainedMessages + "\n"
                + "summaryChars=" + summaryChars + "\n"
                + "loadedSkills=" + renderList(loadedSkills) + "\n"
                + "ragEpoch=" + ragEpoch + "\n"
                + "mcpToolSnapshot=" + mcpToolSnapshot + "\n"
                + "postCompactRestore=" + (postCompactRestoreEnabled ? "enabled" : "disabled") + "\n"
                + "protectedConstraints=" + protectedConstraints + "\n"
                + "restoredConstraints=" + restoredConstraints + "\n"
                + "semanticGuard=" + semanticGuardStatus + "\n"
                + "sourceHash=" + sourceHash + "\n"
                + "sourceRange=" + (sourceEventEnd > 0
                ? sourceEventStart + ":" + sourceEventEnd
                : sourceStart + ":" + sourceEnd) + "\n"
                + "messageRange=" + sourceStart + ":" + sourceEnd + "\n"
                + "sourceEventRange=" + sourceEventStart + ":" + sourceEventEnd + "\n"
                + "projectionHash=" + projectionHash + "\n"
                + END;
    }

    public static Optional<CompactBoundaryMetadata> parseFromSummaryMessage(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String text = content;
        if (text.startsWith(ConversationHistoryCompactor.SUMMARY_MARKER)) {
            text = text.substring(ConversationHistoryCompactor.SUMMARY_MARKER.length()).trim();
        } else if (text.startsWith(ConversationHistoryCompactor.LEGACY_SUMMARY_MARKER)) {
            // 兼容旧版中文标记:已持久化的检查点/历史会话回放仍可能携带。
            text = text.substring(ConversationHistoryCompactor.LEGACY_SUMMARY_MARKER.length()).trim();
        }
        if (!text.startsWith(START)) {
            return Optional.empty();
        }
        int endIdx = text.indexOf(END);
        if (endIdx < 0) {
            return Optional.empty();
        }

        String block = text.substring(START.length(), endIdx).trim();
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : block.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }

        try {
            return Optional.of(new CompactBoundaryMetadata(
                    values.get("compactType"),
                    values.get("trigger"),
                    values.get("mode"),
                    parseInt(values.get("preTokens")),
                    parseInt(values.get("postTokens")),
                    parseInt(values.get("originalMessages")),
                    parseInt(values.get("rebuiltMessages")),
                    parseInt(values.get("retainedMessages")),
                    parseInt(values.get("summaryChars")),
                    parseList(values.get("loadedSkills")),
                    blankToNone(values.get("ragEpoch")),
                    blankToNone(values.get("mcpToolSnapshot")),
                    "enabled".equalsIgnoreCase(values.getOrDefault("postCompactRestore", "disabled")),
                    parseIntOrDefault(values.get("protectedConstraints"), 0),
                    parseIntOrDefault(values.get("restoredConstraints"), 0),
                    blankToNone(values.get("semanticGuard")),
                    blankToNone(values.get("sourceHash")),
                    parseRangeStart(values.getOrDefault("messageRange", values.get("sourceRange"))),
                    parseRangeEnd(values.getOrDefault("messageRange", values.get("sourceRange"))),
                    blankToNone(values.get("projectionHash")),
                    parseLongRangeStart(values.getOrDefault("sourceEventRange", "0:0")),
                    parseLongRangeEnd(values.getOrDefault("sourceEventRange", "0:0"))
            ));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static String stripBoundaryBlock(String summaryText) {
        if (summaryText == null || summaryText.isBlank()) {
            return "";
        }
        String text = summaryText.trim();
        if (!text.startsWith(START)) {
            return text;
        }
        int endIdx = text.indexOf(END);
        if (endIdx < 0) {
            return text;
        }
        return text.substring(endIdx + END.length()).trim();
    }

    private static int parseInt(String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing integer metadata");
        }
        return Integer.parseInt(value);
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String renderList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return String.join(", ", values);
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "none" : value.trim();
    }

    private static int parseRangeStart(String value) {
        int separator = value == null ? -1 : value.indexOf(':');
        if (separator < 0) return 0;
        return parseIntOrDefault(value.substring(0, separator), 0);
    }

    private static int parseRangeEnd(String value) {
        int separator = value == null ? -1 : value.indexOf(':');
        if (separator < 0) return 0;
        return Math.max(parseRangeStart(value),
                parseIntOrDefault(value.substring(separator + 1), 0));
    }

    private static long parseLongRangeStart(String value) {
        int separator = value == null ? -1 : value.indexOf(':');
        if (separator < 0) return 0L;
        return parseLongOrDefault(value.substring(0, separator), 0L);
    }

    private static long parseLongRangeEnd(String value) {
        int separator = value == null ? -1 : value.indexOf(':');
        if (separator < 0) return 0L;
        return Math.max(parseLongRangeStart(value),
                parseLongOrDefault(value.substring(separator + 1), 0L));
    }

    private static long parseLongOrDefault(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
