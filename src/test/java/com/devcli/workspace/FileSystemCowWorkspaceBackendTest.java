package com.devcli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemCowWorkspaceBackendTest {

    @Test
    void recognizesWindowsBuildsWithNativeRefsBlockCloneSupport() {
        assertFalse(FileSystemCowWorkspaceBackend.supportsWindowsBlockCloning("10.0"));
        assertFalse(FileSystemCowWorkspaceBackend.supportsWindowsBlockCloning("10.0.22621"));
        assertFalse(FileSystemCowWorkspaceBackend.supportsWindowsBlockCloning("10.0.22631"));
        assertTrue(FileSystemCowWorkspaceBackend.supportsWindowsBlockCloning("10.0.26100"));
    }

    @Test
    void nativeCloneStrategyProducesIndependentWorkspaceAndBaseline(@TempDir Path tempDir)
            throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("root.txt"), "root-base");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "class App {}");
        Path workspaceBase = Files.createDirectories(tempDir.resolve("workspaces"));
        Path workspace = Files.createDirectory(workspaceBase.resolve("workspace"));
        FileSystemCowWorkspaceBackend backend = new FileSystemCowWorkspaceBackend(
                new CopyingCloneStrategy(), new CopyWorkspaceBackend(1), 10_000L);

        WorkspaceBackend.Materialization materialization =
                backend.materialize(project, workspaceBase, workspace);

        assertTrue(backend.usedNativeClone(workspace));
        assertEquals(PatchSet.hash(project.resolve("root.txt")),
                materialization.baselineHashes().get("root.txt"));
        assertEquals(PatchSet.hash(project.resolve("src/App.java")),
                materialization.baselineHashes().get("src/App.java"));
        Files.writeString(workspace.resolve("root.txt"), "workspace-change");
        assertEquals("root-base", Files.readString(project.resolve("root.txt")));
    }

    @Test
    void cloneFailureCleansPartialFilesAndFallsBackToCopy(@TempDir Path tempDir)
            throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("source.txt"), "base");
        Path workspaceBase = Files.createDirectories(tempDir.resolve("workspaces"));
        Path workspace = Files.createDirectory(workspaceBase.resolve("workspace"));
        FileSystemCowWorkspaceBackend.CloneStrategy failing =
                new FileSystemCowWorkspaceBackend.CloneStrategy() {
                    @Override
                    public boolean available(Path projectRoot, Path workspacePath) {
                        return true;
                    }

                    @Override
                    public void cloneEntries(List<Path> sourceEntries, Path workspacePath,
                                             long timeoutMillis) throws IOException {
                        Files.writeString(workspacePath.resolve("partial.txt"), "partial");
                    }
                };
        FileSystemCowWorkspaceBackend backend = new FileSystemCowWorkspaceBackend(
                failing, new CopyWorkspaceBackend(1), 10_000L);

        WorkspaceBackend.Materialization materialization =
                backend.materialize(project, workspaceBase, workspace);

        assertFalse(backend.usedNativeClone(workspace));
        assertFalse(Files.exists(workspace.resolve("partial.txt")));
        assertEquals("base", Files.readString(workspace.resolve("source.txt")));
        assertEquals(PatchSet.hash(project.resolve("source.txt")),
                materialization.baselineHashes().get("source.txt"));
        Files.writeString(workspace.resolve("source.txt"), "workspace");
        assertEquals("base", Files.readString(project.resolve("source.txt")));
    }

    private static final class CopyingCloneStrategy
            implements FileSystemCowWorkspaceBackend.CloneStrategy {
        @Override
        public boolean available(Path projectRoot, Path workspacePath) {
            return true;
        }

        @Override
        public void cloneEntries(List<Path> sourceEntries, Path workspacePath,
                                 long timeoutMillis) throws IOException {
            for (Path source : sourceEntries) {
                Path target = workspacePath.resolve(source.getFileName().toString());
                if (Files.isDirectory(source)) {
                    copyDirectory(source, target);
                } else {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }

        private static void copyDirectory(Path source, Path target) throws IOException {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
