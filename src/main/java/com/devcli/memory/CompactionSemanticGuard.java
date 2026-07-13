package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompactionSemanticGuard {
    private static final Pattern PROTECTED_MARKER = Pattern.compile(
            "(?i)(必须|禁止|不得|不要|务必|默认|约束|验收|兼容|版本|端口|目录|命令|must|never|do not|required|default|constraint|acceptance)");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([a-z_][a-z0-9_.-]{2,})\\s*[:=]\\s*([^\\s,，;；]+)");
    private static final Pattern COMMAND = Pattern.compile(
            "(?i)(mvn|gradle|npm|pnpm|yarn|pytest|java|docker|git)\\s+[^。；;\\n]{1,160}");
    private static final Pattern ANCHOR = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.:/-]{1,}|\\d+(?:\\.\\d+)*");
    private static final int MAX_CONSTRAINTS = 24;
    private static final int MAX_CONSTRAINT_CHARS = 600;

    private CompactionSemanticGuard() {
    }

    static Validation validateAndRepair(List<LlmClient.Message> source, String summary, int maxChars) {
        String safeSummary = summary == null ? "" : summary.trim();
        List<String> constraints = extractConstraints(source);
        List<String> missing = constraints.stream()
                .filter(constraint -> !retained(safeSummary, constraint))
                .toList();
        if (missing.isEmpty()) {
            return new Validation(true, safeSummary, List.of(), constraints.size());
        }

        String repair = "\n\n## 压缩语义守卫恢复的关键约束\n- " + String.join("\n- ", missing);
        int limit = Math.max(repair.length(), maxChars);
        int available = Math.max(0, limit - repair.length());
        String base = safeSummary.length() <= available
                ? safeSummary
                : safeSummary.substring(0, available).stripTrailing();
        return new Validation(false, (base + repair).trim(), List.copyOf(missing), constraints.size());
    }

    static List<String> extractConstraints(List<LlmClient.Message> source) {
        if (source == null || source.isEmpty()) return List.of();
        Set<String> constraints = new LinkedHashSet<>();
        for (LlmClient.Message message : source) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            String[] segments = message.content().replace('\r', '\n').split("(?<=[。！？；;])|\\n+");
            for (String segment : segments) {
                String normalized = segment.replaceAll("\\s+", " ").trim();
                if (normalized.isBlank()) continue;
                if (PROTECTED_MARKER.matcher(normalized).find()
                        || ASSIGNMENT.matcher(normalized).find()
                        || COMMAND.matcher(normalized).find()) {
                    constraints.add(limit(normalized, MAX_CONSTRAINT_CHARS));
                    if (constraints.size() >= MAX_CONSTRAINTS) {
                        return List.copyOf(constraints);
                    }
                }
            }
        }
        return List.copyOf(constraints);
    }

    private static boolean retained(String summary, String constraint) {
        String normalizedSummary = normalize(summary);
        String normalizedConstraint = normalize(constraint);
        if (!normalizedConstraint.isBlank() && normalizedSummary.contains(normalizedConstraint)) {
            return true;
        }
        List<String> anchors = anchors(constraint);
        if (anchors.isEmpty()) return false;
        int retained = 0;
        for (String anchor : anchors) {
            if (normalizedSummary.contains(normalize(anchor))) retained++;
        }
        boolean polarityRequired = containsPolarity(constraint);
        boolean polarityRetained = !polarityRequired || containsPolarity(summary);
        return polarityRetained && retained >= Math.min(anchors.size(), Math.max(2, (anchors.size() + 1) / 2));
    }

    private static List<String> anchors(String constraint) {
        List<String> anchors = new ArrayList<>();
        Matcher matcher = ANCHOR.matcher(constraint);
        while (matcher.find()) {
            String value = matcher.group().toLowerCase(Locale.ROOT);
            if (value.length() >= 2 && !Set.of("must", "never", "default", "required", "do", "not").contains(value)) {
                anchors.add(value);
            }
        }
        return anchors.stream().distinct().limit(8).toList();
    }

    private static boolean containsPolarity(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("禁止") || lower.contains("不得") || lower.contains("不要")
                || lower.contains("never") || lower.contains("do not");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}._:/-]+", "");
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    record Validation(boolean validBeforeRepair, String repairedSummary,
                      List<String> missingConstraints, int protectedConstraintCount) {
    }
}
