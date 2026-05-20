package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceLeaseManagerTest {

    @Test
    void shouldRejectWriteLeaseOwnedByAnotherStep(@TempDir Path tempDir) {
        ResourceLeaseManager manager = new ResourceLeaseManager();
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step_a", file);

        assertThrows(ResourceLeaseException.class, () -> manager.acquireWrite("step_b", file));
    }

    @Test
    void shouldReleaseStepLeases(@TempDir Path tempDir) {
        ResourceLeaseManager manager = new ResourceLeaseManager();
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step_a", file);
        manager.releaseStep("step_a");

        assertDoesNotThrow(() -> manager.acquireWrite("step_b", file));
    }

    @Test
    void shouldClearAllLeases(@TempDir Path tempDir) {
        ResourceLeaseManager manager = new ResourceLeaseManager();
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step_a", file);
        manager.clear();

        assertDoesNotThrow(() -> manager.acquireWrite("step_b", file));
    }
}
