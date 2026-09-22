package com.devcli.tool;

import com.devcli.hitl.ApprovalResult;
import com.devcli.hitl.HitlHandler;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.policy.TaskGrant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力范围只能单调收窄：嵌套调用与隔离 fork 都不得放宽外层约束。
 */
class ToolAccessScopeNarrowingTest {

    /**
     * 收窄结果必须等于两个范围允许集合的交集；这条不变量是嵌套语义正确性的基础。
     */
    @Test
    void narrowMatchesPermittedSetIntersection() {
        for (ToolRegistry.ToolAccessScope left : ToolRegistry.ToolAccessScope.values()) {
            for (ToolRegistry.ToolAccessScope right : ToolRegistry.ToolAccessScope.values()) {
                ToolRegistry.ToolAccessScope narrowed = left.narrow(right);
                for (ToolRegistry.ToolEffect effect : ToolRegistry.ToolEffect.values()) {
                    assertEquals(left.permits(effect) && right.permits(effect),
                            narrowed.permits(effect),
                            left + ".narrow(" + right + ") 对 " + effect + " 的判定不等于交集");
                }
            }
        }
    }

    @Test
    void narrowIsCommutativeAndIdempotent() {
        for (ToolRegistry.ToolAccessScope left : ToolRegistry.ToolAccessScope.values()) {
            assertEquals(left, left.narrow(left));
            for (ToolRegistry.ToolAccessScope right : ToolRegistry.ToolAccessScope.values()) {
                assertEquals(left.narrow(right), right.narrow(left));
            }
        }
    }

    @Test
    void nestedScopeCannotWidenBackToFull(@TempDir Path tempDir) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(tempDir.toString());

            registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
                // 内层显式要求 FULL，也不得放宽外层的只读约束
                ToolOutput output = registry.runWithToolAccess(ToolRegistry.ToolAccessScope.FULL,
                        () -> registry.executeToolOutput("write_file",
                                "{\"path\":\"nested.txt\",\"content\":\"x\"}"));

