package com.devcli.runtime.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryEvidenceRunStoreTest {

    @Test
    void recoveryEvidenceRefRejectsMissingHighIntegrityFields() {
        assertThrows(IllegalArgumentException.class, () -> new RecoveryEvidenceRef(
                "run-1", "thread-1", "main", null, "key", "/checkpoint.json", "",
                RecoveryEvidenceRef.State.PRESENT, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryEvidenceRef(
                "run-1", "thread-1", "main", RecoveryEvidenceRef.Kind.CHECKPOINT,
                "key", "/checkpoint.json", "", null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryEvidenceRef(
                "run-1", "thread-1", "main", RecoveryEvidenceRef.Kind.CHECKPOINT,
                "key", null, "", RecoveryEvidenceRef.State.PRESENT, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new RecoveryEvidenceRef(
                "run-1", "thread-1", "main", RecoveryEvidenceRef.Kind.CHECKPOINT,
                "key", "  ", "", RecoveryEvidenceRef.State.PRESENT, null, null, 0));

        RecoveryEvidenceRef deleted = ref("run-1", RecoveryEvidenceRef.Kind.CHECKPOINT,
                "key", "/checkpoint.json", RecoveryEvidenceRef.State.DELETED);
        assertEquals("/checkpoint.json", deleted.normalizedReference());
    }

    @Test
    void upsertsTypedReferencesIdempotentlyAndEnforcesStateTransitions(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("runtime.db");
        try (SqliteRunStore store = new SqliteRunStore(db)) {
            RecoveryEvidenceRef prepared = ref("run-1", RecoveryEvidenceRef.Kind.PATCH_JOURNAL,
                    "step-1", "checkpoint/step-1", RecoveryEvidenceRef.State.PREPARED);
            RecoveryEvidenceRef first = store.upsertRecoveryEvidence(prepared);
            RecoveryEvidenceRef same = store.upsertRecoveryEvidence(prepared);
            assertEquals(1L, first.version());
            assertEquals(first, same);

            RecoveryEvidenceRef completed = prepared.withState(RecoveryEvidenceRef.State.COMPLETED);
            RecoveryEvidenceRef updated = store.upsertRecoveryEvidence(completed);
            assertEquals(2L, updated.version());
            assertEquals(RecoveryEvidenceRef.State.COMPLETED, updated.state());

            assertThrows(IllegalStateException.class, () -> store.upsertRecoveryEvidence(
                    completed.withState(RecoveryEvidenceRef.State.PREPARED)));

            store.upsertRecoveryEvidence(ref("run-1", RecoveryEvidenceRef.Kind.CHECKPOINT,
                    "z", "checkpoints/z.json", RecoveryEvidenceRef.State.PRESENT));
            store.upsertRecoveryEvidence(ref("run-1", RecoveryEvidenceRef.Kind.SIDE_GIT,
                    "a", "abc123", RecoveryEvidenceRef.State.COMPLETED));
            List<RecoveryEvidenceRef> ordered = store.listRecoveryEvidence("run-1", 10);
            assertEquals(List.of("a", "z", "step-1"),
                    ordered.stream().map(RecoveryEvidenceRef::logicalKey).toList());
        }

        try (SqliteRunStore reopened = new SqliteRunStore(db)) {
            RecoveryEvidenceRef persisted = reopened.listRecoveryEvidence("run-1", 10).stream()
                    .filter(ref -> ref.logicalKey().equals("step-1"))
                    .findFirst().orElseThrow();
            assertEquals(RecoveryEvidenceRef.State.COMPLETED, persisted.state());
            assertEquals(2L, persisted.version());
        }
    }

    @Test
    void allowsFailedPatchJournalToReconcileWithMonotonicVersions(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("runtime.db");
        try (SqliteRunStore store = new SqliteRunStore(db)) {
            RecoveryEvidenceRef prepared = ref("run-reconcile", RecoveryEvidenceRef.Kind.PATCH_JOURNAL,
                    "step-1", "checkpoint/step-1", RecoveryEvidenceRef.State.PREPARED);
            store.upsertRecoveryEvidence(prepared);
            RecoveryEvidenceRef failed = store.upsertRecoveryEvidence(prepared.withState(
                    RecoveryEvidenceRef.State.FAILED));
            assertEquals(2L, failed.version());

            RecoveryEvidenceRef completed = store.upsertRecoveryEvidence(failed.withState(
                    RecoveryEvidenceRef.State.COMPLETED));
            assertEquals(3L, completed.version());
            assertEquals(RecoveryEvidenceRef.State.COMPLETED, completed.state());

            assertThrows(IllegalStateException.class, () -> store.upsertRecoveryEvidence(
                    completed.withState(RecoveryEvidenceRef.State.PRESENT)));
        }

        try (SqliteRunStore store = new SqliteRunStore(db)) {
            RecoveryEvidenceRef prepared = ref("run-reconcile-rollback",
                    RecoveryEvidenceRef.Kind.PATCH_JOURNAL, "step-1", "checkpoint/step-1",
                    RecoveryEvidenceRef.State.PREPARED);
            store.upsertRecoveryEvidence(prepared);
            RecoveryEvidenceRef failed = store.upsertRecoveryEvidence(prepared.withState(
                    RecoveryEvidenceRef.State.FAILED));
            RecoveryEvidenceRef rolledBack = store.upsertRecoveryEvidence(failed.withState(
                    RecoveryEvidenceRef.State.ROLLED_BACK));
            assertEquals(3L, rolledBack.version());
            assertEquals(RecoveryEvidenceRef.State.ROLLED_BACK, rolledBack.state());
            assertThrows(IllegalStateException.class, () -> store.upsertRecoveryEvidence(
                    rolledBack.withState(RecoveryEvidenceRef.State.PREPARED)));
        }
    }

    private static RecoveryEvidenceRef ref(String runId, RecoveryEvidenceRef.Kind kind,
                                           String logicalKey, String normalizedRef,
                                           RecoveryEvidenceRef.State state) {
        return new RecoveryEvidenceRef(runId, "thread-1", "main", kind, logicalKey,
                normalizedRef, "", state, null, null, 0);
    }
}
