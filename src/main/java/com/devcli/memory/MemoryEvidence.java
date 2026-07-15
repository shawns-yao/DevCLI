package com.devcli.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 长期记忆的结构化证据与审核状态。
 *
 * <p>该类型是证据字段的唯一领域契约。metadata 只保留旧数据兼容和业务标签，
 * 新代码不得再从自由格式 metadata 推断置信度、审核状态或冲突关系。
 */
public record MemoryEvidence(
        Confidence confidence,
        String sourceQuote,
        String reasoning,
        ReviewState reviewState,
        List<String> conflictsWith) {

    private static final int MAX_SOURCE_QUOTE_CHARS = 1_000;
    private static final int MAX_REASONING_CHARS = 500;
    private static final int MAX_CONFLICTS = 32;

    public MemoryEvidence {
        sourceQuote = normalizeText(sourceQuote, MAX_SOURCE_QUOTE_CHARS);
        confidence = enforceConfidenceEvidence(
                confidence == null ? Confidence.UNSPECIFIED : confidence, sourceQuote);
        reasoning = normalizeText(reasoning, MAX_REASONING_CHARS);
        reviewState = reviewState == null ? ReviewState.REVIEWED : reviewState;
        conflictsWith = normalizeConflicts(conflictsWith);
    }

    public static MemoryEvidence legacy(Map<String, String> metadata) {
        Map<String, String> values = metadata == null ? Map.of() : metadata;
        return new MemoryEvidence(
                Confidence.parse(values.get("confidence")),
                values.getOrDefault("source_quote", ""),
                values.getOrDefault("reasoning", values.getOrDefault("reason_code", "")),
                ReviewState.parse(values.get("review_state"), ReviewState.REVIEWED),
                parseLegacyConflicts(values.get("conflict_with")));
    }

    public static MemoryEvidence fromPolicy(Map<String, String> metadata, String sourceQuote) {
        Map<String, String> values = metadata == null ? Map.of() : metadata;
        String source = values.getOrDefault("source", "");
        ReviewState defaultState = "explicit".equalsIgnoreCase(source) || "fact".equalsIgnoreCase(source)
                ? ReviewState.REVIEWED
                : ReviewState.UNREVIEWED;
        return new MemoryEvidence(
                Confidence.parse(values.get("confidence")),
                sourceQuote,
                values.getOrDefault("reason_code", ""),
                ReviewState.parse(values.get("review_state"), defaultState),
                parseLegacyConflicts(values.get("conflict_with")));
    }

    public MemoryEvidence withConflict(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) return this;
        List<String> next = new ArrayList<>(conflictsWith);
        next.add(memoryId);
        return new MemoryEvidence(confidence, sourceQuote, reasoning, reviewState, next);
    }

    public MemoryEvidence withReviewState(ReviewState state) {
        return new MemoryEvidence(confidence, sourceQuote, reasoning, state, conflictsWith);
    }

    public boolean isRecallable() {
        return reviewState != ReviewState.REJECTED;
    }

    public double retrievalWeight() {
        double confidenceWeight = switch (confidence) {
            case HIGH -> 1.0;
            case MEDIUM -> 0.9;
            case LOW -> 0.75;
            case UNSPECIFIED -> 0.85;
        };
        double reviewWeight = reviewState == ReviewState.REVIEWED ? 1.0 : 0.85;
        return confidenceWeight * reviewWeight;
    }

    private static Confidence enforceConfidenceEvidence(Confidence confidence, String sourceQuote) {
        if (confidence == Confidence.HIGH && sourceQuote.length() < 5) {
            confidence = Confidence.MEDIUM;
        }
        if (confidence == Confidence.MEDIUM && sourceQuote.isBlank()) {
            confidence = Confidence.LOW;
        }
        return confidence;
    }

    private static String normalizeText(String value, int maxChars) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "")
                .trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private static List<String> normalizeConflicts(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            String candidate = value.trim();
            if (!candidate.isEmpty()) normalized.add(candidate);
            if (normalized.size() >= MAX_CONFLICTS) break;
        }
        return List.copyOf(normalized);
    }

    private static List<String> parseLegacyConflicts(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.trim());
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNSPECIFIED;

        public static Confidence parse(String value) {
            if (value == null || value.isBlank()) return UNSPECIFIED;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return UNSPECIFIED;
            }
        }
    }

    public enum ReviewState {
        REVIEWED,
        UNREVIEWED,
        REJECTED;

        public static ReviewState parse(String value, ReviewState fallback) {
            if (value == null || value.isBlank()) return fallback == null ? REVIEWED : fallback;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback == null ? REVIEWED : fallback;
            }
        }
    }
}
