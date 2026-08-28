package com.devcli.workspace;

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

        backend.cleanup(project, workspaceBase, workspace);

        assertFalse(Files.exists(workspace));
        assertFalse(runGit(project, "worktree", "list", "--porcelain")
                .contains(workspace.toString().replace('\\', '/')));
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
