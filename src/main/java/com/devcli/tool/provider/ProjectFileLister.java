package com.devcli.tool.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 列出项目中应参与内容搜索的候选文件。
 *
 * <p>git 仓库优先使用 {@code git ls-files --cached --others --exclude-standard}：一条命令同时
 * 给出已跟踪与未跟踪但未被忽略的文件，忽略语义（嵌套 {@code .gitignore}、否定模式、全局
 * excludes）完全交给 git，避免在本项目内重复实现一份会与 git 行为漂移的规则。
 *
 * <p>非 git 目录、git 不可用或命令失败时回落到目录遍历，只用硬编码跳过目录兜底。
 * 显式把搜索范围收窄到被忽略目录时同样回落——此时用户意图明确，不应被忽略规则挡住。
 *
 * <p>子进程输出重定向到临时文件再读取，避免大仓库下 stdout 管道写满导致 {@code waitFor} 死锁。
 */
final class ProjectFileLister {

    /** 兜底跳过目录：git 不可用时挡住依赖、构建产物、缓存与临时目录。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".devcli", ".codegraph", ".idea", ".venv", "__pycache__",
            "node_modules", "target", "build", "dist", "out",
            ".next", ".nuxt", ".cache", ".pytest_cache", ".mypy_cache",
            "Temp", "Test", "Log", "logs", "cc", "live", "learn-agent");

    private static final int GIT_TIMEOUT_MILLIS = 15_000;

    private ProjectFileLister() {
    }

    /**
     * @param root  项目根，用于定位 git 仓库
     * @param scope 搜索范围，必须位于 {@code root} 之内
     * @return 候选文件绝对路径，已按可移植路径字符串排序；不包含目录
     */
    static List<Path> list(Path root, Path scope) {
        List<Path> viaGit = listViaGit(root, scope);
        if (viaGit != null) {
            return viaGit;
        }
        List<Path> files = new ArrayList<>();
        collect(scope, files);
        sort(files);
        return files;
    }

    /** git 不可用、非仓库或范围整体被忽略时返回 {@code null}，由调用方回落遍历。 */
    private static List<Path> listViaGit(Path root, Path scope) {
        List<Path> entries = gitFileList(root);
        if (entries == null) {
            return null;
        }
        List<Path> files = new ArrayList<>();
        for (Path entry : entries) {
            Path absolute = root.resolve(entry).normalize();
            if (absolute.startsWith(scope) && Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
                files.add(absolute);
            }
        }
        if (files.isEmpty() && hasAnyFile(scope)) {
            // 范围本身被 .gitignore 排除：尊重显式收窄的意图，改用遍历。
            return null;
        }
        sort(files);
        return files;
    }

    /** 执行 {@code git ls-files -z --cached --others --exclude-standard}；失败返回 {@code null}。 */
    private static List<Path> gitFileList(Path root) {
        Path stdout = null;
        Path stderr = null;
        Process process = null;
        try {
            stdout = Files.createTempFile("devcli-grep-files-", ".out");
            stderr = Files.createTempFile("devcli-grep-files-", ".err");
            process = new ProcessBuilder("git", "-C", root.toString(),
                    "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            if (!process.waitFor(GIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return splitZero(Files.readAllBytes(stdout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return null;
        } catch (Exception ignored) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return null;
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private static List<Path> splitZero(byte[] bytes) {
        List<Path> values = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != 0) {
                continue;
            }
            if (index > start) {
                String entry = new String(bytes, start, index - start, StandardCharsets.UTF_8);
                try {
                    values.add(Path.of(entry));
                } catch (RuntimeException ignored) {
                    // 非法路径条目跳过，不影响其余结果。
                }
            }
            start = index + 1;
        }
        return values;
    }

    /** 范围是文件时直接返回；是目录时递归收集，跳过 {@link #SKIP_DIRS}。 */
    private static void collect(Path scope, List<Path> out) {
        if (Files.isRegularFile(scope, LinkOption.NOFOLLOW_LINKS)) {
            out.add(scope);
            return;
        }
        collectInto(scope, out);
    }

    private static void collectInto(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException ignored) {
            return;
        }
        for (Path child : children) {
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                Path name = child.getFileName();
                if (name != null && SKIP_DIRS.contains(name.toString())) {
                    continue;
                }
                collectInto(child, out);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                out.add(child);
            }
        }
    }

    /** 范围下是否存在任何文件，用于判断“范围整体被忽略”还是“范围本来就是空的”。 */
    private static boolean hasAnyFile(Path scope) {
        if (Files.isRegularFile(scope, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        List<Path> probe = new ArrayList<>();
        collectBounded(scope, probe, 1);
        return !probe.isEmpty();
    }

    private static void collectBounded(Path dir, List<Path> out, int limit) {
        if (out.size() >= limit || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException ignored) {
            return;
        }
        for (Path child : children) {
            if (out.size() >= limit) {
                return;
            }
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                collectBounded(child, out, limit);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                out.add(child);
            }
        }
    }

    private static void sort(List<Path> files) {
        files.sort(Comparator.comparing(path ->
                path.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响搜索结果。
        }
    }
}
