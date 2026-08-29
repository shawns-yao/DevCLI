package com.devcli.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RerankerFreshnessTest {
    @Test
    void localRerankerKeepsCandidateOrderByRelevantTerms() {
        VectorStore.SearchResult unrelated = result("unrelated", "CURRENT");
        VectorStore.SearchResult relevant = result("payment reconciliation", "DIRTY");

        List<VectorStore.SearchResult> reranked = new LocalCodeReranker()
                .rerank("payment", List.of(unrelated, relevant), 2);

        assertEquals("payment reconciliation", reranked.getFirst().name());
        assertEquals(VectorStore.IndexFreshness.DIRTY, reranked.getFirst().freshness());
    }

    @Test
    void fusionKeepsCandidateFreshnessMetadata() {
        VectorStore.SearchResult dirty = result("payment", "DIRTY");
        RetrievalFusion fusion = new RetrievalFusion();
        fusion.addChannel("keyword", List.of(dirty), 1.0);

        assertEquals(VectorStore.IndexFreshness.DIRTY, fusion.rank("payment", 1).getFirst().freshness());
    }

    private VectorStore.SearchResult result(String name, String freshness) {
        return new VectorStore.SearchResult("src/" + name + ".java", "method", name, name,
                0.1, "sv", "cp", "idx", List.of(),
                VectorStore.IndexFreshness.valueOf(freshness));
    }
}
