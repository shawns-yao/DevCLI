package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryHotCacheTest {

    @Test
    void keepsBoundedWorkingSetWithoutHidingColdMarkdown(@TempDir Path tempDir) {
        try (LongTermMemory memory = new LongTermMemory(
                new SqliteLongTermMemoryStore(tempDir), tempDir, new MemoryHotCache(3))) {
            for (int i = 0; i < 6; i++) {
                memory.store(new MemoryEntry("fact-" + i, "unique-memory-value-" + i,
                        MemoryEntry.MemoryType.FACT, null, 4));
            }
            assertEquals(6, memory.size());
            assertEquals(3, memory.hotCacheSize());
            assertFalse(memory.isHot("fact-0"));

            assertEquals("unique-memory-value-0",
                    memory.search("unique-memory-value-0", 5).getFirst().getContent());
            assertTrue(memory.isHot("fact-0"), () -> "hot ids=" + memory.hotIds());
            assertEquals(3, memory.hotCacheSize());

            assertEquals(6, memory.getAll().size());
            assertEquals(3, memory.hotCacheSize(), "全量管理读取不能把 Hot Working Set 撑大");
        }
    }

    @Test
    void reloadsOnlyConfiguredHotWorkingSet(@TempDir Path tempDir) {
        try (LongTermMemory first = new LongTermMemory(
                new SqliteLongTermMemoryStore(tempDir), tempDir, new MemoryHotCache(3))) {
            for (int i = 0; i < 6; i++) {
                first.store(new MemoryEntry("fact-" + i, "reload-value-" + i,
                        MemoryEntry.MemoryType.FACT, null, 4));
            }
        }

        try (LongTermMemory reloaded = new LongTermMemory(
                new SqliteLongTermMemoryStore(tempDir), tempDir, new MemoryHotCache(3))) {
            assertEquals(6, reloaded.size());
            assertEquals(3, reloaded.hotCacheSize());
            assertEquals("reload-value-0", reloaded.retrieve("fact-0").orElseThrow().getContent());
            assertEquals(3, reloaded.hotCacheSize());
        }
    }

    @Test
    void preheatsHigherImportanceMemoryBeforeOlderLowImportanceEntry(@TempDir Path tempDir) {
        try (LongTermMemory first = new LongTermMemory(
                new SqliteLongTermMemoryStore(tempDir), tempDir, new MemoryHotCache(1))) {
            first.store(entry("low", Map.of("importance", "LOW")));
            first.store(entry("high", Map.of("importance", "HIGH")));
        }

        try (LongTermMemory reloaded = new LongTermMemory(
                new SqliteLongTermMemoryStore(tempDir), tempDir, new MemoryHotCache(1))) {
            assertTrue(reloaded.isHot("high"));
            assertFalse(reloaded.isHot("low"));
        }
    }

    @Test
    void prefersPinnedMemoryDuringEviction() {
        MemoryHotCache cache = new MemoryHotCache(2);
        cache.put(entry("pinned", Map.of("pinned", "true", "importance", "HIGH")));
        cache.put(entry("normal-1", Map.of()));
        cache.put(entry("normal-2", Map.of()));

        assertTrue(cache.contains("pinned"));
        assertFalse(cache.contains("normal-1"));
        assertTrue(cache.contains("normal-2"));
        assertEquals(2, cache.size());
    }

    @Test
    void sqliteCatalogDoesNotHydrateMarkdownBodies(@TempDir Path tempDir) {
        try (SqliteLongTermMemoryStore store = new SqliteLongTermMemoryStore(tempDir)) {
            assertTrue(store.upsert(new MemoryEntry("fact-1", "body lives in Markdown",
                    MemoryEntry.MemoryType.FACT, null, 5)));
            assertEquals("", store.loadCatalog().getFirst().getContent());
            assertEquals("body lives in Markdown", store.loadById("fact-1").orElseThrow().getContent());
        }
    }

    private static MemoryEntry entry(String id, Map<String, String> metadata) {
        return new MemoryEntry(id, "content-" + id, MemoryEntry.MemoryType.FACT,
                null, metadata, 4);
    }
}
