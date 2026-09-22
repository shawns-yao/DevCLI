package com.devcli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class SkillStateStoreTest {

    @Test
    void returnsEmptyDisabledWhenFileMissing(@TempDir Path tempDir) {
        SkillStateStore store = new SkillStateStore(tempDir.resolve("skills.json"));
        assertTrue(store.disabled().isEmpty());
    }

    @Test
    void persistsDisabledList(@TempDir Path tempDir) {
        Path file = tempDir.resolve("skills.json");
        SkillStateStore store = new SkillStateStore(file);

        store.disable("web-access");
        store.disable("verbose-debug");

        SkillStateStore reload = new SkillStateStore(file);
        Set<String> disabled = reload.disabled();
        assertEquals(2, disabled.size());
        assertTrue(disabled.contains("web-access"));
        assertTrue(disabled.contains("verbose-debug"));
    }

    @Test
    void enableRemovesFromDisabled(@TempDir Path tempDir) {
        Path file = tempDir.resolve("skills.json");
        SkillStateStore store = new SkillStateStore(file);
        store.disable("a");
        store.disable("b");
        store.enable("a");

        Set<String> disabled = new SkillStateStore(file).disabled();
        assertEquals(Set.of("b"), disabled);
    }

    @Test
    void treatsCorruptFileAsEmpty(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("skills.json");
        Files.writeString(file, "not json {{");
        SkillStateStore store = new SkillStateStore(file);
        assertTrue(store.disabled().isEmpty());
    }

    @Test
    void persistsProjectDirectoryTrustByCanonicalFingerprint(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("skills.json");
        Path projectSkills = tempDir.resolve("repo/.devcli/skills");
        Files.createDirectories(projectSkills);
        SkillStateStore store = new SkillStateStore(file);

        assertFalse(store.isProjectDirectoryTrusted(projectSkills));
        store.trustProjectDirectory(projectSkills.resolve("..").resolve("skills"));

        SkillStateStore reopened = new SkillStateStore(file);
        assertTrue(reopened.isProjectDirectoryTrusted(projectSkills));
        reopened.untrustProjectDirectory(projectSkills);
        assertFalse(new SkillStateStore(file).isProjectDirectoryTrusted(projectSkills));
    }

    @Test
    void concurrentStoreInstancesDoNotLoseUpdates(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("skills.json");
        SkillStateStore first = new SkillStateStore(file);
        SkillStateStore second = new SkillStateStore(file);
        CountDownLatch start = new CountDownLatch(1);
        Thread a = new Thread(() -> awaitAndDisable(start, first, "alpha"));
        Thread b = new Thread(() -> awaitAndDisable(start, second, "beta"));

        a.start();
        b.start();
        start.countDown();
        a.join();
        b.join();

        assertEquals(Set.of("alpha", "beta"), new SkillStateStore(file).disabled());
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp")));
        }
    }

    private static void awaitAndDisable(CountDownLatch start, SkillStateStore store, String name) {
        try {
            start.await();
            store.disable(name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
