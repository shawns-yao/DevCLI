package com.devcli.memory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class MemoryFactDeduper {
    private MemoryFactDeduper() {
    }

    static boolean duplicatesAny(String fact, Collection<String> volatileFacts) {
        String normalizedFact = normalize(fact);
        if (normalizedFact.isBlank() || volatileFacts == null || volatileFacts.isEmpty()) {
            return false;
        }
        for (String volatileFact : volatileFacts) {
            if (isDuplicate(normalizedFact, normalize(volatileFact))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDuplicate(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        if (left.length() >= 12 && right.contains(left)) {
            return true;
        }
        if (right.length() >= 12 && left.contains(right)) {
            return true;
        }
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.size() < 4 || rightTokens.size() < 4) {
            return false;
        }
        int overlap = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                overlap++;
            }
        }
        double ratio = overlap / (double) Math.min(leftTokens.size(), rightTokens.size());
        return ratio >= 0.85;
    }

    private static Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : value.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceFirst("^用户最新输入[:：]\\s*", "")
                .replaceFirst("^(请)?记住[:：]?\\s*", "")
                .replaceFirst("^remember(?: that)?[:：]?\\s*", "")
                .replaceFirst("^please remember(?: that)?[:：]?\\s*", "")
                .replaceAll("[\\p{Punct}，。；：！？、“”‘’（）【】《》]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.endsWith("...") ? normalized.substring(0, normalized.length() - 3).trim() : normalized;
    }
}
