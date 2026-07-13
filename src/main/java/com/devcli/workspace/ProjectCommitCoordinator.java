package com.devcli.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按主项目根目录串行化 PatchSet 提交和终态持久化。
 * JVM 内使用公平锁保持线程顺序，进程间使用 JDK FileLock 共享同一临界区。
 */
public final class ProjectCommitCoordinator {
    static final String LOCK_DIR_PROPERTY = "devcli.project.commit.lock.dir";
    static final String LOCK_DIR_ENV = "DEVCLI_PROJECT_COMMIT_LOCK_DIR";
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ProjectCommitCoordinator() {
    }

    public static <T> T withProjectLock(Path projectRoot, CheckedSupplier<T> action) throws Exception {
        Path root = normalizeProjectRoot(projectRoot);
        ReentrantLock lock = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock(true));
        lock.lockInterruptibly();
        try {
            if (lock.getHoldCount() > 1) {
                return action.get();
            }
            Path lockFile = lockFile(root, System.getProperties(), System.getenv());
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } finally {
            lock.unlock();
        }
    }

    static Path lockFile(Path projectRoot, java.util.Properties properties,
                         Map<String, String> environment) {
        String configured = properties.getProperty(LOCK_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = environment.get(LOCK_DIR_ENV);
        }
        Path base = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".devcli", "locks", "project-commit")
                : Path.of(configured.trim());
        String digest = sha256(normalizeProjectRoot(projectRoot).toString());
        return base.toAbsolutePath().normalize().resolve(digest + ".lck");
    }

    private static Path normalizeProjectRoot(Path projectRoot) {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        try {
            return Files.exists(root) ? root.toRealPath() : root;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot resolve project root: " + root, e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
