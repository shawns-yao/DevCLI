package com.devcli.runtime.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteRunStoreTest {

    @Test
    void persistsVersionedLifecycleAndAttempt(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            RunStore.RunRecord created = store.create(new RunStore.Submission(
                    "run-1", "thread-1", "main", RunStore.Source.RUNTIME_API,
                    "react", "hello"));

            assertEquals(RunStore.Status.ENQUEUED, created.status());
            assertEquals(0, created.attempt());
            assertEquals(0, created.version());
            assertTrue(store.start(created.id()));

            RunStore.RunRecord running = store.find(created.id()).orElseThrow();
            assertEquals(RunStore.Status.RUNNING, running.status());
            assertEquals(1, running.attempt());
            assertEquals(1, running.version());
            assertNotNull(running.startedAt());

            assertTrue(store.complete(created.id(), "done"));
            RunStore.RunRecord completed = store.find(created.id()).orElseThrow();
            assertEquals(RunStore.Status.COMPLETED, completed.status());
            assertEquals("done", completed.result());
            assertEquals(2, completed.version());
            assertFalse(store.start(created.id()));
        }
    }

    @Test
    void recoversBackgroundRunWithoutResettingAttempt(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            RunStore.RunRecord created = store.create(new RunStore.Submission(
                    "task-1", "", "main", RunStore.Source.BACKGROUND,
                    "react", "resume"));
            assertTrue(store.start(created.id()));

            assertEquals(1, store.recoverRunning(
                    RunStore.Source.BACKGROUND,
                    RunStore.Status.ENQUEUED,
                    "process_restart"));

            RunStore.RunRecord recovered = store.find(created.id()).orElseThrow();
            assertEquals(RunStore.Status.ENQUEUED, recovered.status());
            assertEquals(1, recovered.attempt());
            assertEquals("process_restart", recovered.recoveryReason());
            assertNull(recovered.startedAt());
        }
    }

    @Test
    void importsLegacyTaskDatabaseReadOnlyAndDoesNotOverwrite(@TempDir Path tempDir) throws Exception {
        Path legacy = tempDir.resolve("tasks.db");
        createLegacyDatabase(legacy);
        FileTime modifiedBefore = Files.getLastModifiedTime(legacy);

        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            assertEquals(1, store.importLegacyTasks(legacy));
            RunStore.RunRecord imported = store.find("legacy-1").orElseThrow();
            assertEquals(RunStore.Source.BACKGROUND, imported.source());
            assertEquals(RunStore.Status.COMPLETED, imported.status());
            assertEquals("legacy result", imported.result());
            assertEquals("legacy_tasks_db_import", imported.recoveryReason());

            assertEquals(0, store.importLegacyTasks(legacy));
            assertEquals("legacy result", store.find("legacy-1").orElseThrow().result());
        }

        assertEquals(modifiedBefore, Files.getLastModifiedTime(legacy));
    }

    private static void createLegacyDatabase(Path path) throws Exception {
        String now = Instant.now().toString();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runtime_tasks (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        result TEXT,
                        error TEXT,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT,
                        duration_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO runtime_tasks(
                        id, status, prompt, result, error, created_at,
                        started_at, finished_at, updated_at, duration_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, "legacy-1");
                insert.setString(2, "completed");
                insert.setString(3, "legacy prompt");
                insert.setString(4, "legacy result");
                insert.setString(5, "");
                insert.setString(6, now);
                insert.setString(7, now);
                insert.setString(8, now);
                insert.setString(9, now);
                insert.setLong(10, 5L);
                insert.executeUpdate();
            }
        }
    }
}
