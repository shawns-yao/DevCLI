package com.devcli.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按主项目根目录串行化 PatchSet 提交和终态持久化。
 */
public final class ProjectCommitCoordinator {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ProjectCommitCoordinator() {
    }

    public static <T> T withProjectLock(Path projectRoot, CheckedSupplier<T> action) throws Exception {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        ReentrantLock lock = LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
