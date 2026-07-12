package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryProjectIsolationTest {

    @Test
    void largeToolOutputUsesOwningRegistryProjectPath(@TempDir Path tempDir) {
        Path firstProject = tempDir.resolve("first").toAbsolutePath().normalize();
        Path secondProject = tempDir.resolve("second").toAbsolutePath().normalize();
        ToolRegistry first = largeOutputRegistry();
        ToolRegistry second = largeOutputRegistry();
        try {
            first.setProjectPath(firstProject.toString());
            second.setProjectPath(secondProject.toString());

            ToolRegistry.ToolExecutionResult result = first.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("large_call", "large_tool", "{}")
            )).get(0);

            assertTrue(result.result().contains(firstProject.toString()));
            assertFalse(result.result().contains(secondProject.toString()));
        } finally {
            first.close();
            second.close();
        }
    }

    private static ToolRegistry largeOutputRegistry() {
        return new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                return "x".repeat(60_000);
            }
        };
    }
}
