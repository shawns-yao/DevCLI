package com.devcli.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PatchSetTest {

    @Test
    void existingDirectoryAtAddedFilePathIsConflict(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path occupied = project.resolve("occupied");
        Files.createDirectories(occupied);
        byte[] content = "replacement".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "occupied",
                PatchSet.ChangeType.ADD,
                PatchSet.MISSING_HASH,
                PatchSet.hash(content),
                content
        )));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertFalse(result.applied());
        assertEquals(List.of("occupied"), result.conflicts());
        assertTrue(Files.isDirectory(occupied), "冲突检测不得删除原有目录");
    }

    @Test
    void applyFailureReportsRollbackOutcome(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        byte[] parent = "parent-file".getBytes(StandardCharsets.UTF_8);
        byte[] child = "child-file".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(
                new PatchSet.FileChange("a", PatchSet.ChangeType.ADD,
                        PatchSet.MISSING_HASH, PatchSet.hash(parent), parent),
                new PatchSet.FileChange("a/child.txt", PatchSet.ChangeType.ADD,
                        PatchSet.MISSING_HASH, PatchSet.hash(child), child)
        ));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertFalse(result.applied());
        assertTrue(result.error().contains("a"), result.error());
        assertTrue(result.rollbackFailures().isEmpty(), result.rollbackFailures().toString());
        assertTrue(result.rollbackComplete());
        assertFalse(Files.exists(project.resolve("a")),
                "应用失败后已写入的父文件应被回滚");
    }

    @Test
    void symbolicLinkParentCannotEscapeProjectRoot(@TempDir Path tempDir) throws Exception {
        Path project = tempDir.resolve("project");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(project);
        Files.createDirectories(outside);
        Path link = project.resolve("linked");
        createDirectoryLink(link, outside);

        byte[] content = "escaped".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "linked/escape.txt",
                PatchSet.ChangeType.ADD,
                PatchSet.MISSING_HASH,
                PatchSet.hash(content),
                content
        )));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertFalse(result.applied());
        assertFalse(Files.exists(outside.resolve("escape.txt")),
                "PatchSet 不得经符号链接写出项目根目录");
    }

    @Test
    void rejectsChangeWhoseAfterHashDoesNotMatchContent(@TempDir Path tempDir) {
        Path project = tempDir.resolve("project");
        byte[] content = "actual".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "file.txt", PatchSet.ChangeType.ADD, PatchSet.MISSING_HASH,
                "not-the-content-hash", content)));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertFalse(result.applied());
        assertTrue(result.error().contains("afterHash"), result.error());
        assertFalse(Files.exists(project.resolve("file.txt")));
    }

    @Test
    void preservesExecutableFlagWhenApplyingChange(@TempDir Path tempDir) throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
                "当前文件系统不支持 POSIX 权限");
        Path project = Files.createDirectories(tempDir.resolve("project"));
        byte[] content = "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8);
        PatchSet patchSet = new PatchSet(List.of(new PatchSet.FileChange(
                "run.sh", PatchSet.ChangeType.ADD, PatchSet.MISSING_HASH,
                PatchSet.hash(content), content, true)));

        PatchSet.ApplyResult result = patchSet.apply(project);

        assertTrue(result.applied());
        assertTrue(Files.isExecutable(project.resolve("run.sh")));
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder(
                    "cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            assumeTrue(finished && process.exitValue() == 0,
                    "当前 Windows 环境无法创建目录联接");
            return;
        }
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "当前文件系统不支持创建符号链接: " + e.getMessage());
        }
    }
}
