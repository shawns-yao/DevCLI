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

        manager.acquireWrite(step1, file);

        // 模拟租约超时：通过反射修改内部时间戳
        var field = ResourceLeaseManager.class.getDeclaredField("writeOwners");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var writeOwners = (java.util.Map<Path, Object>) field.get(manager);

        var leaseEntryClass = Class.forName("com.devcli.tool.ResourceLeaseManager$LeaseEntry");
        var constructor = leaseEntryClass.getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);

        // 31 秒前（超过 30 秒超时阈值）
        long expiredTime = System.currentTimeMillis() - 31_000;
        var expiredEntry = constructor.newInstance(step1, expiredTime);
        writeOwners.put(file.toAbsolutePath().normalize(), expiredEntry);

        // step-2 应该能够获取租约（step-1 已超时）
        assertDoesNotThrow(() -> manager.acquireWrite(step2, file), "超时后其他步骤应该能获取租约");

        assertTrue(manager.isLeaseValid(step2, file), "step-2 应该持有有效租约");
        assertFalse(manager.isLeaseValid(step1, file), "step-1 的租约应该失效");
    }

    @Test
    void isLeaseValid_shouldReturnFalseForExpiredLease(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("User.java");
        String stepId = "step-1";

        manager.acquireWrite(stepId, file);
        assertTrue(manager.isLeaseValid(stepId, file));

        // 模拟超时
        var field = ResourceLeaseManager.class.getDeclaredField("writeOwners");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var writeOwners = (java.util.Map<Path, Object>) field.get(manager);

        var leaseEntryClass = Class.forName("com.devcli.tool.ResourceLeaseManager$LeaseEntry");
        var constructor = leaseEntryClass.getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);

        long expiredTime = System.currentTimeMillis() - 31_000;
        var expiredEntry = constructor.newInstance(stepId, expiredTime);
        writeOwners.put(file.toAbsolutePath().normalize(), expiredEntry);

        // 租约应该失效
        assertFalse(manager.isLeaseValid(stepId, file), "超时的租约应该失效");
    }
}
