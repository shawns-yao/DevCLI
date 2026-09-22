package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGovernanceRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void directToolCallStillAppliesResultSizeGovernance() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "huge_read", "test", new com.fasterxml.jackson.databind.ObjectMapper()
                            .createObjectNode().put("type", "object"),
                    arguments -> "x".repeat(100_000),
                    ToolRegistry.ToolEffect.READ_ONLY));

            ToolOutput result = registry.executeToolOutput("huge_read", "{}");

            assertNotEquals(100_000, result.text().length());
            assertTrue(result.text().contains("result_ref"), result.text());
        }
    }

    @Test
    void largeListDirectoryResultsAreNotPassthrough() {
        String huge = "entry ".repeat(2_000);

        assertTrue(ToolResultSizeManager.classify("list_dir", false, huge)
                != ToolResultSizeManager.CollapseClassification.PASSTHROUGH);
    }

    @Test
    void externalFileChangeInvalidatesReadCache() throws Exception {
        Path file = tempDir.resolve("value.txt");
        Files.writeString(file, "before");
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(tempDir.toString());
            ToolRegistry.ToolExecutionResult first = registry.executeTools(java.util.List.of(
                    new ToolRegistry.ToolInvocation("read-1", "read_file",
                            "{\"path\":\"value.txt\"}"))).getFirst();
            assertTrue(first.result().contains("before"), first.result());

            Files.writeString(file, "after");

            ToolRegistry.ToolExecutionResult second = registry.executeTools(java.util.List.of(
                    new ToolRegistry.ToolInvocation("read-2", "read_file",
                            "{\"path\":\"value.txt\"}"))).getFirst();
            assertTrue(second.result().contains("after"), second.result());
        }
    }

    @Test
    void listDirectoryProviderBoundsLargeDirectories() throws Exception {
        for (int i = 0; i < 650; i++) {
            Files.createFile(tempDir.resolve(String.format("entry-%04d.txt", i)));
        }
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(tempDir.toString());

            ToolOutput result = registry.executeToolOutput("list_dir", "{\"path\":\".\"}");

            assertTrue(result.text().contains("目录内容已截断"), result.text());
        }
    }
}
