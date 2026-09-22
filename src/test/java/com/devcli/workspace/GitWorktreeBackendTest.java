package com.devcli.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitWorktreeBackendTest {

    /** 项目级锁默认落在 {@code ~/.devcli/locks}；受限环境下不可写，测试重定向到临时目录。 */
    @TempDir
    Path lockDir;

    private String previousLockDir;

    @BeforeEach
    void redirectCommitLocks() {
        previousLockDir = System.getProperty(ProjectCommitCoordinator.LOCK_DIR_PROPERTY);
        System.setProperty(ProjectCommitCoordinator.LOCK_DIR_PROPERTY, lockDir.toString());
    }

    @AfterEach
    void restoreCommitLocks() {
        if (previousLockDir == null) {
            System.clearProperty(ProjectCommitCoordinator.LOCK_DIR_PROPERTY);
        } else {
            System.setProperty(ProjectCommitCoordinator.LOCK_DIR_PROPERTY, previousLockDir);
        }
    }

    @Test
    void materializesCurrentDirtyAndUntrackedState(@TempDir Path tempDir) throws Exception {
        Path project = createRepository(tempDir.resolve("project"));
        Path workspaceBase = Files.createDirectories(tempDir.resolve("workspaces"));
        Path workspace = Files.createDirectory(workspaceBase.resolve("workspace"));
        Files.writeString(project.resolve("tracked.txt"), "dirty");
        Files.delete(project.resolve("deleted.txt"));
        Files.writeString(project.resolve("untracked.txt"), "untracked");
        Files.writeString(project.resolve("ignored.env"), "secret");
        Files.writeString(project.resolve(".env"), "TOKEN=secret");
        Files.createDirectories(project.resolve("nested/.git"));
        Files.writeString(project.resolve("nested/.git/config"), "nested-secret");
        GitWorktreeBackend backend = new GitWorktreeBackend(TimeUnit.SECONDS.toMillis(30));

        WorkspaceBackend.Materialization result =
                backend.materialize(project, workspaceBase, workspace);

        assertEquals("dirty", Files.readString(workspace.resolve("tracked.txt")));
        assertFalse(Files.exists(workspace.resolve("deleted.txt")));
        assertEquals("untracked", Files.readString(workspace.resolve("untracked.txt")));
        assertEquals("secret", Files.readString(workspace.resolve("ignored.env")));
        assertFalse(Files.exists(workspace.resolve(".env")));
        assertFalse(Files.exists(workspace.resolve("nested/.git")));
        assertTrue(Files.isRegularFile(workspace.resolve(".git")));
        assertFalse(Files.isSymbolicLink(workspace.resolve("tracked-link")));
        assertEquals(PatchSet.hash(workspace.resolve("tracked.txt")),
                result.baselineHashes().get("tracked.txt"));

        // 叠加成本只统计真正复制进子工作区的内容：脏的已跟踪文件 + 未跟踪文件。
        // 被排除的目录（nested/.git）与受保护的凭据（.env）都不计入，也不占用预算。
        long expectedOverlayBytes = Files.size(project.resolve("tracked.txt"))
                + Files.size(project.resolve("untracked.txt"))
                + Files.size(project.resolve("ignored.env"));
        assertEquals(expectedOverlayBytes, result.overlayBytes());

        backend.cleanup(project, workspaceBase, workspace);

        assertFalse(Files.exists(workspace));
        assertFalse(runGit(project, "worktree", "list", "--porcelain")
                .contains(workspace.toString().replace('\\', '/')));
    }

    @Test
    void overlayBudgetFallsBackToDefaultOnMissingOrInvalidValue() {
        long fallback = GitWorktreeBackend.resolveOverlayBudgetBytes(new Properties(), Map.of());

        assertEquals(fallback, GitWorktreeBackend.resolveOverlayBudgetBytes(null, null));
        assertEquals(fallback, GitWorktreeBackend.resolveOverlayBudgetBytes(new Properties(),
                Map.of(GitWorktreeBackend.OVERLAY_BUDGET_ENV, "not-a-number")));
        assertEquals(fallback, GitWorktreeBackend.resolveOverlayBudgetBytes(new Properties(),
                Map.of(GitWorktreeBackend.OVERLAY_BUDGET_ENV, "0")));
        assertEquals(fallback, GitWorktreeBackend.resolveOverlayBudgetBytes(new Properties(),
                Map.of(GitWorktreeBackend.OVERLAY_BUDGET_ENV, "-1")));

        // 系统属性优先于环境变量，与工作区后端的其它配置项一致。
        Properties properties = new Properties();
        properties.setProperty(GitWorktreeBackend.OVERLAY_BUDGET_PROPERTY, "1024");
        assertEquals(1024L, GitWorktreeBackend.resolveOverlayBudgetBytes(properties,
                Map.of(GitWorktreeBackend.OVERLAY_BUDGET_ENV, "2048")));
        assertEquals(2048L, GitWorktreeBackend.resolveOverlayBudgetBytes(new Properties(),
                Map.of(GitWorktreeBackend.OVERLAY_BUDGET_ENV, "2048")));
    }

    @Test
    void overlayCostAccumulatorKeepsOnlyHeaviestSources() {
        GitWorktreeBackend.OverlayCostAccumulator cost =
                new GitWorktreeBackend.OverlayCostAccumulator(2);
        cost.record("small.txt", 10);
        cost.record("large.bin", 5_000);
        cost.record("medium.dat", 900);
        cost.record(null, 100);
        cost.record("empty.txt", 0);

        GitWorktreeBackend.OverlayCost snapshot = cost.snapshot();

        assertEquals(5_910L, snapshot.totalBytes(), "全部有效记录都应计入总量");
        assertEquals(2, snapshot.topContributors().size(), "来源列表必须有界");
        assertTrue(snapshot.topContributors().get(0).startsWith("large.bin"),
                snapshot.topContributors().toString());
        assertTrue(snapshot.topContributors().get(1).startsWith("medium.dat"),
                snapshot.topContributors().toString());
    }

    @Test
    void factoryUsesGitBackendForRepositoryAndCowForPlainDirectory(@TempDir Path tempDir)
            throws Exception {
        Path project = createRepository(tempDir.resolve("project"));
        Path plain = Files.createDirectories(tempDir.resolve("plain"));

        assertInstanceOf(GitWorktreeBackend.class, WorkspaceBackendFactory.create(project));
        assertInstanceOf(FileSystemCowWorkspaceBackend.class, WorkspaceBackendFactory.create(plain));
    }

    @Test
    void explicitBackendConfigurationOverridesAutoDetection() {
        Properties properties = new Properties();
        properties.setProperty(WorkspaceBackendFactory.BACKEND_PROPERTY, "copy");

        assertEquals("copy", WorkspaceBackendFactory.resolveMode(properties,
                Map.of(WorkspaceBackendFactory.BACKEND_ENV, "git")));
    }

    private static Path createRepository(Path project) throws Exception {
        Files.createDirectories(project);
        runGit(project, "init");
        runGit(project, "config", "user.email", "devcli-test@example.invalid");
        runGit(project, "config", "user.name", "DevCLI Test");
        Files.writeString(project.resolve(".gitignore"), "ignored.env\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("tracked.txt"), "base", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("deleted.txt"), "delete", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(project.resolve("tracked-link"), project.resolve("tracked.txt"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            // 当前文件系统不允许创建符号链接时跳过该平台分支。
        }
        runGit(project, "add", ".");
        runGit(project, "commit", "-m", "initial");
        return project;
    }

    private static String runGit(Path project, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(project.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
