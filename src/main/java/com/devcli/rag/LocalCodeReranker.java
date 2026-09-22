package com.devcli.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 无外部服务依赖的可解释二阶段代码重排器。 */
public final class LocalCodeReranker implements CodeReranker {
    @Override
    public List<VectorStore.SearchResult> rerank(String query,
                                                 List<VectorStore.SearchResult> candidates,
                                                 int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> queryTerms = terms(query);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorStore.SearchResult candidate = candidates.get(i);
            String haystack = String.join(" ", candidate.name(), candidate.filePath(), candidate.content())
                    .toLowerCase(Locale.ROOT);
            long matches = queryTerms.stream().filter(haystack::contains).count();
            double score = candidate.similarity() + (queryTerms.isEmpty()
                    ? 0.0 : 0.25 * matches / queryTerms.size());
            scored.add(new Scored(i, score));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparingInt(Scored::index))
                .limit(limit)
                .map(scoredCandidate -> candidates.get(scoredCandidate.index()))
                .toList();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String description() {
        return "local-token-overlap";
    }

    private Set<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new HashSet<>();
        for (String token : RagQueryTokenizer.tokenize(query)) {
            String normalized = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
            if (normalized.length() >= 2) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private record Scored(int index, double score) {
    }
}
