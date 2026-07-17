package com.devcli.tool.provider;

import com.devcli.rag.RagEvidenceSideChannel;
import com.devcli.rag.SymbolInvalidation;
import com.devcli.rag.VectorStore;
import com.devcli.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RagToolProviderTest {

    @Test
    void searchResultUsesTypedEvidenceWithoutEmbeddingJsonInText() {
        SymbolInvalidation invalidation = new SymbolInvalidation(
                "CodeRetriever.java#method#CodeRetriever.search",
                "src/main/java/com/devcli/rag/CodeRetriever.java",
                "method",
                "CodeRetriever.search",
                "sv-old",
                "sv-new",
                "idx-old",
                "idx-new",
                "cp-1",
                "Do not rely on the old search symbol.");
        VectorStore.SearchResult result = new VectorStore.SearchResult(
                "src/main/java/com/devcli/rag/CodeRetriever.java",
                "method",
                "CodeRetriever.search",
                "content",
                0.91,
                "sv-new",
                "cp-1",
                "idx-new",
                List.of(invalidation));

        ToolOutput output = RagToolProvider.formatSearchResult(
                "CodeRetriever search", List.of(result), List.of(invalidation), false);

        assertFalse(output.text().contains("RAG_EVIDENCE_JSON"));
        assertEquals(1, output.sideChannels().size());
        RagEvidenceSideChannel evidence = assertInstanceOf(
                RagEvidenceSideChannel.class, output.sideChannels().get(0));
        assertEquals("sv-new", evidence.payload().evidence().get(0).symbolVersion());
        assertEquals("sv-old", evidence.payload().negativeFacts().get(0).oldSymbolVersion());
    }
}
