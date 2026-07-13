package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void sameSubjectIncrementsRevisionAndMarksConflict() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeWithSubject(entry("old", "项目默认 Java 版本是 17", "project.java_version", null));
            memory.storeWithSubject(entry("new", "项目默认 Java 版本是 21", "project.java_version", null));

            MemoryEntry latest = memory.search("Java 版本", 5).getFirst();
            MemoryEntry previous = memory.retrieve("old").orElseThrow();
            assertEquals(2, latest.getRevision());
            assertEquals(MemoryEntry.CURRENT_SCHEMA_VERSION, latest.getSchemaVersion());
            assertEquals("old", latest.getMetadata().get("conflict_with"));
            assertFalse(previous.isActive());
            assertEquals("new", previous.getSupersededBy());
        }
    }

    @Test
    void inferredClaimConflictSupersedesUnversionedMemory() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeManaged(entry("old", "server.port=8080", "", null));
            memory.storeManaged(entry("new", "server.port=8443", "", null));

            assertFalse(memory.retrieve("old").orElseThrow().isActive());
            MemoryEntry latest = memory.search("server.port", 5).getFirst();
            assertEquals(2, latest.getRevision());
            assertEquals("old", latest.getMetadata().get("conflict_with"));
            assertFalse(latest.getSubject().isBlank());
        }
    }

    @Test
    void lifecycleFieldsPersistAcrossReload() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3_600);
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeWithSubject(entry("old", "项目默认 Java 版本是 17", "project.java_version", expiresAt));
            memory.storeWithSubject(entry("new", "项目默认 Java 版本是 21", "project.java_version", expiresAt));
        }

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry latest = reloaded.retrieve("new").orElseThrow();
            assertEquals(2, latest.getRevision());
            assertEquals(expiresAt.toEpochMilli(), latest.getExpiresAt().toEpochMilli());
            assertEquals(MemoryEntry.CURRENT_SCHEMA_VERSION, latest.getSchemaVersion());
        }
    }

    @Test
    void expiredMemoryIsExcludedAndPruned() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("expired", "临时事实", "", Instant.now().minusSeconds(1)));

            assertTrue(memory.search("临时事实", 5).isEmpty());
            assertTrue(memory.retrieve("expired").isEmpty());
            assertEquals(0, memory.size());
        }
    }

    private static MemoryEntry entry(String id, String content, String subject, Instant expiresAt) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of(), MemoryEntry.estimateTokens(content), subject, true, "",
                MemoryEntry.CURRENT_SCHEMA_VERSION, 1, expiresAt);
    }
}
