package com.paicli.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    private LongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new LongTermMemory(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        // SQLite store 必须在 @TempDir 清理前关闭，否则 Windows 会因文件锁导致目录删不掉
        if (memory != null) {
            memory.close();
        }
    }

    @Test
    void shouldStoreAndRetrieve() {
        MemoryEntry entry = new MemoryEntry("fact-1", "项目使用Java 17", MemoryEntry.MemoryType.FACT, null, 10);
        memory.store(entry);

        assertTrue(memory.retrieve("fact-1").isPresent());
        assertEquals("项目使用Java 17", memory.retrieve("fact-1").get().getContent());
    }

    @Test
    void shouldDeduplicateSameContent() {
        MemoryEntry entry1 = new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);
        MemoryEntry entry2 = new MemoryEntry("fact-2", "相同内容", MemoryEntry.MemoryType.FACT, null, 5);

        memory.store(entry1);
        memory.store(entry2);

        assertEquals(1, memory.size());
    }

    @Test
    void shouldUpdateSameIdAndKeepTokenCounterConsistent() {
        memory.store(new MemoryEntry("fact-1", "旧内容", MemoryEntry.MemoryType.FACT, null, 5));
        memory.store(new MemoryEntry("fact-1", "新内容", MemoryEntry.MemoryType.FACT, null, 9));

        assertEquals(1, memory.size());
        assertEquals("新内容", memory.retrieve("fact-1").orElseThrow().getContent());
        assertEquals(9, memory.getTokenCount());

        memory.store(new MemoryEntry("fact-2", "旧内容", MemoryEntry.MemoryType.FACT, null, 4));
        assertEquals(2, memory.size(), "同 id 更新后旧 content hash 应释放，后续旧内容可作为新事实写入");
        assertEquals(13, memory.getTokenCount());
    }

    @Test
    void shouldUpdateSameIdEvenWhenContentIsUnchanged() {
        Instant oldTime = Instant.parse("2024-01-01T00:00:00Z");
        Instant newTime = Instant.parse("2025-01-01T00:00:00Z");

        memory.store(new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT,
                oldTime, Map.of("source", "old"), 5));
        memory.store(new MemoryEntry("fact-1", "相同内容", MemoryEntry.MemoryType.FACT,
                newTime, Map.of("source", "new"), 8));

        MemoryEntry updated = memory.retrieve("fact-1").orElseThrow();
        assertEquals(1, memory.size());
        assertEquals(newTime, updated.getTimestamp());
        assertEquals("new", updated.getMetadata().get("source"));
        assertEquals(8, memory.getTokenCount());
    }

    @Test
    void shouldSearchByKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用IntelliJ IDEA", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("f2", "项目路径: /home/user/project", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("IntelliJ", 5);
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchByMultipleKeywords() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("Java 偏好", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldSearchChineseWithoutRelyingOnSpaces() {
        memory.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = memory.search("偏好设置", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldDeleteEntry() {
        memory.store(new MemoryEntry("f1", "测试内容", MemoryEntry.MemoryType.FACT, null, 5));
        assertTrue(memory.delete("f1"));
        assertEquals(0, memory.size());
    }

    @Test
    void shouldFilterByType() {
        memory.store(new MemoryEntry("f1", "事实1", MemoryEntry.MemoryType.FACT, null, 5));
        memory.store(new MemoryEntry("s1", "摘要1", MemoryEntry.MemoryType.SUMMARY, null, 5));

        var facts = memory.getByType(MemoryEntry.MemoryType.FACT);
        assertEquals(1, facts.size());
    }

    @Test
    void shouldPersistAndReload() {
        memory.store(new MemoryEntry("f1", "持久化测试内容", MemoryEntry.MemoryType.FACT, null, 10));
        memory.store(new MemoryEntry("s1", "摘要测试", MemoryEntry.MemoryType.SUMMARY, null, 8));

        // 关掉第一个实例释放 SQLite 连接，让第二个实例能干净读
        memory.close();
        memory = null;

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            assertEquals(2, reloaded.size());
            assertTrue(reloaded.retrieve("f1").isPresent());
        }
    }

    @Test
    void shouldPreserveTimestampAfterReload() {
        Instant timestamp = Instant.parse("2026-04-20T12:34:56Z");
        memory.store(new MemoryEntry("f1", "带时间戳的事实", MemoryEntry.MemoryType.FACT, timestamp, null, 10));

        memory.close();
        memory = null;

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            assertEquals(timestamp, reloaded.retrieve("f1").orElseThrow().getTimestamp());
        }
    }

    @Test
    void shouldMigrateLegacyJsonOnStartup() throws Exception {
        // 主动写一份 v1 风格的 long_term_memory.json，模拟旧用户升级
        Path legacy = tempDir.resolve("long_term_memory.json");
        java.nio.file.Files.writeString(legacy, """
                [
                  {
                    "id": "legacy-1",
                    "content": "旧版用户偏好",
                    "type": "FACT",
                    "timestamp": "2024-01-01T00:00:00Z",
                    "metadata": {"source": "user-cli"},
                    "tokenCount": 8
                  }
                ]
                """);

        memory.close();
        try (LongTermMemory migrated = new LongTermMemory(tempDir.toFile())) {
            assertEquals(1, migrated.size());
            MemoryEntry entry = migrated.retrieve("legacy-1").orElseThrow();
            assertEquals("旧版用户偏好", entry.getContent());
            assertEquals("user-cli", entry.getMetadata().get("source"));
        }

        // JSON 应被重命名为 .bak，不再是 .json
        assertFalse(java.nio.file.Files.exists(legacy));
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("long_term_memory.json.bak")));
        // 重置 memory 引用避免 @AfterEach NPE
        memory = new LongTermMemory(tempDir.toFile());
    }

    @Test
    void shouldKeepLegacyJsonWhenMigrationStoreDoesNotConfirmWrite() throws Exception {
        Path legacy = tempDir.resolve("long_term_memory.json");
        java.nio.file.Files.writeString(legacy, """
                [
                  {
                    "id": "legacy-1",
                    "content": "旧版用户偏好",
                    "type": "FACT",
                    "timestamp": "2024-01-01T00:00:00Z",
                    "metadata": {"source": "user-cli"},
                    "tokenCount": 8
                  }
                ]
                """);

        memory.close();
        memory = null;

        try (LongTermMemory migrated = new LongTermMemory(new FailingStore(), tempDir)) {
            assertEquals(0, migrated.size());
        }

        assertTrue(java.nio.file.Files.exists(legacy), "迁移未确认写入时必须保留旧 JSON");
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("long_term_memory.json.bak")));
    }

    @Test
    void shouldNotExposeEntryOrVectorWhenPersistentStoreRejectsWrite() {
        memory.close();
        memory = null;

        FailingStore store = new FailingStore();
        try (LongTermMemory failingMemory = new LongTermMemory(store, tempDir)) {
            java.util.concurrent.atomic.AtomicInteger vectorWrites = new java.util.concurrent.atomic.AtomicInteger();
            failingMemory.setVectorIndex(entry -> vectorWrites.incrementAndGet(), id -> {}, () -> {});

            failingMemory.store(new MemoryEntry("fact-1", "不会持久化的事实", MemoryEntry.MemoryType.FACT, null, 10));

            assertEquals(0, failingMemory.size(), "持久化 store 拒绝写入时不应污染内存视图");
            assertTrue(failingMemory.retrieve("fact-1").isEmpty());
            assertEquals(0, vectorWrites.get(), "事实未持久化时不应写入向量索引");
        }
    }

    private static final class FailingStore implements LongTermMemoryStore {
        @Override
        public List<MemoryEntry> loadAll() {
            return List.of();
        }

        @Override
        public boolean upsert(MemoryEntry entry) {
            return false;
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public void clear() {
        }

        @Override
        public void close() {
        }
    }
}
