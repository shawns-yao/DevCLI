package com.devcli.snapshot;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotRecoveryEvidenceTest {

    @Test
    void recordsRealPreAndPostSnapshotReferences(@TempDir Path tempDir) throws Exception {
        FakeSideGitManager manager = new FakeSideGitManager(tempDir, true);
        SnapshotService service = new SnapshotService(manager);
        List<RecoveryEvidenceRef> captured = new ArrayList<>();

        try (RunContext ignored = CancellationContext.startRunContext(
                tempDir, "run-snapshot", "thread-1", "branch-1", captured::add)) {
            assertEquals("ok", service.runTurn("react", "write file", () -> "ok"));
            service.awaitIdle();
        } finally {
            service.close();
        }

        assertEquals(List.of("pre-commit", "post-commit"),
                captured.stream().map(RecoveryEvidenceRef::normalizedReference).toList());
        assertTrue(captured.get(0).logicalKey().endsWith(":pre"));
        assertTrue(captured.get(1).logicalKey().endsWith(":post"));
        assertEquals(captured.get(0).logicalKey().replace(":pre", ""),
                captured.get(1).logicalKey().replace(":post", ""));
        assertTrue(captured.stream().allMatch(ref ->
                ref.kind() == RecoveryEvidenceRef.Kind.SIDE_GIT
                        && ref.state() == RecoveryEvidenceRef.State.COMPLETED
                        && ref.runId().equals("run-snapshot")));
    }

    @Test
    void evidenceFailureDoesNotBreakTurnOrSnapshot(@TempDir Path tempDir) throws Exception {
        FakeSideGitManager manager = new FakeSideGitManager(tempDir, true);
        SnapshotService service = new SnapshotService(manager);
        try (RunContext ignored = CancellationContext.startRunContext(
                tempDir, "run-snapshot-failure", "thread-1", "main", ref -> {
                    throw new IllegalStateException("store unavailable");
                })) {
            assertEquals("answer", service.runTurn("react", "input", () -> "answer"));
            service.awaitIdle();
        } finally {
            service.close();
        }
        assertEquals(1, manager.preCalls);
        assertEquals(1, manager.postCalls);
    }

    @Test
    void managerSnapshotFailureDoesNotRegisterSuccess(@TempDir Path tempDir) throws Exception {
        FakeSideGitManager manager = new FakeSideGitManager(tempDir, true);
        manager.failPre = true;
        manager.failPost = true;
        SnapshotService service = new SnapshotService(manager);
        List<RecoveryEvidenceRef> captured = new ArrayList<>();
        try (RunContext ignored = CancellationContext.startRunContext(
                tempDir, "run-snapshot-manager-failure", "thread-1", "main", captured::add)) {
            assertEquals("answer", service.runTurn("react", "input", () -> "answer"));
            service.awaitIdle();
        } finally {
            service.close();
        }
        assertTrue(captured.isEmpty());
        assertEquals(1, manager.preCalls);
        assertEquals(1, manager.postCalls);
    }

    @Test
    void disabledSnapshotsDoNotRegisterEvidence(@TempDir Path tempDir) throws Exception {
        FakeSideGitManager manager = new FakeSideGitManager(tempDir, false);
        SnapshotService service = new SnapshotService(manager);
        List<RecoveryEvidenceRef> captured = new ArrayList<>();
        try (RunContext ignored = CancellationContext.startRunContext(
                tempDir, "run-snapshot-disabled", "thread-1", "main", captured::add)) {
            assertEquals("answer", service.runTurn("react", "input", () -> "answer"));
            service.awaitIdle();
        } finally {
            service.close();
        }
        assertTrue(captured.isEmpty());
        assertEquals(0, manager.preCalls);
        assertEquals(0, manager.postCalls);
    }

    private static final class FakeSideGitManager extends SideGitManager {
        private final boolean enabled;
        private boolean failPre;
        private boolean failPost;
        private int preCalls;
        private int postCalls;

        private FakeSideGitManager(Path project, boolean enabled) {
            super(project, new SnapshotConfig(enabled, project.resolve("snapshots"), 10, List.of()));
            this.enabled = enabled;
        }

        @Override
        public SnapshotConfig config() {
            return super.config().withEnabled(enabled);
        }

        @Override
        public TurnSnapshot preTurnSnapshot(String turnId, String summary) throws IOException {
            preCalls++;
            if (failPre) {
                throw new IOException("pre failed");
            }
            return snapshot("pre-commit", turnId, SnapshotPhase.PRE_TURN);
        }

        @Override
        public TurnSnapshot postTurnSnapshot(String turnId, String summary) throws IOException {
            postCalls++;
            if (failPost) {
                throw new IOException("post failed");
            }
            return snapshot("post-commit", turnId, SnapshotPhase.POST_TURN);
        }

        private static TurnSnapshot snapshot(String commitId, String turnId, SnapshotPhase phase) {
            return new TurnSnapshot(commitId, phase, turnId, Instant.now(), phase.label());
        }
    }
}
