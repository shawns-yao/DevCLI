package com.devcli.rag;

import java.util.List;

/**
 * Keeps the RRF order when no cross-encoder rerank service is configured.
 */
public class NoopCodeReranker implements CodeReranker {
    @Override
    public List<VectorStore.SearchResult> rerank(String query,
                                                 List<VectorStore.SearchResult> candidates,
                                                 int limit) {
        return candidates.stream()
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String description() {
        return "disabled";
    }
}
