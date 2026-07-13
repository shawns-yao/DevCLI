package com.devcli.tool;

import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.skill.SkillContextBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryForkTest {

    @Test
    void forkCopiesMutableSkillContext(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent-skill");
        Path isolatedRoot = tempDir.resolve("isolated-skill");
        java.nio.file.Files.createDirectories(parentRoot);
        java.nio.file.Files.createDirectories(isolatedRoot);
        SkillContextBuffer parentBuffer = new SkillContextBuffer();
        parentBuffer.push("base", "base-body", List.of("read_file"));

        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(parentRoot.toString());
            parent.setSkillContextBuffer(parentBuffer);

            try (ToolRegistry fork = parent.forkForProject(isolatedRoot)) {
                fork.getSkillContextBuffer().push("fork-only", "fork-body", List.of("write_file"));
                fork.getSkillContextBuffer().drain();

                assertFalse(parent.getSkillContextBuffer().activeSkillNames().contains("fork-only"));
                assertTrue(parent.getSkillContextBuffer().snapshot().contains("base-body"));
                assertEquals(List.of("base"), parent.getSkillContextBuffer().activeSkillNames());
            }
        }
    }

    @Test
    void forksProjectScopedResourcesAndDynamicMcpTools(@TempDir Path tempDir) throws Exception {
        Path parentRoot = tempDir.resolve("parent");
        Path isolatedRoot = tempDir.resolve("isolated");
        java.nio.file.Files.createDirectories(parentRoot);
        java.nio.file.Files.createDirectories(isolatedRoot);

        try (ToolRegistry parent = new ToolRegistry()) {
            parent.setProjectPath(parentRoot.toString());
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    "demo", "echo", McpToolDescriptor.namespaced("demo", "echo"), "echo",
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
            parent.registerMcpToolOutput(descriptor, args -> ToolOutput.success("mcp-ok"));

            try (ToolRegistry fork = parent.forkForProject(isolatedRoot)) {
                assertEquals(isolatedRoot.toAbsolutePath().normalize().toString(), fork.getProjectPath());
                assertEquals("mcp-ok", fork.executeToolOutput("mcp__demo__echo", "{}").text());
                assertTrue(fork.hasTool("write_file"));
            }

            assertEquals(parentRoot.toAbsolutePath().normalize().toString(), parent.getProjectPath());
            assertEquals("mcp-ok", parent.executeToolOutput("mcp__demo__echo", "{}").text());
        }
    }
}
