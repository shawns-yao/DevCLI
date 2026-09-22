package com.devcli.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStoreIndexBackendTest {

    @Test
    void persistsNewEmbeddingsAsBlobsAndSearchesThroughAnn(@TempDir Path directory) throws Exception {
        System.setProperty("devcli.rag.dir", directory.toString());
        try (VectorStore store = new VectorStore(directory.resolve("project").toString())) {
            store.clearProject();
            store.insertChunks(List.of(
                    new VectorStore.CodeChunkEntry(
                            CodeChunk.fileChunk("alpha.txt", "alpha implementation"),
                            new float[]{1.0f, 0.0f, 0.0f}),
                    new VectorStore.CodeChunkEntry(
                            CodeChunk.fileChunk("beta.txt", "beta implementation"),
                            new float[]{0.0f, 1.0f, 0.0f})));

            List<VectorStore.SearchResult> results = store.search(new float[]{1.0f, 0.0f, 0.0f}, 1);

            assertEquals("alpha.txt", results.getFirst().filePath());
            assertEquals("HNSW", store.lastSearchDiagnostics().vectorBackend());
            assertEquals(2, store.lastSearchDiagnostics().indexedVectors());
            assertFalse(store.lastSearchDiagnostics().degraded());
        } finally {
            System.clearProperty("devcli.rag.dir");
        }

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("codebase.db"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT embedding_blob, embedding_json FROM code_chunks LIMIT 1")) {
            assertTrue(rows.next());
            assertEquals(3 * Float.BYTES, rows.getBytes("embedding_blob").length);
            assertEquals(null, rows.getString("embedding_json"));
        }
    }

    @Test
    void keywordSearchUsesFtsForSubstringQueries(@TempDir Path directory) throws Exception {
        System.setProperty("devcli.rag.dir", directory.toString());
        try (VectorStore store = new VectorStore(directory.resolve("project").toString())) {
            store.clearProject();
            store.insertChunks(List.of(new VectorStore.CodeChunkEntry(
                    CodeChunk.fileChunk("OrderService.java", "class OrderService { void reconcilePayment() {} }"),
                    new float[]{1.0f, 0.0f})));

            List<VectorStore.SearchResult> results = store.searchByKeyword("concilePay");

            assertEquals(1, results.size());
            assertEquals("OrderService.java", results.getFirst().filePath());
            assertEquals("FTS5_TRIGRAM", store.lastSearchDiagnostics().keywordBackend());
        } finally {
            System.clearProperty("devcli.rag.dir");
        }
    }
}
