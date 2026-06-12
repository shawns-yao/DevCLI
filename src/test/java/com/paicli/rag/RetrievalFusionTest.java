package com.paicli.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalFusionTest {

    @Test
    void reciprocalRankFusionDoesNotCompareRawScoresAcrossChannels() {
        RetrievalFusion fusion = new RetrievalFusion();
        VectorStore.SearchResult semanticOnly = result("NoiseService", 0.99);
        VectorStore.SearchResult shared = result("UserService", 0.40);

        fusion.addChannel("semantic", List.of(semanticOnly, shared), 1.0);
        fusion.addChannel("keyword", List.of(shared), 1.0);

        List<VectorStore.SearchResult> ranked = fusion.rank("UserService 在哪里定义", 2);

        assertEquals("UserService", ranked.get(0).name());
    }

    private VectorStore.SearchResult result(String name, double score) {
        return new VectorStore.SearchResult(
                "src/main/java/com/example/" + name + ".java",
                "class",
                name,
                "public class " + name + " {}",
                score
        );
    }
}
