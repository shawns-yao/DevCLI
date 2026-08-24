package com.devcli.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 监听项目外部写入，只累积相对路径；DIRTY 发布由检索线程同步完成。 */
final class ProjectIndexWatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProjectIndexWatcher.class);
    private final Path root;
    private final WatchService watchService;
    private final Map<WatchKey, Path> watchedDirectories = new ConcurrentHashMap<>();
    private final Set<String> pendingPaths = ConcurrentHashMap.newKeySet();
    private final Set<String> knownPaths = ConcurrentHashMap.newKeySet();
    private final Thread worker;
    private volatile boolean closed;

    ProjectIndexWatcher(Path projectRoot, VectorStore.IndexWatchSnapshot snapshot) throws IOException {
        this.root = projectRoot.toAbsolutePath().normalize();
        this.watchService = root.getFileSystem().newWatchService();
        try {
            registerTree(root);
            seedCurrentState(snapshot);
        } catch (IOException | RuntimeException e) {
            watchService.close();
            throw e;
        }
        this.worker = new Thread(this::runLoop, "devcli-index-watch");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    List<String> drainChanges() {
        if (pendingPaths.isEmpty()) return List.of();
        List<String> changes = new ArrayList<>(pendingPaths);
        pendingPaths.removeAll(changes);
        return List.copyOf(changes);
    }

    private void runLoop() {
        while (!closed) {
            try {
                WatchKey key = watchService.take();
                Path directory = watchedDirectories.get(key);
                if (directory != null) processEvents(key, directory);
                if (!key.reset()) watchedDirectories.remove(key);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!closed) log.warn("项目索引文件监听失败: {}", e.getMessage());
            }
        }
    }

    private void processEvents(WatchKey key, Path directory) throws IOException {
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                reconcileAllFiles();
                continue;
            }
            if (!(event.context() instanceof Path relative)) continue;
            Path changed = directory.resolve(relative).toAbsolutePath().normalize();
            if (!changed.startsWith(root)) continue;
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                if (!CodeIndexPathPolicy.isExcludedDirectory(changed)) {
                    registerTree(changed);
                    markCurrentFiles(changed);
                }
                continue;
            }
            if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                markDeletedPath(changed);
            } else if (CodeIndexPathPolicy.isIndexableFile(changed)) {
                markPending(changed);
            }
        }
    }

    private void registerTree(Path start) throws IOException {
        if (!Files.isDirectory(start)) return;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                if (!directory.equals(root) && CodeIndexPathPolicy.isExcludedDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = directory.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchedDirectories.put(key, directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void seedCurrentState(VectorStore.IndexWatchSnapshot snapshot) throws IOException {
        if (snapshot == null || !Files.isDirectory(root)) return;
        Set<String> remainingIndexed = ConcurrentHashMap.newKeySet();
        remainingIndexed.addAll(snapshot.indexedPaths());
        Set<String> currentPaths = ConcurrentHashMap.newKeySet();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return !directory.equals(root) && CodeIndexPathPolicy.isExcludedDirectory(directory)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!CodeIndexPathPolicy.isIndexableFile(file)) return FileVisitResult.CONTINUE;
                String relative = relativePath(file);
                currentPaths.add(relative);
                boolean indexed = remainingIndexed.remove(relative);
                if (!indexed || attrs.lastModifiedTime().toMillis() > snapshot.indexUpdatedAtMillis()) {
                    pendingPaths.add(relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        pendingPaths.addAll(remainingIndexed);
        knownPaths.addAll(currentPaths);
    }

    private void markCurrentFiles(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return !directory.equals(root) && CodeIndexPathPolicy.isExcludedDirectory(directory)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (CodeIndexPathPolicy.isIndexableFile(file)) markPending(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void markPending(Path file) {
        String relative = relativePath(file);
        pendingPaths.add(relative);
        knownPaths.add(relative);
    }

    void markDeletedPath(Path path) {
        String relative = relativePath(path);
        String prefix = relative.endsWith("/") ? relative : relative + "/";
        List<String> deleted = knownPaths.stream()
                .filter(known -> known.equals(relative) || known.startsWith(prefix))
                .toList();
        if (deleted.isEmpty() && CodeIndexPathPolicy.isIndexableFile(path)) {
            pendingPaths.add(relative);
            knownPaths.remove(relative);
            return;
        }
        pendingPaths.addAll(deleted);
        knownPaths.removeAll(deleted);
    }

    private void reconcileAllFiles() throws IOException {
        Set<String> currentPaths = ConcurrentHashMap.newKeySet();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                return !directory.equals(root) && CodeIndexPathPolicy.isExcludedDirectory(directory)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (CodeIndexPathPolicy.isIndexableFile(file)) currentPaths.add(relativePath(file));
                return FileVisitResult.CONTINUE;
            }
        });
        pendingPaths.addAll(currentPaths);
        knownPaths.stream()
                .filter(known -> !currentPaths.contains(known))
                .forEach(pendingPaths::add);
        knownPaths.clear();
        knownPaths.addAll(currentPaths);
    }

    private String relativePath(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        watchedDirectories.clear();
        pendingPaths.clear();
        knownPaths.clear();
    }
}
