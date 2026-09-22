package com.devcli.tool.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 官方 apply_patch 语法的解析契约。
 *
 * <p>用例移植自 Codex {@code parser.rs}，并补上本项目刻意收紧的两处：未知行报错带行号、
 * {@code *** Environment ID:} 显式拒绝（本工具只在本地项目内应用补丁）。</p>
 */
class ApplyPatchParserTest {

    private static String patch(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void requiresBeginAndEndMarkers() {
        assertTrue(assertThrows(PatchException.class, () -> ApplyPatchParser.parse("bad"))
                .getMessage().contains("*** Begin Patch"));
        assertTrue(assertThrows(PatchException.class,
                () -> ApplyPatchParser.parse(patch("*** Begin Patch", "bad")))
                .getMessage().contains("*** End Patch"));
    }

    @Test
    void parsesAddFile() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Add File: foo",
                "+hi",
                "*** End Patch"));

        assertEquals(List.of(new PatchHunk.AddFile("foo", List.of("hi"))), hunks);
    }

    @Test
    void parsesEmptyPatchAsNoHunks() {
        assertTrue(ApplyPatchParser.parse(patch("*** Begin Patch", "*** End Patch")).isEmpty());
    }

    @Test
    void rejectsUpdateFileWithoutChunks() {
        assertTrue(assertThrows(PatchException.class, () -> ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Update File: test.py",
                "*** End Patch"))).getMessage().contains("未包含任何修改块"));
    }

    @Test
    void parsesAddDeleteAndUpdateWithMove() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Add File: path/add.py",
                "+abc",
                "+def",
                "*** Delete File: path/delete.py",
                "*** Update File: path/update.py",
                "*** Move to: path/update2.py",
                "@@ def f():",
                "-    pass",
                "+    return 123",
                "*** End Patch"));

        assertEquals(List.of(
                new PatchHunk.AddFile("path/add.py", List.of("abc", "def")),
                new PatchHunk.DeleteFile("path/delete.py"),
                new PatchHunk.UpdateFile("path/update.py", "path/update2.py", List.of(
                        new PatchHunk.UpdateFileChunk("def f():",
                                List.of("    pass"), List.of("    return 123"), false)))
        ), hunks);
    }

    @Test
    void updateHunkCanBeFollowedByAnotherHunk() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Update File: file.py",
                "@@",
                "+line",
                "*** Add File: other.py",
                "+content",
                "*** End Patch"));

        assertEquals(List.of(
                new PatchHunk.UpdateFile("file.py", null, List.of(
                        new PatchHunk.UpdateFileChunk(null, List.of(), List.of("line"), false))),
                new PatchHunk.AddFile("other.py", List.of("content"))
        ), hunks);
    }

    @Test
    void updateWithoutExplicitContextHeaderParses() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Update File: file2.py",
                " import foo",
                "+bar",
                "*** End Patch"));

        assertEquals(List.of(new PatchHunk.UpdateFile("file2.py", null, List.of(
                new PatchHunk.UpdateFileChunk(null,
                        List.of("import foo"), List.of("import foo", "bar"), false))
        )), hunks);
    }

    @Test
    void preservesEndOfFileMarker() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Update File: file.txt",
                "@@",
                "+quux",
                "*** End of File",
                "",
                "*** End Patch"));

        assertEquals(List.of(new PatchHunk.UpdateFile("file.txt", null, List.of(
                new PatchHunk.UpdateFileChunk(null, List.of(), List.of("quux"), true))
        )), hunks);
    }

    @Test
    void acceptsHeredocWrappedPatch() {
        // 部分模型会把补丁写成 shell heredoc 形式，工具参数并不是 shell，需要剥掉包裹层。
        List<PatchHunk> hunks = ApplyPatchParser.parse(String.join("\n",
                "<<'EOF'",
                "*** Begin Patch",
                "*** Update File: file2.py",
                " import foo",
                "+bar",
                "*** End Patch",
                "EOF"));

        assertEquals(List.of(new PatchHunk.UpdateFile("file2.py", null, List.of(
                new PatchHunk.UpdateFileChunk(null,
                        List.of("import foo"), List.of("import foo", "bar"), false))
        )), hunks);
    }

    @Test
    void reportsLineNumberOfUnrecognizedLine() {
        PatchException error = assertThrows(PatchException.class, () -> ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Update File: file.txt",
                "@@",
                "not-a-change-line",
                "*** End Patch")));

        assertTrue(error.getMessage().contains("第 4 行"), error.getMessage());
        assertTrue(error.getMessage().contains("not-a-change-line"), error.getMessage());
    }

    @Test
    void rejectsEnvironmentIdPreamble() {
        PatchException error = assertThrows(PatchException.class, () -> ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Environment ID: remote",
                "*** Add File: hello.txt",
                "+hello",
                "*** End Patch")));

        assertTrue(error.getMessage().contains("Environment ID"), error.getMessage());
    }

    @Test
    void rejectsAddFileWithoutContent() {
        assertTrue(assertThrows(PatchException.class, () -> ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Add File: empty.txt",
                "*** End Patch"))).getMessage().contains("未包含任何内容行"));
    }

    @Test
    void rejectsMarkerWithoutPath() {
        assertTrue(assertThrows(PatchException.class, () -> ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Delete File:",
                "*** End Patch"))).getMessage().contains("缺少路径"));
    }

    @Test
    void toleratesCrlfAndBlankEdges() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(
                "\r\n*** Begin Patch\r\n*** Add File: a.txt\r\n+one\r\n*** End Patch\r\n\r\n");

        assertEquals(List.of(new PatchHunk.AddFile("a.txt", List.of("one"))), hunks);
    }

    @Test
    void bareContextlessChunkKeepsNullContext() {
        List<PatchHunk> hunks = ApplyPatchParser.parse(patch(
                "*** Begin Patch",
                "*** Update File: a.txt",
                "@@",
                "-old",
                "+new",
                "*** End Patch"));

        PatchHunk.UpdateFile update = (PatchHunk.UpdateFile) hunks.get(0);
        assertNull(update.chunks().get(0).changeContext());
    }
}
