package com.devcli.benchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

final class RetrievalMetrics {
    private RetrievalMetrics() {
    }

    static double recallAtK(List<String> ranked, List<String> gold, int k,
                            BiPredicate<String, String> matcher) {
        if (gold == null || gold.isEmpty()) {
            return 0.0;
        }
        long hits = gold.stream()
                .filter(expected -> topK(ranked, k).stream()
                        .anyMatch(actual -> matcher.test(actual, expected)))
                .count();
        return round4((double) hits / gold.size());
    }

    static double reciprocalRankAtK(List<String> ranked, List<String> gold, int k,
                                    BiPredicate<String, String> matcher) {
        List<String> candidates = topK(ranked, k);
        for (int index = 0; index < candidates.size(); index++) {
            String actual = candidates.get(index);
            if (gold.stream().anyMatch(expected -> matcher.test(actual, expected))) {
                return round4(1.0 / (index + 1));
            }
        }
        return 0.0;
    }

    static double ndcgAtK(List<String> ranked, List<String> gold, int k,
                          BiPredicate<String, String> matcher) {
        if (gold == null || gold.isEmpty()) {
            return 0.0;
        }
        List<String> candidates = topK(ranked, k);
        Set<Integer> matchedGoldIndexes = new HashSet<>();
        double dcg = 0.0;
        for (int rank = 0; rank < candidates.size(); rank++) {
            int goldIndex = firstUnmatchedGold(candidates.get(rank), gold,
                    matchedGoldIndexes, matcher);
            if (goldIndex >= 0) {
                matchedGoldIndexes.add(goldIndex);
                dcg += discount(rank + 1);
            }
        }
        int idealHits = Math.min(Math.min(k, candidates.size()), gold.size());
        double idcg = 0.0;
        for (int rank = 1; rank <= idealHits; rank++) {
            idcg += discount(rank);
        }
        return idcg == 0.0 ? 0.0 : round4(dcg / idcg);
    }

    private static int firstUnmatchedGold(String actual, List<String> gold,
                                          Set<Integer> matched,
                                          BiPredicate<String, String> matcher) {
        for (int index = 0; index < gold.size(); index++) {
            if (!matched.contains(index) && matcher.test(actual, gold.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> topK(List<String> ranked, int k) {
        if (ranked == null || ranked.isEmpty() || k <= 0) {
            return List.of();
        }
        return ranked.stream().limit(k).toList();
    }

    private static double discount(int rank) {
        return 1.0 / (Math.log(rank + 1.0) / Math.log(2.0));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
