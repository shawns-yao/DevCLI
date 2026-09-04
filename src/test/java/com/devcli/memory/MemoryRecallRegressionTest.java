package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRecallRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void frequentlyRecalledOldFactsDoNotCrowdOutNewRelevantFacts() {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            List<String> oldIds = new ArrayList<>();
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            for (int i = 0; i < 25; i++) {
                String id = "old-" + i;
                oldIds.add(id);
                memory.storeManaged(entry(id, "project note old-" + i, base.plusSeconds(i)));
            }
            for (int i = 0; i < 5; i++) {
                assertTrue(memory.recordRecalled(oldIds, base.plusSeconds(100 + i)));
            }
            memory.storeManaged(entry("new", "project exact target", base.plusSeconds(1_000)));

            assertEquals("new", memory.search("project exact target", 1).getFirst().getId());
        }
    }

    @Test
    void expiredEntryIsNotRecallableEvenBeforeStoreCleanup() {
        MemoryEntry expired = new MemoryEntry(
                "expired", "old fact", MemoryEntry.MemoryType.FACT,
                Instant.now().minusSeconds(10), Map.of(), 2,
                "", true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1,
                Instant.now().minusSeconds(1));

        assertFalse(expired.isRecallable());
    }

    @Test
    void supersedingFactRemovesTheOldVectorEntry() {
        CopyOnWriteArrayList<String> deleted = new CopyOnWriteArrayList<>();
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.setVectorIndex(entry -> { }, deleted::add, () -> { });
            memory.storeWithSubject(entry("old", "project default Java version is 17",
                    "project.java.version", Instant.now()));
            memory.storeWithSubject(entry("new", "project default Java version is 21",
                    "project.java.version", Instant.now()));

            assertTrue(deleted.contains("old"), deleted.toString());
        }
    }

    private static MemoryEntry entry(String id, String content, Instant timestamp) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, timestamp,
                Map.of(), MemoryEntry.estimateTokens(content));
    }

    private static MemoryEntry entry(String id, String content, String subject, Instant timestamp) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, timestamp,
                Map.of(), MemoryEntry.estimateTokens(content), subject, true, "");
    }
}
