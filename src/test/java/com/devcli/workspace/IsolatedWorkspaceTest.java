package com.devcli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedWorkspaceTest {

    @Test
    void buildsUnchangedLargeWorkspaceWithinBoundedHeap(@TempDir Path tempDir) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath()
                + File.pathSeparator
                + Path.of("target", "classes").toAbsolutePath();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-Xmx32m",
                "-cp", classpath,
                PatchSetMemoryProcess.class.getName(),
                tempDir.toString())
                .redirectErrorStream(true)
                .start();

        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes()));
    }

    @Test
    void usesBoundedParallelCopyBackend(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project);
        for (int i = 0; i < 24; i++) {
            Files.writeString(project.resolve("file-" + i + ".txt"), "content-" + i);
        }

        TrackingCopyWorkspaceBackend backend = new TrackingCopyWorkspaceBackend(2);
        WorkspaceCleanupPolicy cleanupPolicy = new WorkspaceCleanupPolicy(Duration.ofHours(1));
        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(
                project, workspaceBase, "parallel-copy", backend, cleanupPolicy)) {
            try (var files = Files.list(workspace.path())) {
                assertEquals(24L, files.count());
            }
            assertTrue(workspace.createPatchSet().changes().isEmpty(),
                    "并行复制记录的基线哈希必须对应实际工作区内容");
        }

        assertTrue(backend.maxConcurrency() > 1, "复制实现应实际并行执行");
        assertTrue(backend.maxConcurrency() <= 2, "并行复制不得超过配置上限");
    }

    @Test
    void removesExpiredOrphanBeforeCreatingWorkspace(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Path orphan = workspaceBase.resolve("orphan-workspace");
        Path fresh = workspaceBase.resolve("fresh-workspace");
        Files.createDirectories(project);
        Files.createDirectories(orphan);
        Files.createDirectories(fresh);
        Files.writeString(orphan.resolve("stale.txt"), "stale");
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(Duration.ofHours(3))));

        WorkspaceCleanupPolicy cleanupPolicy = new WorkspaceCleanupPolicy(Duration.ofHours(1));
        try (IsolatedWorkspace ignored = IsolatedWorkspace.create(
                project, workspaceBase, "cleanup", new CopyWorkspaceBackend(2), cleanupPolicy)) {
            assertFalse(Files.exists(orphan));
            assertTrue(Files.exists(fresh), "未超过 TTL 的工作区不得被清理");
        }
    }

    @Test
    void keepsExpiredWorkspaceWhileItsLeaseIsActive(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project);
        WorkspaceCleanupPolicy cleanupPolicy = new WorkspaceCleanupPolicy(Duration.ofMillis(1));

        try (IsolatedWorkspace active = IsolatedWorkspace.create(
                project, workspaceBase, "active", new CopyWorkspaceBackend(2), cleanupPolicy)) {
            Files.setLastModifiedTime(active.path(), FileTime.from(Instant.now().minus(Duration.ofHours(3))));
            try (IsolatedWorkspace ignored = IsolatedWorkspace.create(
                    project, workspaceBase, "next", new CopyWorkspaceBackend(2), cleanupPolicy)) {
                assertTrue(Files.exists(active.path()), "活动工作区不得被孤儿清理删除");
            }
        }
    }

    @Test
    void copyBackendPreservesExclusionsAndSkipsSymbolicLinks(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project.resolve(".git"));
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("visible.txt"), "visible");
        Files.writeString(project.resolve(".git").resolve("config"), "secret");
        Files.writeString(project.resolve("target").resolve("artifact.jar"), "artifact");

        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path link = project.resolve("outside-link.txt");
        boolean linkCreated;
        try {
            Files.createSymbolicLink(link, outside);
            linkCreated = true;
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            linkCreated = false;
        }

        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(project, workspaceBase, "safe-copy")) {
            assertTrue(Files.exists(workspace.path().resolve("visible.txt")));
            assertFalse(Files.exists(workspace.path().resolve(".git")));
            assertFalse(Files.exists(workspace.path().resolve("target")));
            if (linkCreated) {
                assertFalse(Files.exists(workspace.path().resolve("outside-link.txt")));
            }
        }
    }

    @Test
    void waitsForCopyWorkersToStopAfterMaterializationFailure(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Path workspace = workspaceBase.resolve("workspace");
        Files.createDirectories(project);
        Files.createDirectories(workspace);
        Files.writeString(project.resolve("fail.txt"), "fail");
        Files.writeString(project.resolve("slow.txt"), "slow");

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch failureThrown = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CopyWorkspaceBackend backend = new CopyWorkspaceBackend(2) {
            @Override
            FileSnapshot copyFile(Path root, Path workspacePath, Path source) throws Exception {
                if (source.getFileName().toString().equals("slow.txt")) {
                    slowStarted.countDown();
                    while (true) {
                        try {
                            if (releaseSlow.await(3, TimeUnit.SECONDS)) {
                                return null;
                            }
                        } catch (InterruptedException ignored) {
                            // 模拟不能立即响应中断的文件系统操作。
                        }
                    }
                }
                assertTrue(slowStarted.await(3, TimeUnit.SECONDS));
                failureThrown.countDown();
                throw new java.io.IOException("copy failed");
            }
        };
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> result = caller.submit(() -> {
                try {
                    backend.materialize(project, workspaceBase, workspace);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(failureThrown.await(3, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertFalse(result.isDone(), "复制失败返回前必须等待其他写入任务停止");

            releaseSlow.countDown();
            assertInstanceOf(java.io.IOException.class, result.get(3, TimeUnit.SECONDS));
        } finally {
            releaseSlow.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void resolvesCleanupTtlFromPropertyBeforeEnvironment() {
        assertEquals(Duration.ofHours(2), WorkspaceCleanupPolicy.resolveTtl(
                Map.of(WorkspaceCleanupPolicy.TTL_PROPERTY, "2"),
                Map.of(WorkspaceCleanupPolicy.TTL_ENV, "3")));
        assertEquals(Duration.ofHours(3), WorkspaceCleanupPolicy.resolveTtl(
                Map.of(), Map.of(WorkspaceCleanupPolicy.TTL_ENV, "3")));
    }

    @Test
    void buildsAndAppliesStructuredPatchSet(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project);
        Files.writeString(project.resolve("modify.txt"), "before");
        Files.writeString(project.resolve("delete.txt"), "delete");

        PatchSet patchSet;
        Path workspacePath;
        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(project, workspaceBase, "step-1")) {
            workspacePath = workspace.path();
            Files.writeString(workspace.path().resolve("modify.txt"), "after");
            Files.delete(workspace.path().resolve("delete.txt"));
            Files.writeString(workspace.path().resolve("add.txt"), "added");
            patchSet = workspace.createPatchSet();

            assertEquals(List.of("add.txt", "delete.txt", "modify.txt"),
                    patchSet.changes().stream().map(PatchSet.FileChange::relativePath).sorted().toList());
            PatchSet.ApplyResult result = patchSet.apply(project);
            assertTrue(result.applied());
        }

        assertFalse(Files.exists(workspacePath));
        assertEquals("after", Files.readString(project.resolve("modify.txt")));
        assertFalse(Files.exists(project.resolve("delete.txt")));
        assertEquals("added", Files.readString(project.resolve("add.txt")));
    }

    @Test
    void rejectsWholePatchWhenOriginalProjectChanged(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path workspaceBase = tempDir.resolve("workspaces");
        Files.createDirectories(project);
        Files.writeString(project.resolve("shared.txt"), "base");

        try (IsolatedWorkspace workspace = IsolatedWorkspace.create(project, workspaceBase, "step-2")) {
            Files.writeString(workspace.path().resolve("shared.txt"), "worker");
            Files.writeString(workspace.path().resolve("new.txt"), "new");
            PatchSet patchSet = workspace.createPatchSet();

            Files.writeString(project.resolve("shared.txt"), "user-change");
            PatchSet.ApplyResult result = patchSet.apply(project);

            assertFalse(result.applied());
            assertEquals(List.of("shared.txt"), result.conflicts());
            assertEquals("user-change", Files.readString(project.resolve("shared.txt")));
            assertFalse(Files.exists(project.resolve("new.txt")),
                    "冲突时不得部分应用其他文件");
        }
    }

    private static final class TrackingCopyWorkspaceBackend extends CopyWorkspaceBackend {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();

        private TrackingCopyWorkspaceBackend(int parallelism) {
            super(parallelism);
        }

        @Override
        CopyWorkspaceBackend.FileSnapshot copyFile(
                Path projectRoot, Path workspacePath, Path source) throws Exception {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(15L);
                return super.copyFile(projectRoot, workspacePath, source);
            } finally {
                active.decrementAndGet();
            }
        }

        private int maxConcurrency() {
            return maximum.get();
        }
    }
}
