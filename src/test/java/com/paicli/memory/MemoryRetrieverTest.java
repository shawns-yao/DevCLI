package com.paicli.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路径 B 重构后：MemoryRetriever 只检索 {@link LongTermMemory}。
 * 短期/工作记忆走 {@link WorkingMemory#renderForPrompt()} 直接注入 system prompt，
 * 不再参与 query-based 检索。
 */
class MemoryRetrieverTest {
    @TempDir
    Path tempDir;

    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        longTerm = new LongTermMemory(tempDir.toFile());
        retriever = new MemoryRetriever(longTerm);
    }

    @AfterEach
    void tearDown() {
        // SQLite store 必须显式 close 让 @TempDir 能清理（Windows 文件锁问题）
        if (longTerm != null) {
            longTerm.close();
        }
    }

    @Test
    void shouldRetrieveFromLongTerm() {
        longTerm.store(new MemoryEntry("f1", "用户偏好：喜欢用Spring Boot", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieveLongTerm("Spring Boot", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.FACT, null, 10));

        String context = retriever.buildContextForQuery("项目路径", 200);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
    }

    @Test
    void semanticSearchMergesWithKeywordAndReranks() {
        // PR-C：语义召回扩展候选，关键词召回保留精确命中，两路合并排序
        longTerm.store(new MemoryEntry("f1", "用户偏好 VSCode 编辑器", MemoryEntry.MemoryType.FACT, null, 10));
        longTerm.store(new MemoryEntry("f2", "用户偏好 IntelliJ IDEA", MemoryEntry.MemoryType.FACT, null, 10));

        retriever.setSemanticSearch((query, topK) ->
                java.util.List.of(new MemoryRetriever.SemanticHit("f2", 0.85)));

        var results = retriever.retrieveLongTerm("VSCode", 5);
        assertEquals(2, results.size());
        assertEquals("f1", results.get(0).getId(),
                "关键词精确命中应参与合并排序，不能被语义命中覆盖");
        assertEquals("f2", results.get(1).getId(),
                "语义命中仍应作为扩展召回返回");
    }

    @Test
    void semanticAndKeywordHitSameEntryDeduplicates() {
        longTerm.store(new MemoryEntry("f1", "用户偏好 VSCode 编辑器", MemoryEntry.MemoryType.FACT, null, 10));

        retriever.setSemanticSearch((query, topK) ->
                java.util.List.of(new MemoryRetriever.SemanticHit("f1", 0.9)));

        var results = retriever.retrieveLongTerm("VSCode", 5);
        assertEquals(1, results.size());
        assertEquals("f1", results.get(0).getId(), "语义和关键词命中同一事实时应去重");
    }

    @Test
    void semanticSearchEmptyHitFallsBackToKeyword() {
        // PR-C：语义检索返回空时 fallback 到关键词
        longTerm.store(new MemoryEntry("f1", "项目使用 Spring Boot", MemoryEntry.MemoryType.FACT, null, 10));

        retriever.setSemanticSearch((query, topK) -> java.util.List.of());

        var results = retriever.retrieveLongTerm("Spring Boot", 5);
        assertEquals(1, results.size());
        assertEquals("f1", results.get(0).getId(),
                "语义返回空时应 fallback 到关键词检索");
    }

    @Test
    void semanticSearchHitWithNoMatchingEntryFallsBack() {
        // PR-C：语义返回 fact_id 但 LongTermMemory 没这条（向量库延迟同步）→ 关键词 fallback
        longTerm.store(new MemoryEntry("f1", "用户偏好简体中文", MemoryEntry.MemoryType.FACT, null, 10));

        retriever.setSemanticSearch((query, topK) ->
                java.util.List.of(new MemoryRetriever.SemanticHit("ghost-id", 0.99)));

        var results = retriever.retrieveLongTerm("中文", 5);
        assertEquals(1, results.size());
        assertEquals("f1", results.get(0).getId(),
                "向量库脏数据指向不存在的 fact_id 时应 fallback 关键词");
    }

    @Test
    void longTermMemoryStoreTriggersVectorIndexHook() {
        // PR-C：LongTermMemory.store 后应触发 onStore 钩子
        java.util.concurrent.atomic.AtomicReference<MemoryEntry> stored = new java.util.concurrent.atomic.AtomicReference<>();
        longTerm.setVectorIndex(stored::set, id -> {}, () -> {});

        MemoryEntry entry = new MemoryEntry("f-new", "新事实", MemoryEntry.MemoryType.FACT, null, 5);
        longTerm.store(entry);

        assertNotNull(stored.get());
        assertEquals("f-new", stored.get().getId());
    }

    @Test
    void longTermMemoryDeleteTriggersVectorIndexHook() {
        java.util.concurrent.atomic.AtomicReference<String> deletedId = new java.util.concurrent.atomic.AtomicReference<>();
        longTerm.store(new MemoryEntry("f1", "to delete", MemoryEntry.MemoryType.FACT, null, 5));
        longTerm.setVectorIndex(e -> {}, deletedId::set, () -> {});

        longTerm.delete("f1");
        assertEquals("f1", deletedId.get());
    }

    @Test
    void longTermMemoryClearTriggersVectorIndexHook() {
        java.util.concurrent.atomic.AtomicInteger clearCount = new java.util.concurrent.atomic.AtomicInteger();
        longTerm.setVectorIndex(e -> {}, id -> {}, clearCount::incrementAndGet);

        longTerm.clear();
        assertEquals(1, clearCount.get());
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        longTerm.store(new MemoryEntry("e1", "无关内容", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieveLongTerm("Spring Boot", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldRetrieveChineseByPhraseFragments() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieveLongTerm("偏好设置", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }
}
