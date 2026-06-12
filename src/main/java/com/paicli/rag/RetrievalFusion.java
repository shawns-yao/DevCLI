package com.paicli.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuses heterogeneous retrieval channels without comparing raw score scales directly.
 */
public class RetrievalFusion {
    private static final int DEFAULT_RRF_K = 60;

    private final int rrfK;
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();

    public RetrievalFusion() {
        this(DEFAULT_RRF_K);
    }

    public RetrievalFusion(int rrfK) {
        this.rrfK = Math.max(1, rrfK);
    }

    public void addChannel(String channel, List<VectorStore.SearchResult> rankedResults, double channelWeight) {
        if (rankedResults == null || rankedResults.isEmpty()) {
            return;
        }
        double weight = Math.max(0.0, channelWeight);
        for (int i = 0; i < rankedResults.size(); i++) {
            VectorStore.SearchResult result = rankedResults.get(i);
            String key = key(result);
            Candidate candidate = candidates.computeIfAbsent(key, ignored -> new Candidate(result));
            candidate.bestResult = better(candidate.bestResult, result);
            candidate.rrfScore += weight / (rrfK + i + 1.0);
            candidate.channels.put(channel, i + 1);
        }
    }

    public List<VectorStore.SearchResult> rank(String query, int limit) {
        List<ScoredResult> scored = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            double score = candidate.rrfScore
                    + channelDiversityBoost(candidate)
                    + symbolBoost(candidate.bestResult, query)
                    + typeBoost(candidate.bestResult)
                    - noisePenalty(candidate.bestResult, query);
            scored.add(new ScoredResult(candidate.bestResult, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredResult::score).reversed());
        return scored.stream()
                .limit(Math.max(0, limit))
                .map(scoredResult -> withScore(scoredResult.result(), scoredResult.score()))
                .toList();
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    private double channelDiversityBoost(Candidate candidate) {
        if (candidate.channels.size() >= 3) {
            return 0.04;
        }
        if (candidate.channels.size() == 2) {
            return 0.025;
        }
        return 0.0;
    }

    private double symbolBoost(VectorStore.SearchResult result, String query) {
        String normalizedQuery = normalize(query);
        String normalizedName = normalize(result.name());
        String normalizedPath = normalize(result.filePath());
        double boost = 0.0;
        for (String token : RagQueryTokenizer.tokenize(query)) {
            String normalizedToken = normalize(token);
            if (normalizedToken.length() < 2) {
                continue;
            }
            if (normalizedName.equals(normalizedToken)) {
                boost += 0.08;
            } else if (normalizedName.contains(normalizedToken)) {
                boost += 0.035;
            }
            if (normalizedPath.endsWith("/" + normalizedToken + ".java")) {
                boost += 0.05;
            } else if (normalizedPath.contains(normalizedToken)) {
                boost += 0.02;
            }
        }
        if (!normalizedQuery.isBlank() && normalizedName.contains(normalizedQuery)) {
            boost += 0.08;
        }
        return Math.min(boost, 0.20);
    }

    private double typeBoost(VectorStore.SearchResult result) {
        return switch (result.chunkType()) {
            case "method" -> 0.025;
            case "class" -> 0.015;
            default -> 0.0;
        };
    }

    private double noisePenalty(VectorStore.SearchResult result, String query) {
        String path = normalize(result.filePath());
        String queryText = normalize(query);
        double penalty = 0.0;
        if ((path.contains("/src/test/") || path.contains("/test/"))
                && !containsAny(queryText, "test", "测试", "单测")) {
            penalty += 0.025;
        }
        if ((path.contains("/docs/") || path.endsWith(".md"))
                && !containsAny(queryText, "doc", "docs", "readme", "文档")) {
            penalty += 0.02;
        }
        if ("file".equals(result.chunkType())) {
            penalty += 0.015;
        }
        return penalty;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private VectorStore.SearchResult better(VectorStore.SearchResult left, VectorStore.SearchResult right) {
        return right.similarity() > left.similarity() ? right : left;
    }

    private VectorStore.SearchResult withScore(VectorStore.SearchResult result, double score) {
        return new VectorStore.SearchResult(
                result.filePath(),
                result.chunkType(),
                result.name(),
                result.content(),
                score,
                result.symbolVersion(),
                result.classpathEpoch(),
                result.indexEpoch(),
                result.invalidations()
        );
    }

    private String key(VectorStore.SearchResult result) {
        return result.filePath() + "#" + result.name();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\\', '/').toLowerCase();
    }

    private static class Candidate {
        private VectorStore.SearchResult bestResult;
        private double rrfScore;
        private final Map<String, Integer> channels = new LinkedHashMap<>();

        private Candidate(VectorStore.SearchResult bestResult) {
            this.bestResult = bestResult;
        }
    }

    private record ScoredResult(VectorStore.SearchResult result, double score) {
    }
}
