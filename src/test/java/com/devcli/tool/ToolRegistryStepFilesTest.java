package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryStepFilesTest {

    @Test
    void collectsModifiedFilesPerLeaseStep(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.runWithResourceLease("step-1", () ->
                registry.executeTool("write_file", "{\"path\":\"a.txt\",\"content\":\"hello\"}"));
        registry.runWithResourceLease("step-2", () ->
                registry.executeTool("write_file", "{\"path\":\"b.txt\",\"content\":\"world\"}"));

        List<String> step1Files = registry.consumeStepModifiedFiles("step-1");
        assertEquals(1, step1Files.size());
        assertTrue(step1Files.get(0).endsWith("a.txt"), step1Files.toString());
        // consume 之后清空，不会重复归集
        assertTrue(registry.consumeStepModifiedFiles("step-1").isEmpty());
        // 步骤之间不串档
        List<String> step2Files = registry.consumeStepModifiedFiles("step-2");
        assertEquals(1, step2Files.size());
        assertTrue(step2Files.get(0).endsWith("b.txt"), step2Files.toString());
    }

    @Test
    void writesOutsideLeaseScopeAreNotCollected(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        registry.executeTool("write_file", "{\"path\":\"c.txt\",\"content\":\"plain\"}");

        assertTrue(registry.consumeStepModifiedFiles("step-1").isEmpty());
        assertTrue(registry.consumeStepModifiedFiles("").isEmpty());
        assertTrue(registry.consumeStepModifiedFiles(null).isEmpty());
    }
}
