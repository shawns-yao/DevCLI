package com.devcli.tool.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验证搜索候选文件的选取范围：git 仓库按 .gitignore 过滤，非 git 目录回落到跳过目录兜底，
 * 显式收窄到被忽略目录时不被忽略规则挡住。
 */
class ProjectFileListerTest {

    @TempDir
    Path root;

    @Test
    void gitRepositoryExcludesIgnoredFiles() throws Exception {
        assumeTrue(gitAvailable(), "需要 git 可执行文件");
        gitInit(root);
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        writeFile(root.resolve("src/Keep.java"));
        writeFile(root.resolve("ignored/Skip.java"));

        List<String> relative = relativePaths(ProjectFileLister.list(root, root));

        assertTrue(relative.contains("src/Keep.java"), relative.toString());
        assertFalse(relative.contains("ignored/Skip.java"),
                "被 .gitignore 排除的文件不应进入搜索范围: " + relative);
    }

    @Test
    void untrackedButNotIgnoredFilesAreListed() throws Exception {
        assumeTrue(gitAvailable(), "需要 git 可执行文件");
        gitInit(root);
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        writeFile(root.resolve("Fresh.java"));

        List<String> relative = relativePaths(ProjectFileLister.list(root, root));

        assertTrue(relative.contains("Fresh.java"),
                "未跟踪但未被忽略的文件必须可见: " + relative);
    }

    @Test
    void explicitScopeIntoIgnoredDirectoryStillSearches() throws Exception {
        assumeTrue(gitAvailable(), "需要 git 可执行文件");
        gitInit(root);
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Path ignoredDirectory = Files.createDirectories(root.resolve("ignored"));
        writeFile(ignoredDirectory.resolve("Skip.java"));

        List<String> relative = relativePaths(ProjectFileLister.list(root, ignoredDirectory));

        assertTrue(relative.contains("ignored/Skip.java"),
                "显式把范围收窄到被忽略目录时应尊重用户意图: " + relative);
    }

    @Test
    void nonGitDirectoryFallsBackToWalkWithSkipList() throws Exception {
        assumeFalse(insideGitRepository(root), "临时目录位于 git 仓库内，无法验证非 git 回落");
        writeFile(root.resolve("Temp/scratch.txt"));
        writeFile(root.resolve("node_modules/pkg/index.js"));
        writeFile(root.resolve("src/Main.java"));

        List<String> relative = relativePaths(ProjectFileLister.list(root, root));

        assertTrue(relative.contains("src/Main.java"), relative.toString());
        assertFalse(relative.contains("Temp/scratch.txt"),
                "兜底跳过目录应生效: " + relative);
        assertFalse(relative.contains("node_modules/pkg/index.js"),
                "兜底跳过目录应生效: " + relative);
    }

    @Test
    void resultsAreSortedByPortablePath() throws Exception {
        writeFile(root.resolve("b/B.java"));
        writeFile(root.resolve("a/A.java"));

        List<String> portable = ProjectFileLister.list(root, root).stream()
                .map(path -> path.toAbsolutePath().normalize().toString().replace('\\', '/'))
                .toList();
        List<String> sorted = new ArrayList<>(portable);
        sorted.sort(Comparator.naturalOrder());

        assertEquals(sorted, portable, "候选文件必须按可移植路径稳定排序");
    }

    @Test
    void fileScopeReturnsOnlyThatFile() throws Exception {
        Path target = root.resolve("src/Only.java");
        writeFile(target);
        writeFile(root.resolve("src/Other.java"));

        List<String> relative = relativePaths(ProjectFileLister.list(root, target));

        assertEquals(List.of("src/Only.java"), relative);
    }

    private List<String> relativePaths(List<Path> files) {
        Path base = root.toAbsolutePath().normalize();
        List<String> result = new ArrayList<>();
        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            try {
                result.add(base.relativize(absolute).toString().replace('\\', '/'));
            } catch (IllegalArgumentException ignored) {
                result.add(absolute.toString().replace('\\', '/'));
            }
        }
        return result;
    }

    private static void writeFile(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "content\n");
    }

    private static boolean gitAvailable() {
        return runQuietly(10, "git", "--version");
    }

    private static boolean insideGitRepository(Path directory) {
        return runQuietly(10, "git", "-C", directory.toString(),
                "rev-parse", "--is-inside-work-tree");
    }

    private static void gitInit(Path directory) throws Exception {
        assertTrue(runQuietly(30, "git", "init", "-q", directory.toString()), "git init 失败");
    }

    private static boolean runQuietly(int timeoutSeconds, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
