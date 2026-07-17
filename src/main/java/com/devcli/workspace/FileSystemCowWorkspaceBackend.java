package com.devcli.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 非 Git 目录的文件系统级写时复制后端。
 *
 * <p>Linux 使用 GNU cp 的强制 reflink；Windows 11 24H2 / Windows Server 2025 及以上版本
 * 在 ReFS 上使用系统原生复制操作。命令不可用、文件系统不支持克隆或克隆过程失败时，
 * 清理部分结果并自动回退到普通复制。后端只在平台明确保证写时复制时接受结果，不使用
 * 可能污染源目录的硬链接。
 */
public final class FileSystemCowWorkspaceBackend implements WorkspaceBackend {
    private static final int MAX_SOURCES_PER_COMMAND = 64;
    private static final int MAX_ARGUMENT_CHARS = 24_000;
    private static final int ERROR_PREVIEW_CHARS = 1_000;

    private final CloneStrategy cloneStrategy;
    private final CopyWorkspaceBackend fallback;
    private final long timeoutMillis;
    private final Set<Path> clonedWorkspaces = ConcurrentHashMap.newKeySet();

    public FileSystemCowWorkspaceBackend() {
        this(CommandCloneStrategy.detect(), new CopyWorkspaceBackend(),
                CopyWorkspaceBackend.resolveCopyTimeoutMillis(
                        System.getProperties(), System.getenv()));
    }

    FileSystemCowWorkspaceBackend(CloneStrategy cloneStrategy,
                                  CopyWorkspaceBackend fallback,
                                  long timeoutMillis) {
        this.cloneStrategy = cloneStrategy == null ? CloneStrategy.unsupported() : cloneStrategy;
        this.fallback = fallback == null ? new CopyWorkspaceBackend() : fallback;
        this.timeoutMillis = Math.max(1L, timeoutMillis);
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

        List<Path> sourceEntries = WorkspaceSourceTree.collectTopLevelEntries(root, base);
        if (sourceEntries.isEmpty()) {
            return new Materialization(Map.of());
        }
        if (!cloneStrategy.available(root, workspace)) {
            return fallback.materialize(root, base, workspace);
        }

        try {
            cloneStrategy.cloneEntries(sourceEntries, workspace, timeoutMillis);
            WorkspaceSourceTree.removeSymbolicLinks(workspace);
            Map<String, String> baseline = validateCloneAndSnapshot(root, base, workspace);
            clonedWorkspaces.add(workspace);
            return new Materialization(baseline);
        } catch (IOException | RuntimeException cloneFailure) {
            clonedWorkspaces.remove(workspace);
            try {
                WorkspaceSourceTree.resetWorkspace(base, workspace);
                return fallback.materialize(root, base, workspace);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(cloneFailure);
                throw fallbackFailure;
            }
        }
    }

    @Override
    public void cleanup(Path projectRoot, Path workspaceBase, Path workspacePath) throws IOException {
        clonedWorkspaces.remove(WorkspacePathPolicy.normalize(workspacePath));
        WorkspaceBackend.super.cleanup(projectRoot, workspaceBase, workspacePath);
    }

    boolean usedNativeClone(Path workspacePath) {
        return clonedWorkspaces.contains(WorkspacePathPolicy.normalize(workspacePath));
    }

