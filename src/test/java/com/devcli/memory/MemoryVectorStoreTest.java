package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryVectorStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyStoreReturnsEmptyResults() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            assertTrue(store.isUsable());
            assertEquals(0, store.count());
            assertEquals(List.of(), store.search(new float[]{1f, 0f, 0f}, 5,
                    MemoryVectorStore.DEFAULT_SIMILARITY_THRESHOLD));
        }
    }

    @Test
    void upsertAndSearchByCosineSimilarity() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("f1", "用户偏好 VSCode 编辑器", new float[]{1f, 0f, 0f});
            store.upsert("f2", "项目根 /home/dev/myapp", new float[]{0f, 1f, 0f});
            store.upsert("f3", "RAG recall@5 = 95%", new float[]{0.7f, 0.7f, 0f});
            assertEquals(3, store.count());

            // 查询 [1, 0, 0]：最像 f1，其次 f3，再次 f2
            List<MemoryVectorStore.SearchResult> results = store.search(
                    new float[]{1f, 0f, 0f}, 5, 0.0);
            assertEquals(3, results.size());
            assertEquals("f1", results.get(0).factId());
            assertTrue(results.get(0).similarity() > results.get(1).similarity());
        }
    }

    @Test
    void upsertOverridesExistingFactId() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("f1", "first content", new float[]{1f, 0f});
            store.upsert("f1", "updated content", new float[]{0f, 1f});
            assertEquals(1, store.count());

            List<MemoryVectorStore.SearchResult> r = store.search(new float[]{0f, 1f}, 5, 0.0);
            assertEquals(1, r.size());
            assertEquals("updated content", r.get(0).content());
        }
    }

    @Test
    void deleteRemovesEntry() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("f1", "delete me", new float[]{1f, 0f});
            store.upsert("f2", "keep me", new float[]{0f, 1f});
            store.delete("f1");
            assertEquals(1, store.count());

            List<MemoryVectorStore.SearchResult> r = store.search(new float[]{1f, 0f}, 5, 0.0);
            // 仅剩 f2，相似度 0
            assertTrue(r.isEmpty() || r.get(0).factId().equals("f2"));
        }
    }

    @Test
    void clearWipesAllEntries() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("f1", "a", new float[]{1f, 0f});
            store.upsert("f2", "b", new float[]{0f, 1f});
            store.clear();
            assertEquals(0, store.count());
        }
    }

    @Test
    void thresholdFiltersLowSimilarity() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("similar", "x", new float[]{1f, 0f});
            store.upsert("orthogonal", "y", new float[]{0f, 1f});

            // 阈值 0.5：[1,0] 跟 similar 相似度=1.0 通过，跟 orthogonal=0 不过
            List<MemoryVectorStore.SearchResult> r = store.search(new float[]{1f, 0f}, 5, 0.5);
            assertEquals(1, r.size());
            assertEquals("similar", r.get(0).factId());
        }
    }

    @Test
    void mismatchedDimensionsAreIgnored() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("f1", "3-dim vec", new float[]{1f, 0f, 0f});
            // 用 2 维查询：维度不匹配应被过滤而不是抛异常
            List<MemoryVectorStore.SearchResult> r = store.search(new float[]{1f, 0f}, 5, 0.0);
            assertTrue(r.isEmpty());
        }
    }

    @Test
    void persistsAcrossInstances() {
        try (MemoryVectorStore first = new MemoryVectorStore(tempDir)) {
            first.upsert("f1", "persistent", new float[]{1f, 0f});
        }
        // 新实例同目录应能读到
        try (MemoryVectorStore second = new MemoryVectorStore(tempDir)) {
            assertEquals(1, second.count());
            List<MemoryVectorStore.SearchResult> r = second.search(new float[]{1f, 0f}, 5, 0.0);
            assertEquals(1, r.size());
            assertEquals("f1", r.get(0).factId());
        }
    }

    @Test
    void storesSemanticCardInsteadOfFullMemoryBody() {
        MemoryEntry entry = new MemoryEntry("m1", "完整正文不应进入向量索引", MemoryEntry.MemoryType.FACT,
                java.util.Map.of("memory_kind", "LESSON", "topic", "压缩", "conclusion", "保留证据"), 8);
        String card = MemorySemanticCard.from(entry);
        assertTrue(card.contains("type=LESSON"));
        assertTrue(card.contains("topic=压缩"));
        assertTrue(card.contains("summary=完整正文不应进入向量索引"));
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert(entry.getId(), card, new float[]{1f, 0f});
            assertEquals(card, store.search(new float[]{1f, 0f}, 1, 0.0).getFirst().content());
        }
    }

    @Test
    void upgradesLegacyTableWithoutWritingBodyIntoLegacyColumns() throws Exception {
        java.nio.file.Files.createDirectories(tempDir);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("memory_vectors.db"));
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE memory_vectors (
                        fact_id TEXT PRIMARY KEY,
                        content TEXT NOT NULL,
                        embedding_json TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    INSERT INTO memory_vectors(fact_id, content, embedding_json)
                    VALUES ('old', '旧版完整正文', '[1.0,0.0]')
                    """);
        }
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            store.upsert("m1", "type=LESSON topic=legacy", new float[]{1f, 0f});
            assertEquals(2, store.count());
            MemoryVectorStore.SearchResult inserted = store.search(new float[]{1f, 0f}, 5, 0.0).stream()
                    .filter(result -> "m1".equals(result.factId()))
                    .findFirst().orElseThrow();
            assertEquals("type=LESSON topic=legacy", inserted.content());
        }
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("memory_vectors.db"));
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT content, embedding_json FROM memory_vectors WHERE fact_id='m1'")) {
            assertTrue(rows.next());
            assertEquals("", rows.getString("content"));
            assertEquals("", rows.getString("embedding_json"));
        }
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("memory_vectors.db"));
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT semantic_text, content, embedding_json, dimensions FROM memory_vectors WHERE fact_id='old'")) {
            assertTrue(rows.next());
            assertEquals("summary=旧版完整正文", rows.getString("semantic_text"));
            assertEquals("", rows.getString("content"));
            assertEquals("", rows.getString("embedding_json"));
            assertEquals(2, rows.getInt("dimensions"));
        }
    }

    @Test
    void invalidArgumentsAreSafe() {
        try (MemoryVectorStore store = new MemoryVectorStore(tempDir)) {
            // null fact id / null embedding 都应静默忽略
            store.upsert(null, "x", new float[]{1f});
            store.upsert("f1", "x", null);
            assertEquals(0, store.count());

            // null 查询向量
            assertEquals(List.of(), store.search(null, 5, 0.0));
            // 空向量
            assertEquals(List.of(), store.search(new float[]{}, 5, 0.0));
        }
    }
}
