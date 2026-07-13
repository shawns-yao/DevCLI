package com.devcli.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通过原生 Git worktree 物化已跟踪基线，再叠加主工作区当前状态。
 */
public final class GitWorktreeBackend implements WorkspaceBackend {
    private final long timeoutMillis;

    public GitWorktreeBackend() {
        this(CopyWorkspaceBackend.resolveCopyTimeoutMillis(
                System.getProperties(), System.getenv()));
    }

    GitWorktreeBackend(long timeoutMillis) {
        this.timeoutMillis = Math.max(1, timeoutMillis);
    }

    static boolean supports(Path projectRoot) {
        if (projectRoot == null) {
            return false;
        }
        Path root = WorkspacePathPolicy.normalize(projectRoot);
        Process process = null;
        try {
            process = new ProcessBuilder("git", "-C", root.toString(),
                    "rev-parse", "--show-toplevel")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                return false;
            }
            String actual = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            return WorkspacePathPolicy.normalize(Path.of(actual)).equals(root);
        } catch (Exception ignored) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    @Override
    public Materialization materialize(Path projectRoot, Path workspaceBase,
                                       Path workspacePath) throws IOException {
        Path root = WorkspacePathPolicy.normalize(projectRoot);
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        verifyRepositoryRoot(root);
        runGit(root, List.of("worktree", "prune", "--expire", "now"));
        runGit(root, List.of("worktree", "add", "--detach", "--force",
                workspace.toString(), "HEAD"));
        try {
            overlayCurrentState(root, workspaceBase, workspace);
            removeExcludedRoots(workspace);
            removeSymbolicLinks(workspace);
            return new Materialization(snapshotHashes(workspace));
        } catch (IOException | RuntimeException e) {
            try {
                cleanup(root, workspaceBase, workspace);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    @Override
    public void cleanup(Path projectRoot, Path workspaceBase, Path workspacePath) throws IOException {
        Path root = WorkspacePathPolicy.normalize(projectRoot);
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        IOException failure = null;
        try {
            runGit(root, List.of("worktree", "remove", "--force", workspace.toString()));
        } catch (IOException e) {
            failure = e;
            WorkspaceCleanupPolicy.deleteWorkspace(workspaceBase, workspace);
        }
        try {
            runGit(root, List.of("worktree", "prune", "--expire", "now"));
        } catch (IOException pruneFailure) {
            if (failure == null) {
                failure = pruneFailure;
            } else {
                failure.addSuppressed(pruneFailure);
            }
        }
        if (failure != null && Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw failure;
        }
    }

    private void verifyRepositoryRoot(Path root) throws IOException {
        String actual = new String(runGit(root,
                List.of("rev-parse", "--show-toplevel")), StandardCharsets.UTF_8).trim();
        Path repositoryRoot = WorkspacePathPolicy.normalize(Path.of(actual));
        if (!repositoryRoot.equals(root)) {
            throw new IOException("Git worktree backend requires the project repository root: " + root);
        }
    }

    private void overlayCurrentState(Path root, Path workspaceBase,
                                     Path workspace) throws IOException {
        Set<String> tracked = new HashSet<>(splitZero(runGit(root,
                List.of("ls-files", "-z", "--cached"))));
        Set<String> dirty = new HashSet<>(splitZero(runGit(root,
                List.of("diff", "--name-only", "-z", "--no-renames", "HEAD", "--"))));
        for (String relative : dirty) {
            syncPath(root, workspace, relative);
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isSymbolicLink(dir)
                        || WorkspacePathPolicy.isExcluded(root, workspaceBase, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
                        || WorkspacePathPolicy.isExcluded(root, workspaceBase, file)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = WorkspacePathPolicy.relativePath(root, file);
                if (!tracked.contains(relative)) {
                    copyFile(root, workspace, file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void syncPath(Path root, Path workspace, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            return;
        }
        Path source = root.resolve(relative).normalize();
        Path target = workspace.resolve(relative).normalize();
        if (!source.startsWith(root) || !target.startsWith(workspace)
                || WorkspacePathPolicy.isExcluded(root, null, source)) {
            return;
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            deleteTree(target);
            return;
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(target);
            copyTree(source, target);
            return;
        }
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void copyFile(Path root, Path workspace, Path source) throws IOException {
        String relative = WorkspacePathPolicy.relativePath(root, source);
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) {
            throw new IOException("workspace overlay path escaped: " + relative);
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(dir) || ".git".equals(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && !".git".equals(file.getFileName().toString())) {
                    Path destination = target.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Map<String, String> snapshotHashes(Path workspace) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(workspace)
                        && WorkspacePathPolicy.isExcluded(workspace, null, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && !WorkspacePathPolicy.isExcluded(workspace, null, file)) {
                    hashes.put(WorkspacePathPolicy.relativePath(workspace, file),
                            PatchSet.hash(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return hashes;
    }

    private static void removeExcludedRoots(Path workspace) throws IOException {
        for (String excluded : WorkspacePathPolicy.excludedRoots()) {
            if (!".git".equals(excluded)) {
                deleteTree(workspace.resolve(excluded));
            }
        }
    }

    private static void removeSymbolicLinks(Path workspace) throws IOException {
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(file)) {
                    Files.deleteIfExists(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private byte[] runGit(Path root, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(arguments);
        Path stdout = Files.createTempFile("devcli-git-worktree-", ".out");
        Path stderr = Files.createTempFile("devcli-git-worktree-", ".err");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("Git worktree command timed out: " + String.join(" ", arguments));
            }
            byte[] output = Files.readAllBytes(stdout);
            if (process.exitValue() != 0) {
                String error = Files.readString(stderr, StandardCharsets.UTF_8).trim();
                throw new IOException("Git worktree command failed: " + error);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IOException("Git worktree command interrupted", e);
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static List<String> splitZero(byte[] bytes) {
        List<String> values = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == 0) {
                if (index > start) {
                    values.add(new String(bytes, start, index - start, StandardCharsets.UTF_8));
                }
                start = index + 1;
            }
        }
        return values;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
