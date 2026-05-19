package com.paicli.memory;

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
