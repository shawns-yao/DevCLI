package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工作区后端共享的源目录扫描与回退重置逻辑。
 */
final class WorkspaceSourceTree {
    private WorkspaceSourceTree() {
    }

    static List<Path> collectRegularFiles(Path projectRoot, Path workspaceBase) throws IOException {
        Path root = WorkspacePathPolicy.normalize(projectRoot);
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        List<Path> sources = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isSymbolicLink(dir)
                        || WorkspacePathPolicy.isExcluded(root, base, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && !WorkspacePathPolicy.isExcluded(root, base, file)) {
                    sources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    static List<Path> collectTopLevelEntries(Path projectRoot, Path workspaceBase) throws IOException {
        Path root = WorkspacePathPolicy.normalize(projectRoot);
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        try (var entries = Files.list(root)) {
            return entries
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !WorkspacePathPolicy.isExcluded(root, base, path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    static void removeSymbolicLinks(Path workspacePath) throws IOException {
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void removeSensitiveFiles(Path workspacePath) throws IOException {
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (attrs.isRegularFile() && WorkspacePathPolicy.isSensitiveFile(
                        workspace.relativize(file))) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void resetWorkspace(Path workspaceBase, Path workspacePath) throws IOException {
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        WorkspaceCleanupPolicy.deleteWorkspace(base, workspace);
        Files.createDirectory(workspace);
    }
}
