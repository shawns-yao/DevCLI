package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
    private final Path projectRoot;
    private final Path workspaceBase;
    private final Path workspacePath;
    private final Map<String, String> baselineHashes;
    private final WorkspaceCleanupPolicy.Lease lease;
    private boolean closed;

    private IsolatedWorkspace(Path projectRoot, Path workspaceBase,
                              Path workspacePath, Map<String, String> baselineHashes,
                              WorkspaceCleanupPolicy.Lease lease) {
        this.projectRoot = projectRoot;
        this.workspaceBase = workspaceBase;
        this.workspacePath = workspacePath;
        this.baselineHashes = Map.copyOf(baselineHashes);
        this.lease = lease;
    }

    public static IsolatedWorkspace create(Path projectRoot, String stepId) throws IOException {
        Path root = normalize(projectRoot);
        String override = System.getProperty("devcli.workspace.dir");
        Path base = override == null || override.isBlank()
                ? root.resolve("Temp").resolve("devcli-workspaces")
                : Path.of(override);
        return create(root, base, stepId, new CopyWorkspaceBackend(),
                new WorkspaceCleanupPolicy());
    }

    public static IsolatedWorkspace create(Path projectRoot, Path workspaceBase,
                                           String stepId) throws IOException {
        return create(projectRoot, workspaceBase, stepId, new CopyWorkspaceBackend(),
                new WorkspaceCleanupPolicy());
    }

    static IsolatedWorkspace create(Path projectRoot, Path workspaceBase, String stepId,
                                    WorkspaceBackend backend,
                                    WorkspaceCleanupPolicy cleanupPolicy) throws IOException {
        Path root = normalize(projectRoot);
        Path base = normalize(workspaceBase);
        Files.createDirectories(root);
        Files.createDirectories(base);
        cleanupPolicy.cleanup(base);
        String safeStep = sanitize(stepId);
        Path workspace = base.resolve(safeStep + "-" + UUID.randomUUID()).normalize();
        if (!workspace.startsWith(base) || workspace.equals(base)) {
            throw new IOException("invalid isolated workspace path");
        }

        WorkspaceCleanupPolicy.Lease lease = cleanupPolicy.acquireLease(base, workspace);
        try {
            Files.createDirectory(workspace);
            WorkspaceBackend.Materialization materialization =
                    backend.materialize(root, base, workspace);
            return new IsolatedWorkspace(root, base, workspace,
                    materialization.baselineHashes(), lease);
        } catch (IOException | RuntimeException e) {
            try {
                deleteWorkspace(base, workspace);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            try {
                lease.close();
            } catch (IOException leaseFailure) {
                e.addSuppressed(leaseFailure);
            }
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

    private static Map<String, byte[]> scanFiles(Path root, Path ignoredBase) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && WorkspacePathPolicy.isExcluded(root, ignoredBase, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && !WorkspacePathPolicy.isExcluded(root, ignoredBase, file)) {
                    result.put(WorkspacePathPolicy.relativePath(root, file), Files.readAllBytes(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    private static Path normalize(Path path) {
        return WorkspacePathPolicy.normalize(path);
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
        IOException failure = null;
        try {
            deleteWorkspace(workspaceBase, workspacePath);
        } catch (IOException e) {
            failure = e;
        }
        try {
            lease.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void deleteWorkspace(Path base, Path workspace) throws IOException {
        WorkspaceCleanupPolicy.deleteWorkspace(base, workspace);
    }
}
