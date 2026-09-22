package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
    private final Map<String, FileModeSnapshot> baselineModes;
    private final WorkspaceCleanupPolicy.Lease lease;
    private final WorkspaceBackend backend;
    private boolean closed;

    private IsolatedWorkspace(Path projectRoot, Path workspaceBase,
                              Path workspacePath, Map<String, String> baselineHashes,
                              Map<String, FileModeSnapshot> baselineModes,
                              WorkspaceCleanupPolicy.Lease lease,
                              WorkspaceBackend backend) {
        this.projectRoot = projectRoot;
        this.workspaceBase = workspaceBase;
        this.workspacePath = workspacePath;
        this.baselineHashes = Map.copyOf(baselineHashes);
        this.baselineModes = Map.copyOf(baselineModes);
        this.lease = lease;
        this.backend = backend;
    }

    public static IsolatedWorkspace create(Path projectRoot, String stepId) throws IOException {
        Path root = normalize(projectRoot);
        String override = System.getProperty("devcli.workspace.dir");
        Path base = override == null || override.isBlank()
                ? root.resolve("Temp").resolve("devcli-workspaces")
                : Path.of(override);
        return create(root, base, stepId, WorkspaceBackendFactory.create(root),
                new WorkspaceCleanupPolicy());
    }

    public static IsolatedWorkspace create(Path projectRoot, Path workspaceBase,
                                           String stepId) throws IOException {
        Path root = normalize(projectRoot);
        return create(root, workspaceBase, stepId, WorkspaceBackendFactory.create(root),
                new WorkspaceCleanupPolicy());
    }

    static IsolatedWorkspace create(Path projectRoot, Path workspaceBase, String stepId,
                                    WorkspaceBackend backend,
                                    WorkspaceCleanupPolicy cleanupPolicy) throws IOException {
        Path root = normalize(projectRoot);
        Path base = normalize(workspaceBase);
        if (root.startsWith(base)) {
            throw new IOException("workspace base must not contain project root: " + base);
        }
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
                    materialization.baselineHashes(),
                    captureModes(workspace, materialization.baselineHashes().keySet()),
                    lease, backend);
        } catch (IOException | RuntimeException e) {
            try {
                backend.cleanup(root, base, workspace);
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
        Set<String> seen = new HashSet<>();
        List<PatchSet.FileChange> changes = new ArrayList<>();
        Files.walkFileTree(workspacePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(workspacePath)
                        && WorkspacePathPolicy.isExcluded(workspacePath, null, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
                        || WorkspacePathPolicy.isExcluded(workspacePath, null, file)) {
                    return FileVisitResult.CONTINUE;
                }
                String relativePath = WorkspacePathPolicy.relativePath(workspacePath, file);
                seen.add(relativePath);
                String beforeHash = baselineHashes.getOrDefault(
                        relativePath, PatchSet.MISSING_HASH);
                FileModeSnapshot beforeMode = baselineModes.get(relativePath);
                FileModeSnapshot afterMode = FileModeSnapshot.capture(file);
                String observedHash = PatchSet.hash(file);
                if (beforeHash.equals(observedHash)
                        && java.util.Objects.equals(beforeMode, afterMode)) {
                    return FileVisitResult.CONTINUE;
                }
                byte[] content = Files.readAllBytes(file);
                String contentHash = PatchSet.hash(content);
                if (beforeHash.equals(contentHash)
                        && java.util.Objects.equals(beforeMode, afterMode)) {
                    return FileVisitResult.CONTINUE;
                }
                PatchSet.ChangeType type = PatchSet.MISSING_HASH.equals(beforeHash)
                        ? PatchSet.ChangeType.ADD
                        : PatchSet.ChangeType.MODIFY;
                changes.add(new PatchSet.FileChange(
                        relativePath, type, beforeHash, contentHash, content,
                        beforeMode, afterMode));
                return FileVisitResult.CONTINUE;
            }
        });

        for (Map.Entry<String, String> baseline : baselineHashes.entrySet()) {
            if (!seen.contains(baseline.getKey())) {
                changes.add(new PatchSet.FileChange(
                        baseline.getKey(), PatchSet.ChangeType.DELETE,
                        baseline.getValue(), PatchSet.MISSING_HASH, new byte[0],
                        baselineModes.get(baseline.getKey()), null));
            }
        }
        return new PatchSet(changes);
    }

    private static Path normalize(Path path) {
        return WorkspacePathPolicy.normalize(path);
    }

    private static Map<String, FileModeSnapshot> captureModes(
            Path workspace, Set<String> relativePaths) throws IOException {
        Map<String, FileModeSnapshot> modes = new java.util.HashMap<>();
        for (String relativePath : relativePaths) {
            FileModeSnapshot mode = FileModeSnapshot.capture(workspace.resolve(relativePath));
            if (mode != null) {
                modes.put(relativePath, mode);
            }
        }
        return modes;
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
            backend.cleanup(projectRoot, workspaceBase, workspacePath);
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

}
