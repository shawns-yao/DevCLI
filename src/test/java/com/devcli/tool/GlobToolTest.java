package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class GlobToolTest {
    @TempDir Path root;

    @Test
    void matchesZeroAndMultipleDirectoryLevelsAndReportsTruncation() throws Exception {
        Files.createDirectories(root.resolve("src/nested"));
        Files.writeString(root.resolve("RootTest.java"), "");
        Files.writeString(root.resolve("src/Test.java"), "");
        Files.writeString(root.resolve("src/nested/OtherTest.java"), "");
        try (var tools = tools()) {
            String found = tools.executeToolOutput("glob", "{\"pattern\":\"**/*Test.java\"}").text();
            assertTrue(found.contains("RootTest.java"), found);
            assertTrue(found.contains("src/Test.java"), found);
            assertTrue(found.contains("src/nested/OtherTest.java"), found);
            String source = tools.executeToolOutput("glob", "{\"pattern\":\"src/**/*.java\"}").text();
            assertTrue(source.contains("src/Test.java"), source);
            assertTrue(source.contains("src/nested/OtherTest.java"), source);
            assertTrue(tools.executeToolOutput("glob", "{\"pattern\":\"**/*.java\",\"limit\":1}").text().contains("截断"));
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS,
                    tools.executeToolOutput("glob", "{\"pattern\":\"[\"}").errorCode());
            assertFalse(tools.executeToolOutput("glob", "{\"pattern\":\"**\",\"path\":\"../\"}").isSuccess());
            assertFalse(tools.hasTool("search_code"));
        }
    }

    @Test
    void sharesGitIgnoreRulesAndExplicitScopeWithGrep() throws Exception {
        Process git = new ProcessBuilder("git", "init", root.toString()).redirectErrorStream(true).start();
        assertEquals(0, git.waitFor());
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve("ignored/Hidden.java"), "");
        Files.writeString(root.resolve("Visible.java"), "");
        try (var tools = tools()) {
            String found = tools.executeToolOutput("glob", "{\"pattern\":\"**/*.java\"}").text();
            assertTrue(found.contains("Visible.java"), found);
            assertFalse(found.contains("Hidden.java"), found);
            assertTrue(tools.executeToolOutput("glob", "{\"pattern\":\"**/*.java\",\"path\":\"ignored\"}")
                    .text().contains("ignored/Hidden.java"));
        }
    }

    private ToolRegistry tools() {
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(root.toString());
        return tools;
    }
}
