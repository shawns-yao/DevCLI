package com.devcli.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

/**
 * 隔离工作区孤儿清理和活动租约策略。
 */
public final class WorkspaceCleanupPolicy {
    static final String TTL_PROPERTY = "devcli.workspace.orphan.ttl.hours";
    static final String TTL_ENV = "DEVCLI_WORKSPACE_ORPHAN_TTL_HOURS";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String LEASE_DIRECTORY = ".leases";

    private final Duration ttl;

    public WorkspaceCleanupPolicy() {
        this(resolveTtl(System.getProperties().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> String.valueOf(entry.getKey()),
                                entry -> String.valueOf(entry.getValue()))),
                System.getenv()));
    }

    WorkspaceCleanupPolicy(Duration ttl) {
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("workspace cleanup ttl must not be negative");
        }
        this.ttl = ttl;
    }

    public void cleanup(Path workspaceBase) throws IOException {
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        Files.createDirectories(base);
        Instant cutoff = Instant.now().minus(ttl);
        try (var entries = Files.list(base)) {
            for (Path candidate : entries.toList()) {
                if (candidate.getFileName().toString().equals(LEASE_DIRECTORY)
                        || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(candidate)
                        || Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isAfter(cutoff)) {
                    continue;
                }
                Lease lease = tryAcquireLease(base, candidate);
                if (lease == null) {
                    continue;
                }
                try (lease) {
                    deleteWorkspace(base, candidate);
                }
            }
        }
    }

    Lease acquireLease(Path workspaceBase, Path workspacePath) throws IOException {
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        Path workspace = validateWorkspace(base, workspacePath);
        Path leasePath = leasePath(base, workspace);
        Files.createDirectories(leasePath.getParent());
        FileChannel channel = FileChannel.open(leasePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            return new Lease(leasePath, channel, channel.lock());
        } catch (Exception e) {
            channel.close();
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("failed to acquire workspace lease", e);
        }
    }

    static Duration resolveTtl(Map<String, String> properties, Map<String, String> environment) {
        String value = properties == null ? null : properties.get(TTL_PROPERTY);
        if (value == null || value.isBlank()) {
            value = environment == null ? null : environment.get(TTL_ENV);
        }
        if (value == null || value.isBlank()) {
            return DEFAULT_TTL;
        }
        try {
            long hours = Long.parseLong(value.trim());
            return hours < 0 ? DEFAULT_TTL : Duration.ofHours(hours);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return DEFAULT_TTL;
        }
    }

    static void deleteWorkspace(Path workspaceBase, Path workspacePath) throws IOException {
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        Path workspace = validateWorkspace(base, workspacePath);
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Lease tryAcquireLease(Path base, Path workspace) throws IOException {
        Path leasePath = leasePath(base, validateWorkspace(base, workspace));
        Files.createDirectories(leasePath.getParent());
        FileChannel channel = FileChannel.open(leasePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new Lease(leasePath, channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        } catch (Exception e) {
            channel.close();
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("failed to inspect workspace lease", e);
        }
    }

    private static Path leasePath(Path base, Path workspace) throws IOException {
        String workspaceName = workspace.getFileName().toString();
        if (workspaceName.isBlank() || workspaceName.equals(".") || workspaceName.equals("..")) {
            throw new IOException("invalid isolated workspace name");
        }
        Path leaseDirectory = base.resolve(LEASE_DIRECTORY).normalize();
        Path leasePath = leaseDirectory.resolve(workspaceName + ".lck").normalize();
        if (!leasePath.startsWith(leaseDirectory) || leasePath.equals(leaseDirectory)) {
            throw new IOException("invalid workspace lease path");
        }
        return leasePath;
    }

    private static Path validateWorkspace(Path base, Path workspacePath) throws IOException {
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        if (workspace.equals(base) || !workspace.startsWith(base)
                || workspace.getParent() == null || !workspace.getParent().equals(base)) {
            throw new IOException("refusing to access workspace outside configured base");
        }
        return workspace;
    }

    static final class Lease implements AutoCloseable {
        private final Path leasePath;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Lease(Path leasePath, FileChannel channel, FileLock lock) {
            this.leasePath = leasePath;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            try {
                Files.deleteIfExists(leasePath);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            try {
                Files.deleteIfExists(leasePath.getParent());
            } catch (DirectoryNotEmptyException ignored) {
                // 其他活动工作区仍持有租约时保留租约目录。
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
}
