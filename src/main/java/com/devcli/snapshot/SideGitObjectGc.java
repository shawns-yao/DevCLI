package com.devcli.snapshot;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class SideGitObjectGc {
    private static final Pattern FANOUT = Pattern.compile("[0-9a-fA-F]{2}");
    private static final Pattern OBJECT_FILE = Pattern.compile("[0-9a-fA-F]{38}");

    Result collect(Path gitDir, Duration maxDuration) throws IOException {
        long deadline = System.nanoTime() + Math.max(1, maxDuration.toNanos());
        Set<ObjectId> reachable;
        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .build()) {
            reachable = reachableObjects(repository, deadline);
        }
        Path objects = gitDir.resolve("objects");
        int scanned = 0;
        int deleted = 0;
        int failedDeletes = 0;
        boolean timedOut = false;
        if (!Files.isDirectory(objects)) {
            return new Result(scanned, deleted, failedDeletes, false);
        }
        try (var directories = Files.list(objects)) {
            for (Path directory : directories.toList()) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    break;
                }
                String prefix = directory.getFileName().toString();
                if (!Files.isDirectory(directory) || !FANOUT.matcher(prefix).matches()) {
                    continue;
                }
                try (var files = Files.list(directory)) {
                    for (Path file : files.toList()) {
                        if (System.nanoTime() >= deadline) {
                            timedOut = true;
                            break;
                        }
                        String suffix = file.getFileName().toString();
                        if (!Files.isRegularFile(file)
                                || !OBJECT_FILE.matcher(suffix).matches()) {
                            continue;
                        }
                        scanned++;
                        ObjectId id = ObjectId.fromString(prefix + suffix);
                        if (!reachable.contains(id)) {
                            if (deleteWithRetry(file, deadline)) {
                                deleted++;
                            } else {
                                failedDeletes++;
                            }
                        }
                    }
                }
                try (var remaining = Files.list(directory)) {
                    if (remaining.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        }
        return new Result(scanned, deleted, failedDeletes, timedOut);
    }

    private static boolean deleteWithRetry(Path file, long deadline) throws IOException {
        IOException lastFailure = null;
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("dos")) {
                Files.setAttribute(file, "dos:readonly", false);
            }
        } catch (IOException ignored) {
            // 非 DOS 文件系统或属性不可写时直接尝试删除。
        }
        long retryDeadline = Math.min(deadline,
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250));
        while (System.nanoTime() < retryDeadline) {
            try {
                return Files.deleteIfExists(file);
            } catch (java.nio.file.FileSystemException e) {
                lastFailure = e;
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Side-Git GC 删除对象被中断", interrupted);
                }
            }
        }
        return lastFailure == null && !Files.exists(file);
    }

    private Set<ObjectId> reachableObjects(Repository repository, long deadline)
            throws IOException {
        Set<ObjectId> reachable = new HashSet<>();
        try (ObjectWalk walk = new ObjectWalk(repository)) {
            for (Ref ref : repository.getRefDatabase().getRefs()) {
                ObjectId id = ref.getObjectId();
                if (id != null) {
                    walk.markStart(walk.parseAny(id));
                }
            }
            RevCommit commit;
            while ((commit = walk.next()) != null) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Side-Git GC 计算可达对象超时");
                }
                reachable.add(commit.copy());
            }
            RevObject object;
            while ((object = walk.nextObject()) != null) {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Side-Git GC 计算可达对象超时");
                }
                reachable.add(object.copy());
            }
        }
        return reachable;
    }

    record Result(int scannedLooseObjects, int deletedLooseObjects,
                  int failedDeletes, boolean timedOut) {
    }
}
