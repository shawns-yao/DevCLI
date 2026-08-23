package com.devcli.workspace;

import com.devcli.tool.ToolRegistry;
import com.devcli.rag.CodeChunk;
import com.devcli.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceExecutionSessionTest {

    @Test
    void serializesCommitAcrossProcesses(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path childMarker = tempDir.resolve("child-acquired.txt");
        CountDownLatch parentLocked = new CountDownLatch(1);
        CountDownLatch releaseParent = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Process child = null;
        try {
            var parent = executor.submit(() -> ProjectCommitCoordinator.withProjectLock(project, () -> {
                parentLocked.countDown();
                releaseParent.await(5, TimeUnit.SECONDS);
                return null;
            }));
            assertTrue(parentLocked.await(5, TimeUnit.SECONDS));

            String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "java.exe" : "java").toString();
            String classpath = Path.of("target", "test-classes").toAbsolutePath()
                    + File.pathSeparator
                    + Path.of("target", "classes").toAbsolutePath();
            child = new ProcessBuilder(
                    javaExecutable,
                    "-cp", classpath,
                    ProjectCommitLockProcess.class.getName(),
                    project.toString(),
                    childMarker.toString())
                    .redirectErrorStream(true)
                    .start();

            Thread.sleep(300L);
            assertFalse(Files.exists(childMarker),
                    "另一个进程不能在当前进程持锁时进入提交临界区");

            releaseParent.countDown();
            parent.get(5, TimeUnit.SECONDS);
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes()));
            assertTrue(Files.exists(childMarker));
        } finally {
            releaseParent.countDown();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }

            executor.shutdownNow();
        }
    }

    @Test
    void releasesProjectLockCacheAfterLastUser(@TempDir Path tempDir) throws Exception {
        for (int index = 0; index < 50; index++) {
            Path project = Files.createDirectories(tempDir.resolve("project-" + index));
            ProjectCommitCoordinator.withProjectLock(project, () -> null);
        }

        assertEquals(0, ProjectCommitCoordinator.cachedLockCount());
    }


    @Test
    void serializesPatchCommitAndDecisionForSameProject(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("shared.txt"), "base");
        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(project.toString());

        try (WorkspaceExecutionSession first = WorkspaceExecutionSession.open(parent, "first");
             WorkspaceExecutionSession second = WorkspaceExecutionSession.open(parent, "second")) {
            assertTrue(first.toolRegistry().executeToolOutput(
                    "write_file", "{\"path\":\"shared.txt\",\"content\":\"first\"}").isSuccess());
            assertTrue(second.toolRegistry().executeToolOutput(
                    "write_file", "{\"path\":\"shared.txt\",\"content\":\"second\"}").isSuccess());
            assertEquals("base", Files.readString(project.resolve("shared.txt")));
            PatchSet firstPatch = first.patchSet();
            PatchSet secondPatch = second.patchSet();
            CountDownLatch firstDecisionEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstDecision = new CountDownLatch(1);

            var executor = Executors.newFixedThreadPool(2);
            try {
                var firstFuture = executor.submit(() -> first.commit(firstPatch, result -> {
                    firstDecisionEntered.countDown();
                    try {
                        releaseFirstDecision.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
                assertTrue(firstDecisionEntered.await(5, TimeUnit.SECONDS));

                var secondFuture = executor.submit(() -> second.commit(secondPatch, result -> { }));
                assertThrows(TimeoutException.class,
                        () -> secondFuture.get(200, TimeUnit.MILLISECONDS),
                        "同项目第二个提交必须等待第一个提交连同终态回调完成");

                releaseFirstDecision.countDown();
                PatchSet.ApplyResult firstResult = firstFuture.get(5, TimeUnit.SECONDS);
                PatchSet.ApplyResult secondResult = secondFuture.get(5, TimeUnit.SECONDS);

                assertTrue(firstResult.applied());
                assertFalse(secondResult.applied());
                assertTrue(secondResult.conflicts().contains("shared.txt"));
            } finally {
                releaseFirstDecision.countDown();
                executor.shutdownNow();
            }
        } finally {
            parent.close();
        }
    }

    @Test
    void successfulPatchCommitMarksParentIndexDirty(@TempDir Path tempDir) throws Exception {
        String oldRagDir = System.getProperty("devcli.rag.dir");
        System.setProperty("devcli.rag.dir", tempDir.resolve("rag").toString());
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("README.md"), "indexed content");
        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(project.toString());
        try {
            try (VectorStore store = new VectorStore(project.toString())) {
                store.clearProject();
                store.replaceProjectIndex(List.of(new VectorStore.CodeChunkEntry(
                        CodeChunk.fileChunk("README.md", "indexed content"),
                        new float[]{1.0f})), List.of(), "idx-1");
            }
            try (WorkspaceExecutionSession session = WorkspaceExecutionSession.open(parent, "worker")) {
                assertTrue(session.toolRegistry().executeToolOutput("write_file",
                        "{\"path\":\"README.md\",\"content\":\"changed content\"}")
                        .isSuccess());
                assertTrue(session.apply(session.patchSet()).applied());
            }
            try (VectorStore store = new VectorStore(project.toString())) {
                assertEquals(VectorStore.IndexFreshness.DIRTY,
                        store.searchByKeyword("indexed content").getFirst().freshness());
            }
        } finally {
            parent.close();
            if (oldRagDir == null) System.clearProperty("devcli.rag.dir");
            else System.setProperty("devcli.rag.dir", oldRagDir);
        }
    }
}
