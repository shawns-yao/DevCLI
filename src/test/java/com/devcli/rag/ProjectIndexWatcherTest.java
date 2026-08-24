package com.devcli.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexWatcherTest {
    @TempDir
    Path project;

    @Test
    void deletedDirectoryPublishesAllKnownIndexedChildren() throws Exception {
        Path sourceDir = Files.createDirectories(project.resolve("src/generated"));
        Path first = Files.writeString(sourceDir.resolve("First.java"), "class First {}");
        Path second = Files.writeString(sourceDir.resolve("Second.java"), "class Second {}");
        VectorStore.IndexWatchSnapshot snapshot = new VectorStore.IndexWatchSnapshot(
                Set.of(relative(first), relative(second)), System.currentTimeMillis());

        try (ProjectIndexWatcher watcher = new ProjectIndexWatcher(project, snapshot)) {
            watcher.markDeletedPath(sourceDir);

            List<String> changes = awaitChanges(watcher, 2);

            assertTrue(changes.contains("src/generated/First.java"));
            assertTrue(changes.contains("src/generated/Second.java"));
        }
    }

    @Test
    void startupReconciliationPublishesIndexedFileDeletedWhileWatcherWasStopped() throws Exception {
        VectorStore.IndexWatchSnapshot snapshot = new VectorStore.IndexWatchSnapshot(
                Set.of("src/Deleted.java"), System.currentTimeMillis());

        try (ProjectIndexWatcher watcher = new ProjectIndexWatcher(project, snapshot)) {
            assertTrue(watcher.drainChanges().contains("src/Deleted.java"));
        }
    }

    private List<String> awaitChanges(ProjectIndexWatcher watcher, int expectedCount) throws Exception {
        java.util.LinkedHashSet<String> changes = new java.util.LinkedHashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (changes.size() < expectedCount && System.nanoTime() < deadline) {
            changes.addAll(watcher.drainChanges());
            if (changes.size() < expectedCount) Thread.sleep(25);
        }
        return List.copyOf(changes);
    }

    private String relative(Path file) {
        return project.relativize(file).toString().replace('\\', '/');
    }
}
