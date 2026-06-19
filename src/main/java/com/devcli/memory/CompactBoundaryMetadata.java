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
        boolean postCompactRestoreEnabled
) {
    private static final String START = "<compact_boundary>";
    private static final String END = "</compact_boundary>";

    public CompactBoundaryMetadata {
        loadedSkills = loadedSkills == null ? List.of() : List.copyOf(loadedSkills);
        ragEpoch = blankToNone(ragEpoch);
        mcpToolSnapshot = blankToNone(mcpToolSnapshot);
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
                retainedMessages, summaryChars, List.of(), "none", "none", false);
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
                + END;
    }

    public static Optional<CompactBoundaryMetadata> parseFromSummaryMessage(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String text = content;
        if (text.startsWith(ConversationHistoryCompactor.SUMMARY_MARKER)) {
            text = text.substring(ConversationHistoryCompactor.SUMMARY_MARKER.length()).trim();
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
                    "enabled".equalsIgnoreCase(values.getOrDefault("postCompactRestore", "disabled"))
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
}
