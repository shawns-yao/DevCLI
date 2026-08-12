package com.devcli.runtime.store;

import com.devcli.budget.RunBudget;
import com.devcli.budget.RunBudgetPolicy;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.RunCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRunStoreTest {
    @Test
    void preservesLegacyRuntimeTablesWhileAddingRunSchema(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runtime_threads(id TEXT PRIMARY KEY, created_at TEXT NOT NULL)");
            statement.execute("INSERT INTO runtime_threads VALUES ('thread_old', '2026-01-01T00:00:00Z')");
        }
        try (SqliteRunStore store = new SqliteRunStore(db)) {
            assertTrue(store.list(null, 10).isEmpty());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var rows = connection.createStatement().executeQuery(
                     "SELECT id FROM runtime_threads WHERE id = 'thread_old'")) {
            assertTrue(rows.next());
        }
    }

    @Test
    void migratesLegacyTasksWithoutRequeueingRunningSideEffects(@TempDir Path tempDir) throws Exception {
        Path legacy = tempDir.resolve("tasks.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE runtime_tasks(
                        id TEXT PRIMARY KEY, status TEXT NOT NULL, prompt TEXT NOT NULL,
                        result TEXT, error TEXT, created_at TEXT NOT NULL,
                        started_at TEXT, finished_at TEXT, updated_at TEXT, duration_ms INTEGER)
                    """);
            statement.execute("""
                    INSERT INTO runtime_tasks VALUES(
                        'task_old', 'running', 'mutate files', '', '',
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', NULL,
                        '2026-01-01T00:00:01Z', 0)
                    """);
        }
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            assertEquals(1, store.migrateLegacyTasks(legacy));
            assertEquals(0, store.migrateLegacyTasks(legacy));
            RunRecord migrated = store.find("task_old").orElseThrow();
            assertEquals(SubmissionSource.BACKGROUND, migrated.source());
            assertEquals(RunStatus.RECOVERY_REQUIRED, migrated.status());
        }
    }

    @Test
    void idempotencyKeyAndVersionCasPreventDuplicateOrStaleUpdates(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            RunSubmission submission = new RunSubmission(
                    "", SubmissionSource.BACKGROUND, "", tempDir, "same", "key-1", "");
            RunRecord first = store.submit(submission);
            assertEquals(first.id(), store.submit(submission).id());
            RunStore.ClaimedRun claimed = store.claimNext(
                    SubmissionSource.BACKGROUND, "worker", Duration.ofMinutes(1)).orElseThrow();
            assertFalse(store.complete(claimed.run().id(), first.version(), claimed.attempt().id(),
                    RunStatus.COMPLETED, "stale", "", null));
            assertTrue(store.complete(claimed.run().id(), claimed.run().version(), claimed.attempt().id(),
                    RunStatus.COMPLETED, "ok", "", null));
        }
    }

    @Test
    void expiredLeaseRequiresReconciliationBeforeAnotherAttempt(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            String runId = store.submit(new RunSubmission(
                    "", SubmissionSource.BACKGROUND, "", tempDir, "resume", "", "")).id();
            RunStore.ClaimedRun first = store.claimNext(
                    SubmissionSource.BACKGROUND, "worker-1", Duration.ofMillis(1)).orElseThrow();
            assertTrue(store.claimNextById(runId, "worker-2", Duration.ofMinutes(1)).isEmpty());
            Thread.sleep(10);
            assertEquals(RunStatus.RECOVERY_REQUIRED, store.reconcileExpiredLeases().getFirst().status());
            assertTrue(store.claimNextById(runId, "worker-2", Duration.ofMinutes(1)).isEmpty());
            RunStore.ClaimedRun second = store.claimRecoveryById(
                    runId, "worker-2", Duration.ofMinutes(1)).orElseThrow();
            assertEquals(first.attempt().sequence() + 1, second.attempt().sequence());
        }
    }

    @Test
    void restoredRunContextKeepsConsumedBudget(@TempDir Path tempDir) {
        RunBudget.Snapshot usage = new RunBudget.Snapshot(
                120, 30, 10, 2, 3, 0, new BigDecimal("0.25"),
                "USD", 0, 500, RunBudget.Decision.CONTINUE, "");
        RunContext.RunBudgetState state = new RunContext.RunBudgetState(
                1, "run_budget", RunBudgetPolicy.forTier(RunBudgetPolicy.Tier.ECONOMY),
                usage, Instant.now());
        try (RunContext context = CancellationContext.startRunContext(
                "run_budget", tempDir, state)) {
            RunBudget.Snapshot restored = context.runBudget().snapshot();
            assertEquals(120, restored.inputTokens());
            assertEquals(30, restored.outputTokens());
            assertEquals(2, restored.llmCalls());
            assertEquals(3, restored.toolCalls());
            assertTrue(restored.elapsedMillis() >= 500);
        }
    }

    @Test
    void persistsBudgetAndRecoveryReferences(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            RunRecord submitted = store.submit(new RunSubmission(
                    "run_refs", SubmissionSource.CLI, "thread_refs", tempDir,
                    "inspect", "", ""));
            RunBudget.Snapshot usage = new RunBudget.Snapshot(
                    10, 5, 0, 1, 2, 0, BigDecimal.ZERO,
                    "unknown", 1, 20, RunBudget.Decision.CONTINUE, "");
            RunContext.RunBudgetState budget = new RunContext.RunBudgetState(
                    1, submitted.id(), RunBudgetPolicy.forTier(RunBudgetPolicy.Tier.BALANCED),
                    usage, Instant.now());
            assertTrue(store.saveBudgetState(submitted.id(), submitted.version(), budget));
            RunRecord withBudget = store.find(submitted.id()).orElseThrow();
            assertEquals(10, store.budgetState(submitted.id()).orElseThrow().usage().inputTokens());
            assertTrue(store.saveRecoveryReferences(withBudget.id(), withBudget.version(),
                    "checkpoint:orch", "patch:orch", "snapshot:commit"));
            RunRecord persisted = store.find(submitted.id()).orElseThrow();
            assertEquals("checkpoint:orch", persisted.checkpointRef());
            assertEquals("patch:orch", persisted.patchJournalRef());
            assertEquals("snapshot:commit", persisted.snapshotRef());
            assertTrue(store.clearRecoveryReferences(
                    persisted.id(), persisted.version(), true, true, false));
            RunRecord cleared = store.find(submitted.id()).orElseThrow();
            assertEquals("", cleared.checkpointRef());
            assertEquals("", cleared.patchJournalRef());
            assertEquals("snapshot:commit", cleared.snapshotRef());
        }
    }

    @Test
    void coordinatorRequiresExplicitRecoveryProof(@TempDir Path tempDir) throws Exception {
        try (SqliteRunStore store = new SqliteRunStore(tempDir.resolve("runtime.db"))) {
            RunRecord submitted = store.submit(new RunSubmission(
                    "run_unsafe", SubmissionSource.BACKGROUND, "", tempDir,
                    "mutate", "", ""));
            assertTrue(store.saveRecoveryReferences(submitted.id(), submitted.version(),
                    "agent-checkpoint:orch", "patch-journal:orch", ""));
            RunRecord withRefs = store.find(submitted.id()).orElseThrow();
            RunContext.RunBudgetState budget = new RunContext.RunBudgetState(
                    1, submitted.id(), RunBudgetPolicy.forTier(RunBudgetPolicy.Tier.BALANCED),
                    new RunBudget.Snapshot(0, 0, 0, 0, 0, 0,
                            BigDecimal.ZERO, "unknown", 0, 0,
                            RunBudget.Decision.CONTINUE, ""), Instant.now());
            assertTrue(store.saveBudgetState(withRefs.id(), withRefs.version(), budget));
            RunStore.ClaimedRun claimed = store.claimNextById(
                    submitted.id(), "worker-1", Duration.ofMillis(1)).orElseThrow();
            Thread.sleep(10);
            store.reconcileExpiredLeases();

            RunCoordinator coordinator = new RunCoordinator(store, Duration.ofMinutes(1));
            assertTrue(coordinator.claim(submitted.id(), "worker-2").isEmpty());
            assertTrue(coordinator.claimRecovery(submitted.id(), "worker-2",
                    RunCoordinator.RecoveryProof.unsafe(), event -> { }).isEmpty());
            assertEquals(RunStatus.RECOVERY_REQUIRED,
                    store.find(submitted.id()).orElseThrow().status());
            assertTrue(coordinator.claimRecovery(submitted.id(), "worker-2",
                    new RunCoordinator.RecoveryProof(true, true, true, "rolled_back"),
                    event -> { }).isPresent());
        }
    }
}
