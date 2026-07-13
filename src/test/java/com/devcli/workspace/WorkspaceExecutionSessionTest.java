package com.devcli.workspace;

import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceExecutionSessionTest {

    @Test
    void serializesPatchCommitAndDecisionForSameProject(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("shared.txt"), "base");
        ToolRegistry parent = new ToolRegistry();
        parent.setProjectPath(project.toString());

        try (WorkspaceExecutionSession first = WorkspaceExecutionSession.open(parent, "first");
             WorkspaceExecutionSession second = WorkspaceExecutionSession.open(parent, "second")) {
            Files.writeString(first.workspacePath().resolve("shared.txt"), "first");
            Files.writeString(second.workspacePath().resolve("shared.txt"), "second");
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
}
