package com.devcli.snapshot;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }

    @Test
    void prunesOlderSnapshotsWhenRetentionLimitIsExceeded() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 3, List.of(".git", "target")));
        for (int i = 1; i <= 3; i++) {
            Files.writeString(project.resolve("a.txt"), "turn-" + i + "-before");
            manager.preTurnSnapshot("turn-" + i, "before " + i);
            Files.writeString(project.resolve("a.txt"), "turn-" + i + "-after");
            manager.postTurnSnapshot("turn-" + i, "after " + i);
        }

        List<TurnSnapshot> all = manager.listSnapshots(10);

        assertEquals(3, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals("turn-3", all.get(0).turnId());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
        assertEquals("turn-3", all.get(1).turnId());
        assertEquals(SnapshotPhase.POST_TURN, all.get(2).phase());
        assertEquals("turn-2", all.get(2).turnId());
    }

    @Test
    void collectsUnreachableObjectsOnlyAfterConfiguredThreshold() throws Exception {
        Path project = tempDir.resolve("gc-project");
        Path snapshots = tempDir.resolve("gc-snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 2,
                List.of(".git", "target"), true, 2, 1000, 30);
        SideGitManager manager = new SideGitManager(project, config);

        Files.writeString(project.resolve("data.txt"), "one");
        TurnSnapshot oldest = manager.preTurnSnapshot("turn-1", "one");
        Files.writeString(project.resolve("data.txt"), "two");
        manager.postTurnSnapshot("turn-1", "two");
        Files.writeString(project.resolve("data.txt"), "three");
        manager.preTurnSnapshot("turn-2", "three");

        assertEquals(0, manager.gcRunCount());

        Files.writeString(project.resolve("data.txt"), "four");
        manager.postTurnSnapshot("turn-2", "four");

        assertEquals(1, manager.gcRunCount(), manager.lastGcError());
        try (var repository = new FileRepositoryBuilder()
                .setGitDir(manager.gitDir().toFile())
                .setWorkTree(project.toFile())
                .build()) {
            assertFalse(repository.getObjectDatabase().has(ObjectId.fromString(oldest.commitId())));
        }

        Files.writeString(project.resolve("data.txt"), "five");
        manager.preTurnSnapshot("turn-3", "five");
        assertEquals(1, manager.gcRunCount(), "GC 不能在每次快照时执行");
    }

    @Test
    void objectCollectionStopsWhenReachabilityScanExceedsDeadline() throws Exception {
        Path project = tempDir.resolve("gc-timeout-project");
        Path snapshots = tempDir.resolve("gc-timeout-snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("data.txt"), "content");
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 2, List.of(".git")));
        manager.preTurnSnapshot("turn-1", "snapshot");

        IOException failure = assertThrows(IOException.class,
                () -> new SideGitObjectGc().collect(manager.gitDir(), Duration.ZERO));

        assertTrue(failure.getMessage().contains("超时"));
    }
}
