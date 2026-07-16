package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompactionSemanticGuard {
    private static final Pattern PROTECTED_MARKER = Pattern.compile(
            "(?i)(必须|禁止|不得|不要|务必|默认|约束|验收|兼容|版本|端口|目录|命令|must|never|do not|required|default|constraint|acceptance)");
    private static final Pattern COMMAND = Pattern.compile(
            "(?i)(mvn|gradle|npm|pnpm|yarn|pytest|java|docker|git)\\s+[^。；;\\n]{1,160}");
    private static final Pattern ANCHOR = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_.:/-]{1,}|\\d+(?:\\.\\d+)*");
    private static final Pattern NEGATIVE = Pattern.compile("(?i)(禁止|不得|不要|不允许|never|do not|must not)");
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
        Map<String, String> latestClaims = new LinkedHashMap<>();
        Set<String> ordinary = new LinkedHashSet<>();
        for (LlmClient.Message message : source) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            for (String segment : segments(message.content())) {
                StructuredClaim.parse(segment).ifPresentOrElse(claim -> {
                    latestClaims.remove(claim.subject());
                    latestClaims.put(claim.subject(), limit(claim.display(), MAX_CONSTRAINT_CHARS));
                }, () -> {
                    if (PROTECTED_MARKER.matcher(segment).find() || COMMAND.matcher(segment).find()) {
                        ordinary.add(limit(segment, MAX_CONSTRAINT_CHARS));
                    }
                });
            }
        }

        List<String> result = new ArrayList<>(MAX_CONSTRAINTS);
        for (String claim : latestClaims.values()) {
            if (result.size() >= MAX_CONSTRAINTS) break;
            result.add(claim);
        }
        for (String constraint : ordinary) {
            if (result.size() >= MAX_CONSTRAINTS) break;
            result.add(constraint);
        }
        return List.copyOf(result);
    }

    private static boolean retained(String summary, String constraint) {
        StructuredClaim.Claim expectedClaim = StructuredClaim.parse(constraint).orElse(null);
        if (expectedClaim != null) {
            for (String segment : segments(summary)) {
                StructuredClaim.Claim actual = StructuredClaim.parse(segment).orElse(null);
                if (actual != null && expectedClaim.subject().equals(actual.subject())
                        && expectedClaim.value().equals(actual.value())) {
                    return true;
                }
            }
        }

        String normalizedConstraint = normalize(constraint);
        if (!normalizedConstraint.isBlank() && normalize(summary).contains(normalizedConstraint)) return true;
        List<String> anchors = anchors(constraint);
        if (anchors.isEmpty()) return false;
        boolean negativeRequired = NEGATIVE.matcher(constraint).find();
        for (String segment : segments(summary)) {
            int retained = 0;
            String normalizedSegment = normalize(segment);
            for (String anchor : anchors) {
                if (normalizedSegment.contains(normalize(anchor))) retained++;
            }
            boolean polarityRetained = !negativeRequired || NEGATIVE.matcher(segment).find();
            int requiredAnchors = Math.min(anchors.size(), Math.max(2, (anchors.size() + 1) / 2));
            if (polarityRetained && retained >= requiredAnchors) return true;
        }
        return false;
    }

    private static List<String> segments(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] raw = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\r', '\n')
                .split("(?<=[。！？；;])|\\n+");
        List<String> result = new ArrayList<>();
        for (String segment : raw) {
            String normalized = segment.replaceAll("\\s+", " ").trim();
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result;
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

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}._:/-]+", "");
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    record Validation(boolean validBeforeRepair, String repairedSummary,
                      List<String> missingConstraints, int protectedConstraintCount) {
    }
}
