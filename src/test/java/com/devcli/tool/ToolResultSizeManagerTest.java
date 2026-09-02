package com.devcli.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具结果尺寸治理单测。
 */
class ToolResultSizeManagerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetBudgetBetweenTests() {
        ToolResultSizeManager.resetTurnBudget();
        System.setProperty("devcli.tool.results.root",
                tempDir.resolve("runtime-tool-results").toString());
    }

    @AfterEach
    void clearResultRoot() {
        System.clearProperty("devcli.tool.results.root");
    }

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
        assertTrue(out.contains("result_ref"), "中等结果必须提供可恢复引用");
        assertTrue(out.contains("next_cursor"), "中等结果必须提供继续读取游标");
        assertTrue(hasStoredTextArtifact(), "中等结果完整原文必须落到运行时结果目录");
    }

    @Test
    void mediumResultKeepsHeadAndTailForDiagnosticContext() {
        String medium = "HEAD\n" + "middle\n".repeat(3_000) + "TAIL\n";

        String out = ToolResultSizeManager.process(
                "execute_command", "call_head_tail", tempDir.toString(), false, medium);

        assertTrue(out.startsWith("HEAD\n"), "预览必须保留结果头部");
        assertTrue(out.contains("TAIL\n"), "预览必须保留结果尾部，便于定位最终错误");
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
        assertTrue(out.contains("read_tool_result"), "应提示用专用工具读取完整内容");

        // 验证文件真的写到磁盘
        Path file = firstStoredTextArtifact();
        assertEquals(large, Files.readString(file), "落盘内容应为完整原文");
    }

    @Test
    void exactDuplicateLargeResultReusesEarlierArtifact() {
        String large = "duplicate-output-".repeat(5_000);
        String first = ToolResultSizeManager.process(
                "execute_command", "call_first", tempDir.toString(), false, large);
        String second = ToolResultSizeManager.process(
                "execute_command", "call_second", tempDir.toString(), false, large);

        assertTrue(first.contains("result_ref"), first);
        assertTrue(second.contains("重复工具结果已折叠"), second);
        assertTrue(second.contains("复用前一次结果"), second);
        assertTrue(second.contains("result_ref="), second);
        assertTrue(second.length() < first.length(), "重复结果应只保留引用");
    }

    @Test
    void readFileToolUsesRecoverableSizeManagement() {
        String huge = "a".repeat(100_000);
        String out = ToolResultSizeManager.process(
                "read_file", "call_3", tempDir.toString(), false, huge);
        assertNotEquals(huge, out, "read_file 不得绕过尺寸治理");
        assertTrue(out.contains("result_ref"), out);
        assertTrue(out.contains("read_tool_result"), out);
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
    void mediumMcpResultIncludesCollapseClassification() {
        String medium = "m".repeat(20_000);
        String out = ToolResultSizeManager.process(
                "mcp__github__list_issues", "call_mcp_medium", tempDir.toString(), false, medium);

        assertTrue(out.contains("工具结果折叠分类: INLINE_TRUNCATED"), out);
    }

    @Test
    void largeMcpResultIncludesCollapseClassification() {
        String large = "m".repeat(70_000);
        String out = ToolResultSizeManager.process(
                "mcp__github__list_issues", "call_mcp_large", tempDir.toString(), false, large);

        assertTrue(out.contains("工具结果折叠分类: PERSISTED_PREVIEW"), out);
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

        assertTrue(hasStoredTextArtifact(), "落盘文件应在受控运行时目录下");
    }

    @Test
    void persistFailureFallsBackToInlineTruncation() throws IOException {
        // 落盘失败必须降级为截断文本，绝不把成功结果变成错误（参考 dsh spill-policy 语义）
        Path occupied = tempDir.resolve("occupied.txt");
        Files.writeString(occupied, "occupied");
        System.setProperty("devcli.tool.results.root", occupied.toString());
        String large = "h".repeat(60_000);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_fail_persist", occupied.toString(), false, large);

        assertTrue(out.length() < large.length(), "落盘失败应降级为截断文本");
        assertTrue(out.startsWith("h".repeat(5_000)), "降级应保留头部");
        assertTrue(out.contains("已截断"), "降级文本应有截断提示");
    }

    @Test
    void nullResultReturnsEmptyString() {
        String out = ToolResultSizeManager.process(
                "execute_command", "call_9", tempDir.toString(), false, null);
        assertTrue(out.contains("执行完毕无输出"), "null 结果应注入空结果标记");
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
        // 截断提示应提供精确恢复工具，而不是要求重新执行原工具
        String medium = "g".repeat(15_000);
        String out = ToolResultSizeManager.process(
                "execute_command", "call_11", tempDir.toString(), false, medium);
        assertTrue(out.contains("read_tool_result"),
                "截断提示应提供精确恢复路径");
    }

    @Test
    void aggregateBudgetIsSharedWithParallelToolThreads() throws Exception {
        String medium = "p".repeat(20_000);
        for (int i = 0; i < 4; i++) {
            ToolResultSizeManager.process("execute_command", "call_parent_" + i, tempDir.toString(), false,
                    medium + i);
        }
        assertTrue(ToolResultSizeManager.turnUsedBudget() > ToolResultSizeManager.AGGREGATE_LIMIT_CHARS);

        FutureTask<String> task = new FutureTask<>(() ->
                ToolResultSizeManager.process("execute_command", "call_child", tempDir.toString(), false, medium));
        Thread thread = new Thread(task, "tool-result-size-test");
        thread.start();

        String out = task.get(5, TimeUnit.SECONDS);
        assertTrue(out.startsWith("p".repeat(2_500)), "并行工具线程应继承同轮聚合预算");
        assertTrue(out.contains("已截断 17500 字符"), "聚合超限后应降低单项截断长度");
    }

    private boolean hasStoredTextArtifact() {
        try {
            return Files.walk(tempDir.resolve("runtime-tool-results"))
                    .anyMatch(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".txt"));
        } catch (IOException e) {
            return false;
        }
    }

    private Path firstStoredTextArtifact() throws IOException {
        try (var paths = Files.walk(tempDir.resolve("runtime-tool-results"))) {
            return paths.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".txt"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
