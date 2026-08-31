package com.devcli.benchmark;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PublicBenchmarkMetrics {
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern PARAGRAPH = Pattern.compile("Paragraph (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "was", "were", "be", "been", "being");

    private PublicBenchmarkMetrics() {
    }

    static double longBenchCountScore(String prediction, String groundTruth) {
        return matchingNumberRatio(prediction, groundTruth);
    }

    static double longBenchRetrievalScore(String prediction, String groundTruth) {
        Matcher matcher = PARAGRAPH.matcher(groundTruth == null ? "" : groundTruth);
        if (!matcher.find()) {
            return 0.0;
        }
        return matchingNumberRatio(prediction, matcher.group(1));
    }

    static double rulerStringMatchAll(String prediction, List<String> references) {
        if (references == null || references.isEmpty()) {
            return 0.0;
        }
        String normalizedPrediction = prediction == null ? "" : prediction.toLowerCase(Locale.ROOT);
        long matched = references.stream()
                .filter(reference -> normalizedPrediction.contains(reference.toLowerCase(Locale.ROOT)))
                .count();
        return (double) matched / references.size();
    }

    static boolean normalizedAnswerHit(String prediction, String answer) {
        String normalizedPrediction = normalizeText(prediction);
        String normalizedAnswer = normalizeText(answer);
        return !normalizedAnswer.isBlank()
                && (normalizedPrediction.contains(normalizedAnswer)
                || normalizedAnswer.contains(normalizedPrediction));
    }

    private static double matchingNumberRatio(String prediction, String expected) {
        Matcher matcher = NUMBER.matcher(prediction == null ? "" : prediction);
        int total = 0;
        int matched = 0;
        while (matcher.find()) {
            total++;
            if (matcher.group().equals(expected)) {
                matched++;
            }
        }
        return total == 0 ? 0.0 : (double) matched / total;
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return Arrays.stream(normalized.split(" "))
                .filter(token -> !token.isBlank() && !STOP_WORDS.contains(token))
                .collect(Collectors.joining(" "));
    }
}
