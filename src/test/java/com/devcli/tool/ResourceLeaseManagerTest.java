package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLeaseManagerTest {

    private ResourceLeaseManager manager;

    @BeforeEach
    void setUp() {
        manager = new ResourceLeaseManager();
    }

    @Test
    void shouldRejectWriteLeaseOwnedByAnotherStep(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step_a", file);

        assertThrows(ResourceLeaseException.class, () -> manager.acquireWrite("step_b", file));
    }

    @Test
    void shouldReleaseStepLeases(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step_a", file);
        manager.releaseStep("step_a");

        assertDoesNotThrow(() -> manager.acquireWrite("step_b", file));
    }

    @Test
    void shouldClearAllLeases(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step_a", file);
        manager.clear();

        assertDoesNotThrow(() -> manager.acquireWrite("step_b", file));
    }

    @Test
    void isLeaseValid_shouldReturnTrueForActiveLeaseHolder(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");
        String stepId = "step-1";

        manager.acquireWrite(stepId, file);

        assertTrue(manager.isLeaseValid(stepId, file), "租约持有者应该通过校验");
    }

    @Test
    void isLeaseValid_shouldReturnFalseForDifferentStep(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");

        manager.acquireWrite("step-1", file);

        assertFalse(manager.isLeaseValid("step-2", file), "其他步骤不应该通过校验");
    }

    @Test
    void isLeaseValid_shouldReturnFalseForNonExistentLease(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");

        assertFalse(manager.isLeaseValid("step-1", file), "未获取租约时应该返回 false");
    }

    @Test
    void isLeaseValid_shouldReturnFalseAfterRelease(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");
        String stepId = "step-1";

        manager.acquireWrite(stepId, file);
        manager.releaseStep(stepId);

        assertFalse(manager.isLeaseValid(stepId, file), "释放后租约应该失效");
    }

    @Test
    void isLeaseValid_shouldHandleNullInputs(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");

        assertFalse(manager.isLeaseValid(null, file), "null stepId 应该返回 false");
        assertFalse(manager.isLeaseValid("step-1", null), "null path 应该返回 false");
        assertFalse(manager.isLeaseValid("", file), "空 stepId 应该返回 false");
    }

    @Test
    void acquireWrite_shouldAllowSameStepReentry(@TempDir Path tempDir) {
        Path file = tempDir.resolve("User.java");
        String stepId = "step-1";

        manager.acquireWrite(stepId, file);

        assertDoesNotThrow(() -> manager.acquireWrite(stepId, file), "同一步骤重入应该允许");
        assertTrue(manager.isLeaseValid(stepId, file));
    }

    @Test
    void acquireWrite_shouldAllowAccessAfterTimeout(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("User.java");
        String step1 = "step-1";
        String step2 = "step-2";

        var clock = new java.util.concurrent.atomic.AtomicLong();
        manager = new ResourceLeaseManager(30_000, 600_000, clock::get);
        manager.acquireWrite(step1, file);
        clock.set(31_000);

        // step-2 应该能够获取租约（step-1 已超时）
        assertDoesNotThrow(() -> manager.acquireWrite(step2, file), "超时后其他步骤应该能获取租约");

        assertTrue(manager.isLeaseValid(step2, file), "step-2 应该持有有效租约");
        assertFalse(manager.isLeaseValid(step1, file), "step-1 的租约应该失效");
    }

    @Test
    void isLeaseValid_shouldReturnFalseForExpiredLease(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("User.java");
        String stepId = "step-1";

        var clock = new java.util.concurrent.atomic.AtomicLong();
        manager = new ResourceLeaseManager(30_000, 600_000, clock::get);
        manager.acquireWrite(stepId, file);
        assertTrue(manager.isLeaseValid(stepId, file));

        clock.set(31_000);

        // 租约应该失效
        assertFalse(manager.isLeaseValid(stepId, file), "超时的租约应该失效");
    }

    @Test
    void renewalCannotExtendAbsoluteLifetime(@TempDir Path dir) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        manager = new ResourceLeaseManager(30, 100, clock::get);
        Path file = dir.resolve("file.txt");
        manager.acquireWrite("a", file);
        for (long time = 20; time < 100; time += 20) {
            clock.set(time);
            manager.acquireWrite("a", file);
            assertTrue(manager.isLeaseValid("a", file));
        }
        clock.set(100);
        assertFalse(manager.isLeaseValid("a", file));
        assertThrows(ResourceLeaseException.class, () -> manager.acquireWrite("a", file));
        manager.acquireWrite("b", file);
        assertThrows(ResourceLeaseException.class, () -> manager.acquireWrite("a", file));
    }

    @Test
    void absoluteExpiryAllowsPreemptionAndReportsTotalLifetime(@TempDir Path dir) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var held = new java.util.concurrent.atomic.AtomicLong();
        manager = new ResourceLeaseManager(200, 100, clock::get);
        manager.setPreemptionListener((path, old, next, duration) -> held.set(duration));
        Path file = dir.resolve("file.txt");
        manager.acquireWrite("a", file);
        clock.set(90);
        manager.acquireWrite("a", file);
        clock.set(100);
        manager.acquireWrite("b", file);
        assertEquals(100, held.get());
        assertTrue(manager.isLeaseValid("b", file));
    }

    @Test
    void cleanupHonorsAbsoluteDeadlineAfterRenewal(@TempDir Path dir) {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        manager = new ResourceLeaseManager(200, 100, clock::get);
        manager.acquireWrite("a", dir.resolve("file.txt"));
        clock.set(90);
        manager.acquireWrite("a", dir.resolve("file.txt"));
        assertEquals(0, manager.pruneExpiredLeases());
        clock.set(100);
        assertEquals(1, manager.pruneExpiredLeases());
        assertEquals(0, manager.leaseCount());
    }
}