                assertEquals(ToolStatus.REJECTED, output.status(), output.text());
                assertEquals(ToolErrorCode.CAPABILITY_DENIED, output.errorCode());
                assertFalse(Files.exists(tempDir.resolve("nested.txt")));
                return null;
            });
        }
    }

    @Test
    void isolatedNestedInsideReadOnlyStaysReadOnly(@TempDir Path tempDir) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(tempDir.toString());

            registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
                ToolOutput output = registry.runWithToolAccess(
                        ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                        () -> registry.executeToolOutput("write_file",
                                "{\"path\":\"isolated-nested.txt\",\"content\":\"x\"}"));

                assertEquals(ToolErrorCode.CAPABILITY_DENIED, output.errorCode(),
                        "只读外层下的隔离内层不得写文件");
                assertFalse(Files.exists(tempDir.resolve("isolated-nested.txt")));
                return null;
            });
        }
    }

    /**
     * 隔离 fork 的权限上限取自创建它的线程，因此 fork 自己声明 ISOLATED_PROJECT
     * 也不能突破创建时的只读约束。
     */
    @Test
    void forkCannotWidenBeyondCreatorScope(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent");
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(parentRoot);
        Files.createDirectories(forkRoot);

        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(parentRoot.toString());

            parent.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
                try (ToolRegistry fork = parent.forkForProject(forkRoot)) {
                    ToolOutput output = fork.runWithToolAccess(
                            ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                            () -> fork.executeToolOutput("write_file",
                                    "{\"path\":\"fork-write.txt\",\"content\":\"x\"}"));

                    assertEquals(ToolErrorCode.CAPABILITY_DENIED, output.errorCode(),
                            "只读父注册表派生的隔离 fork 不得写入");
                    assertFalse(Files.exists(forkRoot.resolve("fork-write.txt")));
                    return null;
                }
            });
        }
    }

    @Test
    void forkCreatedWithoutNarrowingKeepsIsolatedWrites(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent");
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(parentRoot);
        Files.createDirectories(forkRoot);

        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(parentRoot.toString());
            try (ToolRegistry fork = parent.forkForProject(forkRoot)) {
                ToolOutput output = fork.runWithToolAccess(
                        ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                        () -> fork.executeToolOutput("write_file",
                                "{\"path\":\"fork-write.txt\",\"content\":\"ok\"}"));

                assertTrue(output.isSuccess(), output.text());
                assertEquals("ok", Files.readString(forkRoot.resolve("fork-write.txt")));
            }
        }
    }

    /** fork 继承父线程的任务级授权，编排路径不会凭空丢失用户已给出的授权。 */
    @Test
    void forkInheritsParentTaskGrant(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent");
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(parentRoot);
        Files.createDirectories(forkRoot);

        AtomicInteger prompts = new AtomicInteger();
        HitlHandler handler = countingHandler(prompts);
        try (HitlToolRegistry parent = new HitlToolRegistry(handler)) {
            parent.setProjectPath(parentRoot.toString());

            parent.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                try (ToolRegistry fork = parent.forkForProject(forkRoot)) {
                    assertEquals(TaskGrant.WORKSPACE_WRITES, fork.currentTaskGrant(),
                            "fork 应继承父线程授权");
                    ToolOutput output = fork.runWithToolAccess(
                            ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                            () -> fork.executeToolOutput("write_file",
                                    "{\"path\":\"inherited.txt\",\"content\":\"ok\"}"));
                    assertTrue(output.isSuccess(), output.text());
                    return null;
                }
            });
        }
        assertEquals(0, prompts.get(), "继承的授权应使隔离写免于人工审批");
    }

    /** fork 内也必须有办法主动收回继承来的授权。 */
    @Test
    void forkCanRevokeInheritedTaskGrant(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent");
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(parentRoot);
        Files.createDirectories(forkRoot);

        AtomicInteger prompts = new AtomicInteger();
        HitlHandler handler = countingHandler(prompts);
        try (HitlToolRegistry parent = new HitlToolRegistry(handler)) {
            parent.setProjectPath(parentRoot.toString());

            parent.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                try (ToolRegistry fork = parent.forkForProject(forkRoot)) {
                    ToolOutput output = fork.runWithTaskGrant(TaskGrant.NONE,
                            () -> fork.runWithToolAccess(
                                    ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                                    () -> fork.executeToolOutput("write_file",
                                            "{\"path\":\"revoked.txt\",\"content\":\"ok\"}")));
                    assertTrue(output.isSuccess(), output.text());
                    return null;
                }
            });
        }
        assertEquals(1, prompts.get(), "显式撤销后必须重新人工确认");
    }

    /**
     * 编排波次线程池在轮次内新建，因此轮次的能力范围与授权必须能被新线程继承，
     * 否则 /readonly 与 /grant 无法作用到编排路径。
     */
    @Test
    void perTurnScopeAndGrantAreInheritedByThreadsCreatedDuringTheTurn() {
        try (ToolRegistry registry = new ToolRegistry()) {
            java.util.concurrent.atomic.AtomicReference<String> observed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            registry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () ->
                    registry.runWithTaskGrant(TaskGrant.WORKSPACE_WRITES, () -> {
                        Thread waveWorker = new Thread(() -> observed.set(
                                registry.currentToolAccessScope().name() + ","
                                        + registry.currentTaskGrant().workspaceWrites()));
                        waveWorker.start();
                        try {
                            waveWorker.join(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    }));

            assertEquals("READ_ONLY,true", observed.get(),
                    "轮次内新建的线程应继承当前能力范围与授权");
        }
    }

    private static HitlHandler countingHandler(AtomicInteger prompts) {
        return new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(com.devcli.hitl.ApprovalRequest request) {
                prompts.incrementAndGet();
                return ApprovalResult.approve();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {
            }
        };
    }
}
