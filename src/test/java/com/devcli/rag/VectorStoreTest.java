package com.devcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorStoreTest {

    private VectorStore store;
    private static final String TEST_PROJECT = "/tmp/test-project";

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("devcli.rag.dir", "/tmp/devcli-test-rag");
        store = new VectorStore(TEST_PROJECT);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testInsertAndSearch() throws Exception {
        CodeChunk chunk1 = CodeChunk.classChunk("Test.java", "TestClass",
                "public class TestClass {}", 1, 1);
        CodeChunk chunk2 = CodeChunk.methodChunk("Test.java", "TestClass.main",
                "public static void main(String[] args) {}", 2, 4);

        float[] emb1 = {1.0f, 0.0f, 0.0f};
        float[] emb2 = {0.0f, 1.0f, 0.0f};

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(chunk1, emb1),
                new VectorStore.CodeChunkEntry(chunk2, emb2)
        ));

        VectorStore.IndexStats stats = store.getStats();
        assertEquals(2, stats.chunkCount());

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorStore.SearchResult> results = store.search(query, 2);
        assertEquals(2, results.size());
        assertEquals("TestClass", results.get(0).name());
        assertTrue(results.get(0).similarity() > 0.99);
        assertTrue(results.get(0).symbolVersion().startsWith("sv_"));
        assertEquals("none", results.get(0).classpathEpoch());
        assertEquals("none", results.get(0).indexEpoch());
    }

    @Test
    void testSearchByKeyword() throws Exception {
        CodeChunk chunk = CodeChunk.classChunk("Foo.java", "FooService",
                "public class FooService { public void bar() {} }", 1, 3);
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{0.5f, 0.5f})));

        List<VectorStore.SearchResult> results = store.searchByKeyword("FooService");
        assertEquals(1, results.size());
        assertEquals("FooService", results.get(0).name());
        assertTrue(results.get(0).symbolVersion().startsWith("sv_"));
    }

    @Test
    void replaceProjectIndexRecordsSymbolInvalidationWhenVersionChanges() throws Exception {
        CodeChunk oldChunk = CodeChunk.methodChunk("UserService.java", "UserService.findUser",
                "public User findUser(Long id) { return null; }", 1, 3);
        CodeChunk newChunk = CodeChunk.methodChunk("UserService.java", "UserService.findUser",
                "public User findUser(String username) { return null; }", 1, 3);

        store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f, 0.0f})),
                List.of(),
                "idx-old");
        store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(newChunk, new float[]{1.0f, 0.0f})),
                List.of(),
                "idx-new");

        List<SymbolInvalidation> invalidations = store.getRecentInvalidations(10);
        assertEquals(1, invalidations.size());
        assertEquals("idx-old", invalidations.get(0).oldIndexEpoch());
        assertEquals("idx-new", invalidations.get(0).newIndexEpoch());
        assertNotEquals(invalidations.get(0).oldSymbolVersion(), invalidations.get(0).newSymbolVersion());
        assertTrue(invalidations.get(0).negativeFact().contains("Do not rely on UserService.findUser"));

        List<VectorStore.SearchResult> results = store.searchByKeyword("findUser");
        assertEquals("idx-new", results.get(0).indexEpoch());
        assertEquals(1, results.get(0).invalidations().size());
        assertEquals("sv_", results.get(0).symbolVersion().substring(0, 3));
    }

    @Test
    void testRelationStorage() throws Exception {
        CodeRelation rel = new CodeRelation("A.java", "A", "B.java", "B", "extends",
                CodeRelation.SOURCE_RESOLVED, 0.8, "epoch-1");
        store.insertRelations(List.of(rel));

        List<CodeRelation> results = store.getRelations("A");
        assertEquals(1, results.size());
        assertEquals("extends", results.get(0).relationType());
        assertEquals(CodeRelation.SOURCE_RESOLVED, results.get(0).resolutionSource());
        assertEquals(0.8, results.get(0).confidence());
        assertEquals("epoch-1", results.get(0).classpathEpoch());
    }

    @Test
    void testClearProject() throws Exception {
        CodeChunk chunk = CodeChunk.fileChunk("readme.md", "# Hello");
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{1.0f})));
        assertEquals(1, store.getStats().chunkCount());

        store.clearProject();
        assertEquals(0, store.getStats().chunkCount());
    }
}
