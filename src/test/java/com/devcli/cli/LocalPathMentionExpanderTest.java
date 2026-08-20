package com.devcli.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalPathMentionExpanderTest {
    private static final Pattern STORED_PATH = Pattern.compile("stored_path=\\\"([^\\\"]+)\\\"");

    @TempDir
    Path tempDir;

    @Test
    void expandsLocalFileMentionIntoContextBlock() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "hello");
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        String expanded = expander.expand("读一下 @README.md");

        assertTrue(expanded.contains("@<README.md>"));
        assertTrue(expanded.contains("<file path=\"README.md\">"));
        assertTrue(expanded.contains("hello"));
    }

    @Test
    void expandsDirectoryMentionIntoDirectoryBlock() throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src").resolve("Main.java"), "class Main {}");
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        String expanded = expander.expand("列一下 @src");

        assertTrue(expanded.contains("<directory path=\"src\">"));
        assertTrue(expanded.contains("- Main.java"));
    }

    @Test
    void leavesMcpAndImageMentionsUntouched() {
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        assertEquals("@fs:file://README.md", expander.expand("@fs:file://README.md"));
        assertEquals("@image:<shot.png>", expander.expand("@image:<shot.png>"));
        assertEquals("@clipboard", expander.expand("@clipboard"));
    }

    @Test
    void refusesPathOutsideProjectRoot() throws Exception {
        Path outside = Files.writeString(tempDir.getParent().resolve("outside.txt"), "secret");
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        String expanded = expander.expand("读 @" + outside);

        assertFalse(expanded.contains("<file"));
        assertTrue(expanded.contains("@" + outside));
    }

    @Test
    void storesLargeFileAsEvidenceReferenceWhenRemainingTokenBudgetIsSmall() throws Exception {
        String content = "head\n" + "middle-evidence\n".repeat(2_000) + "tail\n";
        Files.writeString(tempDir.resolve("large.txt"), content);
        LocalPathMentionExpander expander = new LocalPathMentionExpander(tempDir);

        Method budgetAwareExpand;
        try {
            budgetAwareExpand = LocalPathMentionExpander.class
                    .getDeclaredMethod("expand", String.class, int.class);
        } catch (NoSuchMethodException e) {
            fail("LocalPathMentionExpander 必须提供预算感知的 expand(String, int)");
            return;
        }
        String expanded = (String) budgetAwareExpand.invoke(expander, "分析 @large.txt", 160);

        assertTrue(expanded.contains("<file_reference"));
        assertTrue(expanded.contains("original_path=\"large.txt\""));
        assertTrue(expanded.contains("evidence_required=\"true\""));
        assertTrue(expanded.contains("sha256=\""));
        assertFalse(expanded.contains("middle-evidence\nmiddle-evidence\nmiddle-evidence"));

        Matcher matcher = STORED_PATH.matcher(expanded);
        assertTrue(matcher.find());
        Path stored = tempDir.resolve(matcher.group(1)).normalize();
        assertTrue(stored.startsWith(tempDir));
        assertEquals(content, Files.readString(stored));
    }
}
