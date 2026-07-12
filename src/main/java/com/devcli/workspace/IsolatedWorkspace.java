package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单个 Plan task / Multi-Agent step 的隔离文件系统视图。
 */
public final class IsolatedWorkspace implements AutoCloseable {
    private static final Set<String> EXCLUDED_ROOTS = Set.of(
            ".git", ".devcli", ".idea", "target", "build", "dist",
            "node_modules", "Temp", "Log");

    private final Path projectRoot;
    private final Path workspaceBase;
    private final Path workspacePath;
    private final Map<String, String> baselineHashes;
    private boolean closed;

    private IsolatedWorkspace(Path projectRoot, Path workspaceBase,
                              Path workspacePath, Map<String, String> baselineHashes) {
        this.projectRoot = projectRoot;
        this.workspaceBase = workspaceBase;
        this.workspacePath = workspacePath;
        this.baselineHashes = Map.copyOf(baselineHashes);
    }

    public static IsolatedWorkspace create(Path projectRoot, String stepId) throws IOException {
        Path root = normalize(projectRoot);
        String override = System.getProperty("devcli.workspace.dir");
        Path base = override == null || override.isBlank()
                ? root.resolve("Temp").resolve("devcli-workspaces")
                : Path.of(override);
        return create(root, base, stepId);
    }

    public static IsolatedWorkspace create(Path projectRoot, Path workspaceBase,
                                           String stepId) throws IOException {
        Path root = normalize(projectRoot);
        Path base = normalize(workspaceBase);
        Files.createDirectories(root);
        Files.createDirectories(base);
        String safeStep = sanitize(stepId);
        Path workspace = base.resolve(safeStep + "-" + UUID.randomUUID()).normalize();
        if (!workspace.startsWith(base) || workspace.equals(base)) {
            throw new IOException("invalid isolated workspace path");
        }
        Files.createDirectories(workspace);

        Map<String, String> baseline = new HashMap<>();
        try {
            copyProject(root, base, workspace, baseline);
            return new IsolatedWorkspace(root, base, workspace, baseline);
        } catch (Exception e) {
            deleteWorkspace(base, workspace);
            throw e;
        }
    }

    public Path path() {
        return workspacePath;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public PatchSet createPatchSet() throws IOException {
        ensureOpen();
        Map<String, byte[]> current = scanFiles(workspacePath, null);
        Set<String> paths = new HashSet<>(baselineHashes.keySet());
        paths.addAll(current.keySet());
        List<PatchSet.FileChange> changes = new ArrayList<>();
        for (String relativePath : paths) {
            String beforeHash = baselineHashes.getOrDefault(relativePath, PatchSet.MISSING_HASH);
            byte[] content = current.get(relativePath);
            if (content == null) {
                changes.add(new PatchSet.FileChange(relativePath, PatchSet.ChangeType.DELETE,
                        beforeHash, PatchSet.MISSING_HASH, new byte[0]));
                continue;
            }
            String afterHash = PatchSet.hash(content);
            if (beforeHash.equals(afterHash)) {
                continue;
            }
            PatchSet.ChangeType type = PatchSet.MISSING_HASH.equals(beforeHash)
                    ? PatchSet.ChangeType.ADD
                    : PatchSet.ChangeType.MODIFY;
            changes.add(new PatchSet.FileChange(
                    relativePath, type, beforeHash, afterHash, content));
        }
        return new PatchSet(changes);
    }

    private static void copyProject(Path root, Path workspaceBase, Path workspace,
                                    Map<String, String> baseline) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (isExcluded(root, workspaceBase, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
                        || isExcluded(root, workspaceBase, file)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = relativePath(root, file);
                byte[] bytes = Files.readAllBytes(file);
                baseline.put(relative, PatchSet.hash(bytes));
                Path target = workspace.resolve(relative).normalize();
                if (!target.startsWith(workspace)) {
                    throw new IOException("workspace copy path escaped: " + relative);
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, byte[]> scanFiles(Path root, Path ignoredBase) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isExcluded(root, ignoredBase, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && !isExcluded(root, ignoredBase, file)) {
                    result.put(relativePath(root, file), Files.readAllBytes(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static boolean isExcluded(Path root, Path workspaceBase, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (workspaceBase != null && normalized.startsWith(workspaceBase)) {
            return true;
        }
        Path relative = root.relativize(normalized);
        return relative.getNameCount() > 0
                && EXCLUDED_ROOTS.contains(relative.getName(0).toString());
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static Path normalize(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        return path.toAbsolutePath().normalize();
    }

    private static String sanitize(String stepId) {
        String value = stepId == null || stepId.isBlank() ? "step" : stepId;
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("isolated workspace is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        deleteWorkspace(workspaceBase, workspacePath);
    }

    private static void deleteWorkspace(Path base, Path workspace) throws IOException {
        Path normalizedBase = normalize(base);
        Path normalizedWorkspace = normalize(workspace);
        if (normalizedWorkspace.equals(normalizedBase)
                || !normalizedWorkspace.startsWith(normalizedBase)) {
            throw new IOException("refusing to delete workspace outside configured base");
        }
        if (!Files.exists(normalizedWorkspace)) {
            return;
        }
        try (var stream = Files.walk(normalizedWorkspace)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
