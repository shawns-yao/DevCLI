package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryMarkdownPersistenceTest {

    @Test
    void storesBodyAndEvidenceOnlyInMarkdown(@TempDir Path tempDir) throws Exception {
        MemoryEvidence evidence = new MemoryEvidence(MemoryEvidence.Confidence.HIGH,
                "tool output line 42", "verified against the current project",
                MemoryEvidence.ReviewState.REVIEWED, List.of("old-memory"));
        MemoryEntry entry = new MemoryEntry("lesson-1", "Build with Maven offline.\nKeep the local repository.",
                MemoryEntry.MemoryType.FACT, Instant.parse("2026-08-30T00:00:00Z"),
                Map.of("memory_kind", "LESSON", "topic", "offline build"), 16,
                "maven-offline", true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1,
                null, evidence);

        try (SqliteLongTermMemoryStore store = new SqliteLongTermMemoryStore(tempDir)) {
            assertTrue(store.upsert(entry));
            MemoryEntry loaded = store.loadById("lesson-1").orElseThrow();
            assertEquals(entry.getContent(), loaded.getContent());
            assertEquals(evidence.sourceQuote(), loaded.getEvidence().sourceQuote());
            assertEquals(evidence.reasoning(), loaded.getEvidence().reasoning());
        }

        Path markdown;
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("memory_vectors.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT content, source_quote, evidence_reasoning, markdown_path "
                     + "FROM memory_facts WHERE id = 'lesson-1'")) {
            assertTrue(rows.next());
            assertEquals("", rows.getString("content"));
            assertEquals("", rows.getString("source_quote"));
            assertEquals("", rows.getString("evidence_reasoning"));
            markdown = tempDir.resolve("records").resolve(rows.getString("markdown_path"));
        }
        String source = Files.readString(markdown);
        assertTrue(source.contains("Build with Maven offline."));
        assertTrue(source.contains("tool output line 42"));
    }

    @Test
    void refusesTamperedMarkdownInsteadOfFallingBackToSqliteBody(@TempDir Path tempDir) throws Exception {
        try (SqliteLongTermMemoryStore store = new SqliteLongTermMemoryStore(tempDir)) {
            assertTrue(store.upsert(new MemoryEntry("fact-1", "trusted body",
                    MemoryEntry.MemoryType.FACT, null, 4)));
        }

        Path markdown;
        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("memory_vectors.db"));
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT markdown_path FROM memory_facts WHERE id = 'fact-1'")) {
            assertTrue(rows.next());
            markdown = tempDir.resolve("records").resolve(rows.getString(1));
        }
        Files.writeString(markdown, Files.readString(markdown) + "\nmanual corruption\n");

        try (SqliteLongTermMemoryStore store = new SqliteLongTermMemoryStore(tempDir)) {
            assertTrue(store.loadById("fact-1").isEmpty());
            assertTrue(store.loadAll().isEmpty());
        }
    }

    @Test
    void restoresMarkdownWhenCatalogWriteFails(@TempDir Path tempDir) throws Exception {
        SqliteLongTermMemoryStore store = new SqliteLongTermMemoryStore(tempDir);
        assertTrue(store.upsert(new MemoryEntry("fact-1", "original",
                MemoryEntry.MemoryType.FACT, null, 4)));
        Path markdown = Files.walk(tempDir.resolve("records"))
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .findFirst().orElseThrow();
        String before = Files.readString(markdown);

        store.close();
        assertFalse(store.upsert(new MemoryEntry("fact-1", "replacement",
                MemoryEntry.MemoryType.FACT, null, 4)));
        assertEquals(before, Files.readString(markdown));
    }
}
