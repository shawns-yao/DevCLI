package com.devcli.workspace;

import com.devcli.agent.AgentCheckpoint;
import com.devcli.plan.ExecutionArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PatchSetFileModeTest {

    @Test
    void encodesPosixModesDeterministically() {
        assertEquals(0644, FileModeSnapshot.fromPosix(mode0644()).posixMode());
        assertFalse(FileModeSnapshot.fromPosix(mode0644()).executable());
        assertEquals(0750, FileModeSnapshot.fromPosix(mode0750()).posixMode());
        assertTrue(FileModeSnapshot.fromPosix(mode0750()).executable());
    }

    @Test
    void modeOnlyWorkspaceChangeProducesPatch(@TempDir Path tempDir) throws Exception {
        requirePosix(tempDir);
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path base = Files.createDirectories(tempDir.resolve("workspaces"));
        Path source = project.resolve("run.sh");
        Files.writeString(source, "#!/bin/sh\n");
        Files.setPosixFilePermissions(source, mode0644());

        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(project, base, "mode-only")) {
            Path isolated = workspace.path().resolve("run.sh");
            Files.setPosixFilePermissions(isolated, mode0750());

            PatchSet patchSet = workspace.createPatchSet();

            assertEquals(1, patchSet.changes().size());
            PatchSet.FileChange change = patchSet.changes().getFirst();
            assertEquals(change.beforeHash(), change.afterHash());
            assertEquals(FileModeSnapshot.fromPosix(mode0644()), change.beforeMode());
            assertEquals(FileModeSnapshot.fromPosix(mode0750()), change.afterMode());
        }
    }

    @Test
    void applyPreservesExactPosixPermissions(@TempDir Path tempDir) throws Exception {
        requirePosix(tempDir);
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path target = project.resolve("run.sh");
        byte[] before = "before".getBytes(StandardCharsets.UTF_8);
        byte[] after = "after".getBytes(StandardCharsets.UTF_8);
        Files.write(target, before);
        Files.setPosixFilePermissions(target, mode0640());
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "run.sh", PatchSet.ChangeType.MODIFY,
                PatchSet.hash(before), PatchSet.hash(after), after,
                FileModeSnapshot.fromPosix(mode0640()), FileModeSnapshot.fromPosix(mode0750()))));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertTrue(result.applied(), result.failureDescription());
        assertEquals(mode0750(), Files.getPosixFilePermissions(target));
    }

    @Test
    void modeConflictRejectsPatchEvenWhenContentHashStillMatches(@TempDir Path tempDir) throws Exception {
        requirePosix(tempDir);
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path target = project.resolve("run.sh");
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);
        Files.write(target, content);
        Files.setPosixFilePermissions(target, mode0600());
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "run.sh", PatchSet.ChangeType.MODIFY,
                PatchSet.hash(content), PatchSet.hash(content), content,
                FileModeSnapshot.fromPosix(mode0644()), FileModeSnapshot.fromPosix(mode0750()))));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertFalse(result.applied());
        assertEquals(List.of("run.sh"), result.conflicts());
        assertEquals(mode0600(), Files.getPosixFilePermissions(target));
    }

    @Test
    void failedBatchRestoresOriginalPosixPermissions(@TempDir Path tempDir) throws Exception {
        requirePosix(tempDir);
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path target = project.resolve("a.txt");
        byte[] before = "before".getBytes(StandardCharsets.UTF_8);
        byte[] after = "after".getBytes(StandardCharsets.UTF_8);
        Files.write(target, before);
        Files.setPosixFilePermissions(target, mode0640());
        byte[] parent = "parent".getBytes(StandardCharsets.UTF_8);
        byte[] child = "child".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(
                new PatchSet.FileChange("a.txt", PatchSet.ChangeType.MODIFY,
                        PatchSet.hash(before), PatchSet.hash(after), after,
                        FileModeSnapshot.fromPosix(mode0640()), FileModeSnapshot.fromPosix(mode0750())),
                new PatchSet.FileChange("b", PatchSet.ChangeType.ADD,
                        PatchSet.MISSING_HASH, PatchSet.hash(parent), parent),
                new PatchSet.FileChange("b/child.txt", PatchSet.ChangeType.ADD,
                        PatchSet.MISSING_HASH, PatchSet.hash(child), child)
        ));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertFalse(result.applied());
        assertTrue(result.rollbackComplete(), result.rollbackFailures().toString());
        assertEquals("before", Files.readString(target));
        assertEquals(mode0640(), Files.getPosixFilePermissions(target));
    }

    @Test
    void checkpointDoesNotPromoteUnappliedModeOnlyPatch(@TempDir Path project) throws Exception {
        requirePosix(project);
        Path target = project.resolve("run.sh");
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);
        Files.write(target, content);
        Files.setPosixFilePermissions(target, mode0644());
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "run.sh", PatchSet.ChangeType.MODIFY,
                PatchSet.hash(content), PatchSet.hash(content), content,
                FileModeSnapshot.fromPosix(mode0644()), FileModeSnapshot.fromPosix(mode0750()))));
        AgentCheckpoint checkpoint = new AgentCheckpoint("mode-only-unapplied", "goal");
        checkpoint.preparePatchCommit("step-1", project, patchSet,
                ExecutionArtifact.pending("step-1"));

        AgentCheckpoint.PatchReconcileResult result = checkpoint.reconcilePendingPatchCommits(project);

        assertEquals(AgentCheckpoint.PatchReconcileAction.CONTINUE_PENDING,
                result.actions().get("step-1"));
        assertFalse(checkpoint.recoveryState().artifacts().get("step-1").successful());
    }

    private static void requirePosix(Path path) throws Exception {
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView("posix"),
                "当前文件系统不支持 POSIX 权限");
    }

    private static Set<PosixFilePermission> mode0600() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static Set<PosixFilePermission> mode0640() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
    }

    private static Set<PosixFilePermission> mode0644() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
    }

    private static Set<PosixFilePermission> mode0750() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE);
    }
}
