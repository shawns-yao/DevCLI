package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具结果尺寸治理单测。
 */
class ToolResultSizeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void smallResultPassesThroughUnchanged() {
        // ≤ 5K 字符直接原样返回
        String small = "x".repeat(1_000);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_1", tempDir.toString(), false, small);
        assertEquals(small, out);
    }

    @Test
    void mediumResultIsTruncatedToInlineThreshold() {
        // 5K~50K 区间：尾部截断到 5K，附带剩余字符提示
        String medium = "y".repeat(20_000);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_2", tempDir.toString(), false, medium);
        assertTrue(out.length() < medium.length(), "应该被截断");
        assertTrue(out.startsWith("y".repeat(5_000)), "保留段应当是头部 5K 字符");
        assertTrue(out.contains("已截断"), "应有截断提示");
        assertTrue(out.contains("15000 字符"), "提示应说明丢了多少字符");
        assertTrue(out.contains("共 20000 字符"), "提示应说明总字符数");
    }

    @Test
    void largeResultIsPersistedAndPreviewed() throws IOException {
        // > 50K 完整落盘，messages 只放预览 + 路径
        String large = "z".repeat(80_000);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_huge_42", tempDir.toString(), false, large);

        // 预览部分
        assertTrue(out.startsWith("z".repeat(1_500)), "预览应为前 1500 字符");
        assertTrue(out.contains("[工具输出过大已落盘 80000 字符"), "应有落盘提示");
        assertTrue(out.contains("read_file"), "应提示用 read_file 读取完整内容");

        // 验证文件真的写到磁盘
        Path outDir = tempDir.resolve(ToolResultSizeManager.OUTPUTS_DIR)
                .resolve(ToolResultSizeManager.currentSessionId());
        assertTrue(Files.isDirectory(outDir), "落盘目录应存在");
        Path file = outDir.resolve("call_huge_42.txt");
        assertTrue(Files.isRegularFile(file), "落盘文件应存在");
        assertEquals(large, Files.readString(file), "落盘内容应为完整原文");
    }

    @Test
    void readFileToolBypassesSizeManagement() {
        // read_file 在白名单里：再大也不应被截断（避免 read→file→read 死循环）
        String huge = "a".repeat(100_000);
        String out = ToolResultSizeManager.process(
                "read_file", "call_3", tempDir.toString(), false, huge);
        assertEquals(huge, out, "read_file 结果不应被治理");
    }

    @Test
    void listDirToolBypassesSizeManagement() {
        // list_dir 也在白名单：目录树短结构化输出不应被截断
        String dirTree = "drwx ".repeat(1_500); // ~7.5K，正常情况会被截断
        String out = ToolResultSizeManager.process(
                "list_dir", "call_4", tempDir.toString(), false, dirTree);
        assertEquals(dirTree, out, "list_dir 结果不应被治理");
    }

    @Test
    void resultWithImagesIsNeverManaged() {
        // 含图片的结果整体跳过（图片 part 不能截断，破坏会损坏视觉信息）
        String text = "b".repeat(80_000);
        String out = ToolResultSizeManager.process(
                "web_fetch", "call_5", tempDir.toString(), true, text);
        assertEquals(text, out, "含图片的工具结果应跳过尺寸治理");
    }

    @Test
    void mcpToolsAreManagedByDefault() {
        // MCP 动态工具（mcp__server__tool）默认进入尺寸治理
        String big = "m".repeat(70_000);
        String out = ToolResultSizeManager.process(
                "mcp__github__list_issues", "call_6", tempDir.toString(), false, big);
        assertNotEquals(big, out, "MCP 工具默认应被治理");
        assertTrue(out.contains("[工具输出过大已落盘"), "应触发落盘");
    }

    @Test
    void exactlyAtInlineThresholdPassesThrough() {
        // 5000 字符正好等于 INLINE_THRESHOLD_CHARS：边界 case，不截断
        String boundary = "c".repeat(ToolResultSizeManager.INLINE_THRESHOLD_CHARS);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_7", tempDir.toString(), false, boundary);
        assertEquals(boundary, out, "正好 5000 字符应原样返回");
    }

    @Test
    void exactlyAtPersistThresholdIsTruncatedNotPersisted() {
        // 50000 字符正好等于 PERSIST_THRESHOLD_CHARS：边界 case，走截断（≤ 阈值）
        String boundary = "d".repeat(ToolResultSizeManager.PERSIST_THRESHOLD_CHARS);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_8", tempDir.toString(), false, boundary);
        assertTrue(out.length() < boundary.length(), "应被截断");
        assertTrue(out.contains("已截断 45000 字符"), "应保留 5K，截断 45K");
        assertFalse(out.contains("[工具输出过大已落盘"), "正好 50K 应走截断不走落盘");
    }

    @Test
    void persistedFileNameSanitizesUnsafeCharacters() throws IOException {
        // tool_use_id 含路径分隔符 / 控制字符：必须安全化否则破坏目录结构
        String big = "e".repeat(60_000);
        String unsafeId = "call/with\\slashes:and*chars";
        ToolResultSizeManager.process(
                "execute_command", unsafeId, tempDir.toString(), false, big);

        Path outDir = tempDir.resolve(ToolResultSizeManager.OUTPUTS_DIR)
                .resolve(ToolResultSizeManager.currentSessionId());
        // 不应在 outDir 之外创建目录
        assertTrue(Files.list(outDir)
                .anyMatch(p -> p.getFileName().toString().endsWith(".txt")),
                "落盘文件应在合法目录下");
    }

    @Test
    void nullResultReturnsEmptyString() {
        // 防御 null
        assertEquals("", ToolResultSizeManager.process(
                "execute_command", "call_9", tempDir.toString(), false, null));
    }

    @Test
    void revertTurnToolBypassesSizeManagement() {
        // revert_turn 在白名单
        String medium = "f".repeat(10_000);
        String out = ToolResultSizeManager.process(
                "revert_turn", "call_10", tempDir.toString(), false, medium);
        assertEquals(medium, out);
    }

    @Test
    void truncationProducesGreppableHint() {
        // 截断提示应建议 LLM 用 search_code/grep 进一步过滤
        String medium = "g".repeat(15_000);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_11", tempDir.toString(), false, medium);
        assertTrue(out.contains("search_code") || out.contains("grep"),
                "截断提示应教 LLM 怎么避免再次撞阈值");
    }
}
