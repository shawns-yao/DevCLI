package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {

    @TempDir
    Path projectRoot;

    @Test
    void isolatedProjectCanUseEditFile() throws Exception {
        Files.writeString(projectRoot.resolve("sample.txt"), "before");
        try (ToolRegistry registry = registry()) {
            ToolOutput output = registry.runWithToolAccess(
                    ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> registry.executeToolOutput("edit_file",
                            "{\"path\":\"sample.txt\",\"old_string\":\"before\",\"new_string\":\"after\"}"));

            assertTrue(output.isSuccess(), output.text());
            assertEquals("after", Files.readString(projectRoot.resolve("sample.txt")));
        }
    }

    @Test
    void editFileAllowsEmptyReplacementForDeletion() throws Exception {
        Files.writeString(projectRoot.resolve("sample.txt"), "keep-remove-keep");
        try (ToolRegistry registry = registry()) {
            ToolOutput output = registry.executeToolOutput("edit_file",
                    "{\"path\":\"sample.txt\",\"old_string\":\"remove-\",\"new_string\":\"\"}");

            assertTrue(output.isSuccess(), output.text());
            assertEquals("keep-keep", Files.readString(projectRoot.resolve("sample.txt")));
        }
    }

    @Test
    void noOpEditDoesNotAdvanceContextOrReportModifiedFile() throws Exception {
        Files.writeString(projectRoot.resolve("sample.txt"), "same");
        try (ToolRegistry registry = registry()) {
            long generation = registry.contextVersionLedger().currentGeneration();
            ToolOutput output = registry.runWithResourceLease("step-noop",
                    () -> registry.executeToolOutput("edit_file",
                            "{\"path\":\"sample.txt\",\"old_string\":\"same\",\"new_string\":\"same\"}"));

            assertTrue(output.isSuccess(), output.text());
            assertTrue(output.text().contains("无需写入"), output.text());
            assertEquals(generation, registry.contextVersionLedger().currentGeneration());
            assertTrue(registry.consumeStepModifiedFiles("step-noop").isEmpty());
        }
    }

    @Test
    void noOpWriteDoesNotAdvanceContextOrReportModifiedFile() throws Exception {
        Files.writeString(projectRoot.resolve("sample.txt"), "same");
        try (ToolRegistry registry = registry()) {
            long generation = registry.contextVersionLedger().currentGeneration();
            ToolOutput output = registry.runWithResourceLease("step-write-noop",
                    () -> registry.executeToolOutput("write_file",
                            "{\"path\":\"sample.txt\",\"content\":\"same\"}"));

            assertTrue(output.isSuccess(), output.text());
            assertTrue(output.text().contains("无需写入"), output.text());
            assertEquals(generation, registry.contextVersionLedger().currentGeneration());
            assertTrue(registry.consumeStepModifiedFiles("step-write-noop").isEmpty());
        }
    }

    @Test
    void oversizedFileIsRejectedBeforeTextDecoding() throws Exception {
        byte[] invalidUtf8 = new byte[5 * 1024 * 1024 + 1];
        java.util.Arrays.fill(invalidUtf8, (byte) 0xff);
        Files.write(projectRoot.resolve("large.bin"), invalidUtf8);

        try (ToolRegistry registry = registry()) {
            ToolOutput output = registry.executeToolOutput("edit_file",
                    "{\"path\":\"large.bin\",\"old_string\":\"x\",\"new_string\":\"y\"}");

            assertEquals(ToolStatus.REJECTED, output.status());
            assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode());
            assertTrue(output.text().contains("5MB"), output.text());
        }
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }
}
