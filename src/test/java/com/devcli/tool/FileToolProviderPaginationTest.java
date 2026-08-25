package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolProviderPaginationTest {

    private static final Pattern RESULT_REF = Pattern.compile("result_ref=([^,\\]]+)");

    @TempDir
    Path projectRoot;

    @BeforeEach
    void configureArtifactRoot() {
        System.setProperty("devcli.tool.results.root",
                projectRoot.resolve("runtime-tool-results").toString());
    }

    @AfterEach
    void clearArtifactRoot() {
        System.clearProperty("devcli.tool.results.root");
    }

    @Test
    void readFileAppliesDefaultCharacterLimitAndReturnsNextCursor() throws Exception {
        Files.writeString(projectRoot.resolve("large.txt"), "x".repeat(40_000));

        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());
            ToolOutput output = registry.executeToolOutput(
                    "read_file", "{\"path\":\"large.txt\"}");

            assertTrue(output.text().length() < 40_000, output.text());
            assertTrue(output.text().contains("next_cursor"), output.text());
            assertTrue(output.sideChannels().stream()
                    .anyMatch(channel -> channel.getClass().getSimpleName().equals("FileReadPage")));
        }
    }

    @Test
    void readFileSupportsInclusiveLineRange() throws Exception {
        Files.writeString(projectRoot.resolve("lines.txt"), "one\ntwo\nthree\nfour\n");

        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());
            ToolOutput output = registry.executeToolOutput(
                    "read_file",
                    "{\"path\":\"lines.txt\",\"start_line\":2,\"end_line\":3}");

            assertTrue(output.text().contains("two"), output.text());
            assertTrue(output.text().contains("three"), output.text());
            assertFalse(output.text().contains("one"), output.text());
            assertFalse(output.text().contains("four"), output.text());
        }
    }

    @Test
    void readFileSupportsCharacterOffsetAndLimit() throws Exception {
        Files.writeString(projectRoot.resolve("chars.txt"), "abcdefghij");

        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());
            ToolOutput output = registry.executeToolOutput(
                    "read_file",
                    "{\"path\":\"chars.txt\",\"offset\":2,\"limit\":3}");

            assertTrue(output.text().contains("cde"), output.text());
            assertFalse(output.text().contains("abcdef"), output.text());
            assertTrue(output.text().contains("next_cursor=5"), output.text());
        }
    }

    @Test
    void readToolResultRestoresTruncatedContentByCursor() {
        String original = "a".repeat(5_000) + "b".repeat(15_000);
        String managed = ToolResultSizeManager.process(
                "execute_command", "call_restore", projectRoot.toString(), false, original);
        String resultRef = extractResultRef(managed);

        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput page = registry.executeToolOutput(
                    "read_tool_result",
                    "{\"result_ref\":\"" + resultRef
                            + "\",\"offset\":5000,\"limit\":100}");

            assertTrue(page.text().contains("b".repeat(100)), page.text());
            assertTrue(page.text().contains("next_cursor=5100"), page.text());
        }
    }

    @Test
    void readToolResultDefaultPageDoesNotCreateNestedArtifact() {
        String original = "r".repeat(20_000);
        String managed = ToolResultSizeManager.process(
                "execute_command", "call_no_nested_ref", projectRoot.toString(), false, original);
        String resultRef = extractResultRef(managed);

        try (ToolRegistry registry = new ToolRegistry()) {
            ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation(
                            "restore_call", "read_tool_result",
                            "{\"result_ref\":\"" + resultRef + "\"}")
            )).get(0);

            assertTrue(result.result().contains("r".repeat(4_000)), result.result());
            assertFalse(result.sideChannels().stream()
                    .anyMatch(ToolResultArtifact.class::isInstance), result.result());
        }
    }

    @Test
    void readToolResultRejectsPathTraversal() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput(
                    "read_tool_result", "{\"result_ref\":\"../outside.txt\"}");

            assertTrue(output.status() == ToolStatus.REJECTED, output.text());
            assertTrue(output.errorCode() == ToolErrorCode.INVALID_ARGUMENTS, output.text());
        }
    }

    @Test
    void readToolResultRejectsTamperedArtifact() throws Exception {
        String managed = ToolResultSizeManager.process(
                "execute_command", "call_tamper", projectRoot.toString(), false,
                "v".repeat(20_000));
        String resultRef = extractResultRef(managed);
        Files.writeString(ToolResultArtifactStore.rootDirectory().resolve(resultRef), "tampered");

        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput(
                    "read_tool_result", "{\"result_ref\":\"" + resultRef + "\"}");

            assertTrue(output.status() == ToolStatus.ERROR, output.text());
            assertTrue(output.text().contains("SHA-256"), output.text());
        }
    }

    private static String extractResultRef(String managed) {
        var matcher = RESULT_REF.matcher(managed);
        assertTrue(matcher.find(), managed);
        return matcher.group(1);
    }
}
