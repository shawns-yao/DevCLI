package com.devcli.mcp.mention;

import com.devcli.mcp.McpServerManager;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AtMentionExpanderTest {
    private static final Pattern STORED_PATH = Pattern.compile("stored_path=\\\"([^\\\"]+)\\\"");

    @Test
    void replacesMentionWithResourceBlock(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FakeManager(tempDir, "hello", "text/plain"));

        String expanded = expander.expand("看 @fs:file://README.md");

        assertTrue(expanded.contains("<resource server=\"fs\" uri=\"file://README.md\" mimeType=\"text/plain\">"));
        assertTrue(expanded.contains("hello"));
        assertFalse(expanded.contains("@fs:file://README.md"));
    }

    @Test
    void expandsMultipleMentionsFromRightToLeft(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FakeManager(tempDir, "body", "text/plain"));

        String expanded = expander.expand("@fs:file://a 和 @fs:file://b");

        assertEquals(2, count(expanded, "<resource server=\"fs\""));
    }

    @Test
    void leavesInputUnchangedWithoutMentions(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FakeManager(tempDir, "body", "text/plain"));

        assertEquals("普通输入", expander.expand("普通输入"));
    }

    @Test
    void insertsErrorBlockWhenReadFails(@TempDir Path tempDir) {
        AtMentionExpander expander = new AtMentionExpander(new FailingManager(tempDir));

        String expanded = expander.expand("@fs:file://missing");

        assertTrue(expanded.contains("<resource_error"));
        assertTrue(expanded.contains("boom"));
    }

    @Test
    void storesOversizeResourceAsEvidenceReferenceWhenBudgetIsSmall(@TempDir Path tempDir) throws Exception {
        String content = "resource-head\n" + "resource-evidence\n".repeat(2_000) + "resource-tail\n";
        Constructor<AtMentionExpander> constructor;
        Method budgetAwareExpand;
        try {
            constructor = AtMentionExpander.class
                    .getDeclaredConstructor(McpServerManager.class, Path.class);
            budgetAwareExpand = AtMentionExpander.class
                    .getDeclaredMethod("expand", String.class, int.class);
        } catch (NoSuchMethodException e) {
            fail("AtMentionExpander 必须支持项目级快照目录和剩余 Token 预算");
            return;
        }
        AtMentionExpander expander = constructor.newInstance(
                new FakeManager(tempDir, content, "text/plain"), tempDir);

        String expanded = (String) budgetAwareExpand.invoke(
                expander, "分析 @fs:file://large.txt", 160);

        assertTrue(expanded.contains("<file_reference"));
        assertTrue(expanded.contains("source_type=\"mcp_resource\""));
        assertTrue(expanded.contains("evidence_required=\"true\""));
        Matcher matcher = STORED_PATH.matcher(expanded);
        assertTrue(matcher.find());
        assertEquals(content, Files.readString(tempDir.resolve(matcher.group(1)).normalize()));
    }

    @Test
    void allocatesBudgetToMcpMentionsInTextOrder(@TempDir Path tempDir) {
        String content = "evidence ".repeat(50);
        AtMentionExpander expander = new AtMentionExpander(
                new FakeManager(tempDir, content, "text/plain"), tempDir);

        String expanded = expander.expand(
                "@fs:file://first.txt @fs:file://second.txt", 260);

        assertTrue(expanded.contains("<resource server=\"fs\" uri=\"file://first.txt\""));
        assertTrue(expanded.contains("<file_reference source_type=\"mcp_resource\" server=\"fs\""
                + " uri=\"file://second.txt\""));
    }

    private static int count(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static class FakeManager extends McpServerManager {
        private final String content;
        private final String mimeType;

        FakeManager(Path projectDir, String content, String mimeType) {
            super(new ToolRegistry(), projectDir);
            this.content = content;
            this.mimeType = mimeType;
        }

        @Override
        public ResourceReadResult readResourceForMention(String serverName, String uri) {
            return new ResourceReadResult(content, mimeType);
        }
    }

    private static class FailingManager extends McpServerManager {
        FailingManager(Path projectDir) {
            super(new ToolRegistry(), projectDir);
        }

        @Override
        public ResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
            throw new IOException("boom");
        }
    }
}
