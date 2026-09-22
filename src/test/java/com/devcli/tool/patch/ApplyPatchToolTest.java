package com.devcli.tool.patch;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * apply_patch 经完整工具链的端到端行为。
 *
 * <p>重点不在"能改文件"，而在"改不动的时候什么都不会发生"：定位失败、基线过期、
 * 路径越界、重复路径都必须整批不应用，并且仍然受租约、写白名单与受保护路径策略约束。</p>
 */
class ApplyPatchToolTest {

    @TempDir
    Path projectRoot;

    private static String patch(String... lines) {
        return String.join("\n", lines);
    }

    private static String args(String patchText) {
        String escaped = patchText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"patch\":\"" + escaped + "\"}";
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }

    private ToolOutput run(ToolRegistry registry, String... lines) {
        return registry.executeToolOutput(ApplyPatchTool.NAME, args(patch(lines)));
    }

    @Test
    void addsUpdatesDeletesAndMovesInOneCall() throws Exception {
        Files.writeString(projectRoot.resolve("keep.txt"), "keep\n");
        Files.writeString(projectRoot.resolve("gone.txt"), "gone\n");
        Files.writeString(projectRoot.resolve("old-name.txt"), "before\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Add File: added.txt",
                    "+hello",
                    "+world",
                    "*** Delete File: gone.txt",
                    "*** Update File: old-name.txt",
                    "*** Move to: new-name.txt",
                    "@@",
                    "-before",
                    "+after",
                    "*** End Patch");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("hello\nworld\n", Files.readString(projectRoot.resolve("added.txt")));
            assertFalse(Files.exists(projectRoot.resolve("gone.txt")));
            assertFalse(Files.exists(projectRoot.resolve("old-name.txt")));
            assertEquals("after\n", Files.readString(projectRoot.resolve("new-name.txt")));
            assertEquals("keep\n", Files.readString(projectRoot.resolve("keep.txt")));
            assertTrue(output.text().contains("A added.txt"), output.text());
            assertTrue(output.text().contains("D gone.txt"), output.text());
            assertEquals(List.of("added.txt", "gone.txt", "new-name.txt", "old-name.txt"),
                    output.modifiedResources().stream().sorted().toList());
        }
    }

    /** 移植官方 {@code test_update_file_hunk_interleaved_changes}。 */
    @Test
    void appliesInterleavedChunksInOneFile() throws Exception {
        Files.writeString(projectRoot.resolve("interleaved.txt"), "a\nb\nc\nd\ne\nf\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: interleaved.txt",
                    "@@",
                    " a",
                    "-b",
                    "+B",
                    "@@",
                    " c",
                    " d",
                    "-e",
                    "+E",
                    "@@",
                    " f",
                    "+g",
                    "*** End of File",
                    "*** End Patch");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("a\nB\nc\nd\nE\nf\ng\n",
                    Files.readString(projectRoot.resolve("interleaved.txt")));
        }
    }

    /** 移植官方 {@code test_pure_addition_chunk_followed_by_removal}：无原文行时在文件末尾插入。 */
    @Test
    void appendsWhenChunkHasNoOldLines() throws Exception {
        Files.writeString(projectRoot.resolve("panic.txt"), "line1\nline2\nline3\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: panic.txt",
                    "@@",
                    "+after-context",
                    "+second-line",
                    "@@",
                    " line1",
                    "-line2",
                    "-line3",
                    "+line2-replacement",
                    "*** End Patch");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("line1\nline2-replacement\nafter-context\nsecond-line\n",
                    Files.readString(projectRoot.resolve("panic.txt")));
        }
    }

    @Test
    void preservesCrlfLineEndings() throws Exception {
        Path file = projectRoot.resolve("windows.txt");
        Files.writeString(file, "one\r\ntwo\r\nthree\r\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: windows.txt",
                    "@@",
                    " one",
                    "-two",
                    "+changed",
                    "*** End Patch");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("one\r\nchanged\r\nthree\r\n", Files.readString(file));
        }
    }

    @Test
    void keepsFileWithoutTrailingNewlineUnchangedAtEnd() throws Exception {
        Path file = projectRoot.resolve("nonewline.txt");
        Files.writeString(file, "one\ntwo");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: nonewline.txt",
                    "@@",
                    "-one",
                    "+ONE",
                    "*** End Patch");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("ONE\ntwo", Files.readString(file), "补丁没要求就不该补上结尾换行");
        }
    }

    /**
     * 与官方实现的关键差异：官方逐个 hunk 写盘，失败只上报已提交部分；
     * 这里全部算完再交给 PatchSet，任何一处定位失败都整批不应用。
     */
    @Test
    void failedChunkLeavesEveryFileUntouched() throws Exception {
        Path first = projectRoot.resolve("a.txt");
        Path second = projectRoot.resolve("b.txt");
        Files.writeString(first, "old-a\n");
        Files.writeString(second, "old-b\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: a.txt",
                    "@@",
                    "-old-a",
                    "+new-a",
                    "*** Update File: b.txt",
                    "@@",
                    "-not-present",
                    "+new-b",
                    "*** End Patch");

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, output.errorCode());
            assertEquals("old-a\n", Files.readString(first), "前一个文件不得被部分应用");
            assertEquals("old-b\n", Files.readString(second));
        }
    }

    @Test
    void failedSecondChunkInSameFileLeavesItUntouched() throws Exception {
        Path file = projectRoot.resolve("a.txt");
        Files.writeString(file, "one\ntwo\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: a.txt",
                    "@@",
                    "-one",
                    "+ONE",
                    "@@",
                    "-missing",
                    "+x",
                    "*** End Patch");

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertEquals("one\ntwo\n", Files.readString(file));
        }
    }

    @Test
    void rejectsDuplicatePathInSamePatch() throws Exception {
        Files.writeString(projectRoot.resolve("a.txt"), "one\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: a.txt",
                    "@@",
                    "-one",
                    "+ONE",
                    "*** Update File: a.txt",
                    "@@",
                    "-ONE",
                    "+two",
                    "*** End Patch");

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertTrue(output.text().contains("重复声明"), output.text());
            assertEquals("one\n", Files.readString(projectRoot.resolve("a.txt")));
        }
    }

    @Test
    void rejectsUpdateForMissingFile() throws Exception {
        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: nope.txt",
                    "@@",
                    "-x",
                    "+y",
                    "*** End Patch");

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertTrue(output.text().contains("不存在"), output.text());
        }
    }

    @Test
    void addFileOverExistingFileKeepsBaselineGate() throws Exception {
        Files.writeString(projectRoot.resolve("a.txt"), "original\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Add File: a.txt",
                    "+replaced",
                    "*** End Patch");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("replaced\n", Files.readString(projectRoot.resolve("a.txt")));
        }
    }

    @Test
    void rejectsProtectedPathEvenThoughPatchIsWellFormed() throws Exception {
        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Add File: .env",
                    "+SECRET=1",
                    "*** End Patch");

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
            assertFalse(Files.exists(projectRoot.resolve(".env")));
        }
    }

    @Test
    void rejectsPathOutsideDelegatedWriteGlobs() throws Exception {
        Files.writeString(projectRoot.resolve("outside.txt"), "one\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = registry.runWithAllowedWritePaths(List.of("src/**"),
                    () -> run(registry,
                            "*** Begin Patch",
                            "*** Update File: outside.txt",
                            "@@",
                            "-one",
                            "+ONE",
                            "*** End Patch"));

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertEquals(ToolErrorCode.CAPABILITY_DENIED, output.errorCode(), output.text());
            assertEquals("one\n", Files.readString(projectRoot.resolve("outside.txt")));
        }
    }

    @Test
    void noOpPatchDoesNotAdvanceContextOrReportModifiedFile() throws Exception {
        Files.writeString(projectRoot.resolve("a.txt"), "same\n");

        try (ToolRegistry registry = registry()) {
            long generation = registry.contextVersionLedger().currentGeneration();
            ToolOutput output = registry.runWithResourceLease("step-noop",
                    () -> run(registry,
                            "*** Begin Patch",
                            "*** Update File: a.txt",
                            "@@",
                            "-same",
                            "+same",
                            "*** End Patch"));

            assertTrue(output.isSuccess(), output.text());
            assertTrue(output.text().contains("未产生实际变更"), output.text());
            assertEquals(generation, registry.contextVersionLedger().currentGeneration());
            assertTrue(registry.consumeStepModifiedFiles("step-noop").isEmpty());
        }
    }

    /**
     * 过期基线：本步骤读过之后文件被别的步骤改过。
     *
     * <p>补丁的上下文行仍然能匹配（改动落在别处），所以定位不会失败——必须由整文件版本闸门拦下，
     * 否则就是拿旧基线静默覆盖别人的改动。</p>
     */
    @Test
    void blocksStaleBaselineAfterAnotherStepWrites() throws Exception {
        Path target = projectRoot.resolve("Order.java");
        Files.writeString(target, "line1\nline2\n");

        try (ToolRegistry registry = registry()) {
            registry.runWithResourceLease("step_1",
                    () -> registry.executeToolOutput("read_file", "{\"path\":\"Order.java\"}"));

            try {
                registry.runWithResourceLease("step_2", () -> registry.executeToolOutput("write_file",
                        "{\"path\":\"Order.java\",\"content\":\"line1\\nline2\\nline3\\n\"}"));
            } finally {
                registry.releaseResourceLeases("step_2");
            }

            ToolOutput output = registry.runWithResourceLease("step_1", () -> run(registry,
                    "*** Begin Patch",
                    "*** Update File: Order.java",
                    "@@",
                    "-line1",
                    "+LINE1",
                    "*** End Patch"));

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertEquals(ToolErrorCode.STALE_CONTEXT, output.errorCode(), output.text());
            assertEquals("line1\nline2\nline3\n", Files.readString(target), "被拦下时不得落盘");
            registry.releaseResourceLeases("step_1");
        }
    }

    @Test
    void oversizedTargetIsRejectedBeforeDecoding() throws Exception {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 0xff);
        Files.write(projectRoot.resolve("large.bin"), oversized);

        try (ToolRegistry registry = registry()) {
            ToolOutput output = run(registry,
                    "*** Begin Patch",
                    "*** Update File: large.bin",
                    "@@",
                    "-x",
                    "+y",
                    "*** End Patch");

            assertEquals(ToolStatus.REJECTED, output.status(), output.text());
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, output.errorCode());
            assertTrue(output.text().contains("5MB"), output.text());
        }
    }

    @Test
    void isolatedScopeCanUseApplyPatch() throws Exception {
        Files.writeString(projectRoot.resolve("a.txt"), "one\n");

        try (ToolRegistry registry = registry()) {
            ToolOutput output = registry.runWithToolAccess(
                    ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> run(registry,
                            "*** Begin Patch",
                            "*** Update File: a.txt",
                            "@@",
                            "-one",
                            "+ONE",
                            "*** End Patch"));

            assertTrue(output.isSuccess(), output.text());
            assertEquals("ONE\n", Files.readString(projectRoot.resolve("a.txt")));
        }
    }
}
