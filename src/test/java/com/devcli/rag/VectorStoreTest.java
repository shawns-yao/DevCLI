package com.devcli.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
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
    void replaceProjectIndexRecordsSymbolInvalidationWhenSymbolIsDeleted() throws Exception {
        CodeChunk oldChunk = CodeChunk.methodChunk("UserService.java", "UserService.findUser",
                "public User findUser(Long id) { return null; }", 1, 3);

        store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f, 0.0f})),
                List.of(),
                "idx-old");
        store.replaceProjectIndex(List.of(), List.of(), "idx-new");

        List<SymbolInvalidation> invalidations = store.getRecentInvalidations(10);
        assertEquals(1, invalidations.size());
        SymbolInvalidation invalidation = invalidations.get(0);
        assertEquals("idx-old", invalidation.oldIndexEpoch());
        assertEquals("idx-new", invalidation.newIndexEpoch());
        assertEquals("deleted", invalidation.newSymbolVersion());
        assertTrue(invalidation.negativeFact().contains("Do not rely on UserService.findUser"));
        assertTrue(invalidation.negativeFact().contains("removed from current index"));
    }

    @Test
    void currentIndexEpochTracksLatestProjectIndex() throws Exception {
        CodeChunk oldChunk = CodeChunk.fileChunk("old.md", "old content");
        CodeChunk newChunk = CodeChunk.fileChunk("new.md", "new content");

        assertEquals("none", store.currentIndexEpoch());

        store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f})),
                List.of(),
                "idx-old");
        store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(newChunk, new float[]{1.0f})),
                List.of(),
                "idx-new");

        assertEquals("idx-new", store.currentIndexEpoch());
    }

    @Test
    void rejectsStaleBuildWithBaseEpochCasAndMarksDirtyResults() throws Exception {
        CodeChunk oldChunk = CodeChunk.fileChunk("README.md", "old");
        CodeChunk newChunk = CodeChunk.fileChunk("README.md", "new");
        store.replaceProjectIndex(List.of(
                new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f})), List.of(), "idx-1");

        VectorStore.IndexBuildSnapshot buildSnapshot = store.beginIndexBuildSnapshot(List.of("README.md"));
        assertEquals("idx-1", buildSnapshot.baseEpoch());
        assertEquals(VectorStore.IndexFreshness.DIRTY,
                store.searchByKeyword("old").getFirst().freshness());

        store.replaceProjectIndex(List.of(
                new VectorStore.CodeChunkEntry(newChunk, new float[]{1.0f})), List.of(), "idx-2", buildSnapshot);
        boolean staleSwap = store.replaceProjectIndex(List.of(
                new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f})), List.of(), "idx-old", buildSnapshot);

        assertFalse(staleSwap);
        assertEquals("idx-2", store.currentIndexEpoch());
        assertEquals(VectorStore.IndexFreshness.CURRENT,
                store.searchByKeyword("new").getFirst().freshness());
    }

    @Test
    void projectWriteDuringBuildInvalidatesGenerationCas() throws Exception {
        CodeChunk oldChunk = CodeChunk.fileChunk("README.md", "old");
        CodeChunk staleBuildChunk = CodeChunk.fileChunk("README.md", "stale build");
        store.replaceProjectIndex(List.of(
                new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f})), List.of(), "idx-1");
        VectorStore.IndexBuildSnapshot buildSnapshot = store.beginIndexBuildSnapshot(List.of("README.md"));

        store.markDirtyFiles(List.of("README.md"));
        boolean swapped = store.replaceProjectIndex(List.of(
                new VectorStore.CodeChunkEntry(staleBuildChunk, new float[]{1.0f})),
                List.of(), "idx-stale", buildSnapshot);

        assertFalse(swapped);
        assertEquals("idx-1", store.currentIndexEpoch());
        assertEquals(VectorStore.IndexFreshness.DIRTY,
                store.searchByKeyword("old").getFirst().freshness());
    }

    @Test
    void ordinaryProjectWriteCanMarkIndexedFileDirty() throws Exception {
        CodeChunk indexed = CodeChunk.fileChunk("README.md", "indexed content");
        store.replaceProjectIndex(List.of(
                new VectorStore.CodeChunkEntry(indexed, new float[]{1.0f})), List.of(), "idx-1");

        store.markDirtyFiles(List.of("README.md"));

        assertEquals(VectorStore.IndexFreshness.DIRTY,
                store.searchByKeyword("indexed content").getFirst().freshness());
    }

    @Test
    void externalFileChangeIsDetectedWhenCandidateIsReturned(@org.junit.jupiter.api.io.TempDir Path project)
            throws Exception {
        Path source = project.resolve("README.md");
        Files.writeString(source, "indexed content");
        try (VectorStore projectStore = new VectorStore(project.toString())) {
            projectStore.clearProject();
            projectStore.replaceProjectIndex(List.of(new VectorStore.CodeChunkEntry(
                    CodeChunk.fileChunk(source.toString(), "indexed content"),
                    new float[]{1.0f})), List.of(), "idx-1");
            Files.writeString(source, "live changed content");

            VectorStore.SearchResult result = projectStore.searchByKeyword("indexed content").getFirst();

            assertEquals(VectorStore.IndexFreshness.DIRTY, result.freshness());
            assertEquals("live changed content", result.content());
        }
    }

    @Test
    void dirtyJavaFileContributesNewMethodToKeywordCandidates(
            @org.junit.jupiter.api.io.TempDir Path project) throws Exception {
        Path source = project.resolve("UserService.java");
        Files.writeString(source, "public class UserService { void existing() {} }");
        try (VectorStore projectStore = new VectorStore(project.toString())) {
            projectStore.clearProject();
            projectStore.replaceProjectIndex(new CodeChunker().chunkFile(source).stream()
                    .map(chunk -> new VectorStore.CodeChunkEntry(chunk, new float[]{1.0f}))
                    .toList(), List.of(), "idx-1");
            Files.writeString(source, """
                    public class UserService {
                        void existing() {}
                        void newlyAddedMethod() {}
                    }
                    """);
            projectStore.markDirtyFiles(List.of("UserService.java"));

            List<VectorStore.SearchResult> results = projectStore.searchByKeyword("newlyAddedMethod");

            assertTrue(results.stream().anyMatch(result -> "method".equals(result.chunkType())
                    && result.name().contains("newlyAddedMethod")));
            assertTrue(results.stream().allMatch(
                    result -> result.freshness() == VectorStore.IndexFreshness.DIRTY));
        }
    }

    @Test
    void dirtyTextFileContributesNewConfigurationKey(
            @org.junit.jupiter.api.io.TempDir Path project) throws Exception {
        Path source = project.resolve("application.properties");
        Files.writeString(source, "server.port=8080");
        try (VectorStore projectStore = new VectorStore(project.toString())) {
            projectStore.clearProject();
            projectStore.replaceProjectIndex(List.of(new VectorStore.CodeChunkEntry(
                    CodeChunk.fileChunk(source.toString(), "server.port=8080"),
                    new float[]{1.0f})), List.of(), "idx-1");
            Files.writeString(source, "server.port=8080\nfeature.audit.enabled=true");
            projectStore.markDirtyFiles(List.of("application.properties"));

            List<VectorStore.SearchResult> results = projectStore.searchByKeyword("feature.audit.enabled");

            assertEquals(1, results.size());
            assertTrue(results.getFirst().content().contains("feature.audit.enabled=true"));
            assertEquals(VectorStore.IndexFreshness.DIRTY, results.getFirst().freshness());
        }
    }

    @Test
    void dirtyFileMergesRelativeIndexAndAbsoluteLiveChunkWithoutDuplicates(
            @org.junit.jupiter.api.io.TempDir Path project) throws Exception {
        Path source = project.resolve("application.properties");
        Files.writeString(source, "feature.audit.enabled=false");
        try (VectorStore projectStore = new VectorStore(project.toString())) {
            projectStore.clearProject();
            projectStore.replaceProjectIndex(List.of(new VectorStore.CodeChunkEntry(
                    CodeChunk.fileChunk("application.properties", "feature.audit.enabled=false"),
                    new float[]{1.0f})), List.of(), "idx-1");
            Files.writeString(source, "feature.audit.enabled=true");
            projectStore.markDirtyFiles(List.of("application.properties"));

            List<VectorStore.SearchResult> results =
                    projectStore.searchByKeyword("feature.audit.enabled");

            assertEquals(1, results.size());
            assertEquals("feature.audit.enabled=true", results.getFirst().content());
            assertEquals(VectorStore.IndexFreshness.DIRTY, results.getFirst().freshness());
        }
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
