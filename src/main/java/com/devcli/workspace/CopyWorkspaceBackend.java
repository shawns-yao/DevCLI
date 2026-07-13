package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
    private static final int MAX_DEFAULT_PARALLELISM = 8;

    private final int parallelism;

    public CopyWorkspaceBackend() {
        this(Math.max(1, Math.min(MAX_DEFAULT_PARALLELISM,
                Runtime.getRuntime().availableProcessors())));
    }

    CopyWorkspaceBackend(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        this.parallelism = parallelism;
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

        List<Path> sources = collectSources(root, base);
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
        try {
            while (completed < sources.size()) {
                while (submitted < sources.size() && submitted - completed < parallelism) {
                    Path source = sources.get(submitted++);
                    completion.submit(() -> copyFile(root, workspace, source));
                }
                FileSnapshot snapshot = await(completion);
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

    private static void shutdownAndAwait(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                interrupted = true;
                executor.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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

    private static List<Path> collectSources(Path root, Path workspaceBase) throws IOException {
        List<Path> sources = new ArrayList<>();
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
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)
                        && !WorkspacePathPolicy.isExcluded(root, workspaceBase, file)) {
                    sources.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sources;
    }

    private static FileSnapshot await(CompletionService<FileSnapshot> completion) throws IOException {
        try {
            return completion.take().get();
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

    record FileSnapshot(String relativePath, String hash) {
    }
}