    static boolean supportsWindowsBlockCloning(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String[] parts = version.trim().split("\\.");
        if (parts.length < 3) {
            return false;
        }
        try {
            return Integer.parseInt(parts[parts.length - 1]) >= 26100;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, String> validateCloneAndSnapshot(
            Path root, Path base, Path workspace) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        for (Path source : WorkspaceSourceTree.collectRegularFiles(root, base)) {
            String relative = WorkspacePathPolicy.relativePath(root, source);
            Path target = workspace.resolve(relative).normalize();
            if (!target.startsWith(workspace) || !Files.isRegularFile(target)
                    || Files.isSymbolicLink(target)) {
                throw new IOException("native workspace clone omitted file: " + relative);
            }
            String sourceHash = PatchSet.hash(source);
            if (!sourceHash.equals(PatchSet.hash(target))) {
                throw new IOException("native workspace clone changed file content: " + relative);
            }
            hashes.put(relative, sourceHash);
        }
        return hashes;
    }

    interface CloneStrategy {
        boolean available(Path projectRoot, Path workspacePath) throws IOException;

        void cloneEntries(List<Path> sourceEntries, Path workspacePath,
                          long timeoutMillis) throws IOException;

        static CloneStrategy unsupported() {
            return new CloneStrategy() {
                @Override
                public boolean available(Path projectRoot, Path workspacePath) {
                    return false;
                }

                @Override
                public void cloneEntries(List<Path> sourceEntries, Path workspacePath,
                                         long timeoutMillis) throws IOException {
                    throw new IOException("native clone is unavailable");
                }
            };
        }
    }

    private static final class WindowsReFsCloneStrategy implements CloneStrategy {
        @Override
        public boolean available(Path projectRoot, Path workspacePath) throws IOException {
            String sourceType = Files.getFileStore(projectRoot).type();
            String workspaceType = Files.getFileStore(workspacePath).type();
            return "refs".equalsIgnoreCase(sourceType)
                    && "refs".equalsIgnoreCase(workspaceType)
                    && FileSystemCowWorkspaceBackend.supportsWindowsBlockCloning(
                    System.getProperty("os.version", ""));
        }

        @Override
        public void cloneEntries(List<Path> sourceEntries, Path workspacePath,
                                 long timeoutMillis) throws IOException {
            for (Path source : sourceEntries) {
                Path target = workspacePath.resolve(source.getFileName().toString());
                if (Files.isDirectory(source)) {
                    copyDirectory(source, target);
                } else if (Files.isRegularFile(source) && !Files.isSymbolicLink(source)) {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }

        private static void copyDirectory(Path source, Path target) throws IOException {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                        Files.copy(file, target.resolve(source.relativize(file)),
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private record CommandCloneStrategy(Path executable, List<String> options)
            implements CloneStrategy {

        static CloneStrategy detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("linux")) {
                Path cp = findOnPath("cp");
                return cp == null
                        ? CloneStrategy.unsupported()
                        : new CommandCloneStrategy(cp,
                        List.of("--archive", "--reflink=always", "--"));
            }
            if (os.contains("win")) {
                return new WindowsReFsCloneStrategy();
            }
            return CloneStrategy.unsupported();
        }

        @Override
        public boolean available(Path projectRoot, Path workspacePath) {
            return executable != null && Files.isExecutable(executable);
        }

        @Override
        public void cloneEntries(List<Path> sourceEntries, Path workspacePath,
                                 long timeoutMillis) throws IOException {
            for (List<Path> batch : batches(sourceEntries)) {
                List<String> command = new ArrayList<>();
                command.add(executable.toString());
                command.addAll(options);
                for (Path source : batch) {
                    command.add(source.toString());
                }
                command.add(workspacePath.toString());
                run(command, workspacePath.getParent(), timeoutMillis);
            }
        }

        private static List<List<Path>> batches(List<Path> sources) {
            List<List<Path>> batches = new ArrayList<>();
            List<Path> current = new ArrayList<>();
            int chars = 0;
            for (Path source : sources) {
                int sourceChars = source.toString().length() + 1;
                if (!current.isEmpty() && (current.size() >= MAX_SOURCES_PER_COMMAND
                        || chars + sourceChars > MAX_ARGUMENT_CHARS)) {
                    batches.add(List.copyOf(current));
                    current.clear();
                    chars = 0;
                }
                current.add(source);
                chars += sourceChars;
            }
            if (!current.isEmpty()) {
                batches.add(List.copyOf(current));
            }
            return batches;
        }

        private static void run(List<String> command, Path tempDirectory,
                                long timeoutMillis) throws IOException {
            Path output = Files.createTempFile(tempDirectory, ".devcli-cow-", ".log");
            Process process = null;
            try {
                process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new IOException("native workspace clone timed out");
                }
                if (process.exitValue() != 0) {
                    String message = Files.readString(output, StandardCharsets.UTF_8).trim();
                    if (message.length() > ERROR_PREVIEW_CHARS) {
                        message = message.substring(0, ERROR_PREVIEW_CHARS);
                    }
                    throw new IOException("native workspace clone failed: " + message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (process != null) {
                    process.destroyForcibly();
                }
                throw new IOException("native workspace clone interrupted", e);
            } finally {
                Files.deleteIfExists(output);
            }
        }

        private static Path findOnPath(String executable) {
            String pathValue = System.getenv("PATH");
            if (pathValue == null || pathValue.isBlank()) {
                return null;
            }
            for (String entry : pathValue.split(java.io.File.pathSeparator)) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(entry).resolve(executable);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
