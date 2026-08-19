package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import com.devcli.tool.ToolRegistry;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCommitRecoveryEvidenceTest {

    @Test
    void incompleteRollbackKeepsJournalForRecovery() {
        PatchSet.ApplyResult incompleteRollback = new PatchSet.ApplyResult(
                false, List.of(), List.of(), "apply failed", List.of("a.txt"));
        PatchSet.ApplyResult completeRollback = new PatchSet.ApplyResult(
                false, List.of(), List.of(), "apply failed", List.of());

        assertTrue(!WorkspaceCommitCoordinator.shouldFinalizeJournal(incompleteRollback));
        assertTrue(WorkspaceCommitCoordinator.shouldFinalizeJournal(completeRollback));
        assertTrue(WorkspaceCommitCoordinator.shouldFinalizeJournal(
                new PatchSet.ApplyResult(true, List.of(), List.of("a.txt"), "", List.of())));
    }

    @Test
    void coordinatorRecordsPreparedAndCompletedForAppliedPatch(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "before");
        Path checkpointDir = tempDir.resolve("checkpoints");
        String previous = System.getProperty("devcli.checkpoint.dir");
        System.setProperty("devcli.checkpoint.dir", checkpointDir.toString());
        List<RecoveryEvidenceRef> captured = new ArrayList<>();

        try (RunContext ignored = CancellationContext.startRunContext(
                project, "run-patch-success", "thread-1", "main", captured::add);
             ToolRegistry parent = projectRegistry(project);
             WorkspaceExecutionSession session = WorkspaceExecutionSession.open(parent, "step-1")) {
            AgentCheckpoint checkpoint = new AgentCheckpoint("orch-patch-success", "goal");
            PatchSet patchSet = patch("a.txt", "before", "after");

            PatchSet.ApplyResult result = new WorkspaceCommitCoordinator().commit(
                    session, patchSet, checkpoint, "step-1", project,
                    ExecutionArtifact.pending("step-1"), ignoredResult -> { });

            assertTrue(result.applied());
            assertEquals("after", Files.readString(project.resolve("a.txt")));
            assertEquals(List.of(RecoveryEvidenceRef.State.PREPARED,
                            RecoveryEvidenceRef.State.COMPLETED),
                    patchStates(captured));
        } finally {
            restore("devcli.checkpoint.dir", previous);
        }
    }

    @Test
    void coordinatorRecordsFailedConflictAndClassifiesRollbackStates(@TempDir Path tempDir)
            throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path checkpointDir = tempDir.resolve("checkpoints");
        String previous = System.getProperty("devcli.checkpoint.dir");
        System.setProperty("devcli.checkpoint.dir", checkpointDir.toString());
        List<RecoveryEvidenceRef> captured = new ArrayList<>();

        try (RunContext ignored = CancellationContext.startRunContext(
                project, "run-patch-failures", "thread-1", "main", captured::add);
             ToolRegistry parent = projectRegistry(project);
             WorkspaceExecutionSession session = WorkspaceExecutionSession.open(parent, "step-1")) {
            Path blocked = project.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            AgentCheckpoint rollbackCheckpoint = new AgentCheckpoint("orch-patch-rollback", "goal");
            PatchSet rollbackPatch = patch("blocked/child.txt", "<missing>", "child");
            PatchSet.ApplyResult rollback = new WorkspaceCommitCoordinator().commit(
                    session, rollbackPatch, rollbackCheckpoint, "step-rollback", project,
                    ExecutionArtifact.pending("step-rollback"), ignoredResult -> { });

            assertTrue(!rollback.applied());
            assertEquals(RecoveryEvidenceRef.State.FAILED,
                    patchStates(captured).get(patchStates(captured).size() - 1));
            assertEquals("not a directory", Files.readString(blocked));
            assertEquals(RecoveryEvidenceRef.State.ROLLED_BACK,
                    WorkspaceCommitCoordinator.evidenceState(new PatchSet.ApplyResult(
                            false, List.of(), List.of(), "apply failed", List.of())));
            assertEquals(RecoveryEvidenceRef.State.FAILED,
                    WorkspaceCommitCoordinator.evidenceState(new PatchSet.ApplyResult(
                            false, List.of(), List.of(), "apply failed", List.of("blocked"))));
            assertEquals(RecoveryEvidenceRef.State.FAILED,
                    WorkspaceCommitCoordinator.evidenceState(new PatchSet.ApplyResult(
                            false, List.of("conflict.txt"), List.of(), "patch conflict", List.of())));
        } finally {
            restore("devcli.checkpoint.dir", previous);
        }
    }

    private static ToolRegistry projectRegistry(Path project) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        return registry;
    }

    private static PatchSet patch(String path, String before, String after) {
        byte[] beforeBytes = before.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] afterBytes = after.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new PatchSet(List.of(new PatchSet.FileChange(
                path, PatchSet.ChangeType.MODIFY,
                PatchSet.isMissingHash(before) ? before : PatchSet.hash(beforeBytes),
                PatchSet.isMissingHash(after) ? after : PatchSet.hash(afterBytes),
                afterBytes)));
    }

    private static List<RecoveryEvidenceRef.State> patchStates(List<RecoveryEvidenceRef> refs) {
        return refs.stream()
                .filter(ref -> ref.kind() == RecoveryEvidenceRef.Kind.PATCH_JOURNAL)
                .map(RecoveryEvidenceRef::state)
                .toList();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
