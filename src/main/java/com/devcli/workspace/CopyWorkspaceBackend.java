package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用普通文件复制物化隔离工作区的默认后端。
 */
public class CopyWorkspaceBackend implements WorkspaceBackend {
    static final String COPY_TIMEOUT_PROPERTY = "devcli.workspace.copy.timeout.seconds";
    static final String COPY_TIMEOUT_ENV = "DEVCLI_WORKSPACE_COPY_TIMEOUT_SECONDS";
    private static final int MAX_DEFAULT_PARALLELISM = 8;
    private static final long DEFAULT_COPY_TIMEOUT_SECONDS = 300;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final int parallelism;
    private final long copyTimeoutMillis;

    public CopyWorkspaceBackend() {
        this(Math.max(1, Math.min(MAX_DEFAULT_PARALLELISM,
                        Runtime.getRuntime().availableProcessors())),
                resolveCopyTimeoutMillis(System.getProperties(), System.getenv()));
    }

    CopyWorkspaceBackend(int parallelism) {
        this(parallelism, resolveCopyTimeoutMillis(System.getProperties(), System.getenv()));
    }

    CopyWorkspaceBackend(int parallelism, long copyTimeoutMillis) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        this.parallelism = parallelism;
        this.copyTimeoutMillis = Math.max(1, copyTimeoutMillis);
    }

    @Override
    public Materialization materialize(Path projectRoot, Path workspaceBase,
                                       Path workspacePath) throws IOException {
        Path root = WorkspacePathPolicy.normalize(projectRoot);
        Path base = WorkspacePathPolicy.normalize(workspaceBase);
        Path workspace = WorkspacePathPolicy.normalize(workspacePath);
        if (!workspace.startsWith(base) || workspace.equals(base)) {
            throw new IOException("invalid isolated workspace path");
        }

        List<Path> sources = WorkspaceSourceTree.collectRegularFiles(root, base);
        if (sources.isEmpty()) {
            return new Materialization(Map.of());
        }

        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task,
                    "devcli-workspace-copy-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, threadFactory);
        CompletionService<FileSnapshot> completion = new ExecutorCompletionService<>(executor);
        Map<String, String> baseline = new HashMap<>();
        int submitted = 0;
        int completed = 0;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(copyTimeoutMillis);
        try {
            while (completed < sources.size()) {
                while (submitted < sources.size() && submitted - completed < parallelism) {
                    Path source = sources.get(submitted++);
                    completion.submit(() -> copyFile(root, workspace, source));
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IOException("workspace copy timed out");
                }
                FileSnapshot snapshot = await(completion, remainingNanos);
                completed++;
                if (snapshot != null) {
                    baseline.put(snapshot.relativePath(), snapshot.hash());
                }
            }
            return new Materialization(baseline);
        } finally {
            shutdownAndAwait(executor);
        }
    }

    private static void shutdownAndAwait(ExecutorService executor) throws IOException {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("workspace copy workers did not terminate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("workspace copy shutdown interrupted", e);
        }
    }

    FileSnapshot copyFile(Path projectRoot, Path workspacePath, Path source) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(
                source, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(source)) {
            return null;
        }
        String relative = WorkspacePathPolicy.relativePath(projectRoot, source);
        Path target = workspacePath.resolve(relative).normalize();
        if (!target.startsWith(workspacePath)) {
            throw new IOException("workspace copy path escaped: " + relative);
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        return new FileSnapshot(relative, PatchSet.hash(target));
    }

    private static FileSnapshot await(CompletionService<FileSnapshot> completion,
                                      long timeoutNanos) throws IOException {
        try {
            java.util.concurrent.Future<FileSnapshot> future =
                    completion.poll(timeoutNanos, TimeUnit.NANOSECONDS);
            if (future == null) {
                throw new IOException("workspace copy timed out");
            }
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("workspace copy interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("workspace copy failed", cause);
        }
    }

    static long resolveCopyTimeoutMillis(java.util.Properties properties,
                                         Map<String, String> environment) {
        String value = properties == null ? null : properties.getProperty(COPY_TIMEOUT_PROPERTY);
        if (value == null || value.isBlank()) {
            value = environment == null ? null : environment.get(COPY_TIMEOUT_ENV);
        }
        if (value == null || value.isBlank()) {
            return TimeUnit.SECONDS.toMillis(DEFAULT_COPY_TIMEOUT_SECONDS);
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0
                    ? TimeUnit.SECONDS.toMillis(DEFAULT_COPY_TIMEOUT_SECONDS)
                    : Math.multiplyExact(seconds, 1000L);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return TimeUnit.SECONDS.toMillis(DEFAULT_COPY_TIMEOUT_SECONDS);
        }
    }

    record FileSnapshot(String relativePath, String hash) {
    }
}
