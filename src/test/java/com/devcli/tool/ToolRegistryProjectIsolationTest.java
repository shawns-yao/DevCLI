package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryProjectIsolationTest {

    @Test
    void largeToolOutputsUseIsolatedRuntimeArtifacts(@TempDir Path tempDir) {
        Path firstProject = tempDir.resolve("first").toAbsolutePath().normalize();
        Path secondProject = tempDir.resolve("second").toAbsolutePath().normalize();
        Path runtimeRoot = tempDir.resolve("runtime-results").toAbsolutePath().normalize();
        System.setProperty(ToolResultArtifactStore.ROOT_PROPERTY, runtimeRoot.toString());
        ToolRegistry first = largeOutputRegistry();
        ToolRegistry second = largeOutputRegistry();
        try {
            first.setProjectPath(firstProject.toString());
            second.setProjectPath(secondProject.toString());

            ToolRegistry.ToolExecutionResult result = first.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("large_call", "large_tool", "{}")
            )).get(0);
            ToolRegistry.ToolExecutionResult secondResult = second.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("large_call", "large_tool", "{}")
            )).get(0);

            ToolResultArtifact firstArtifact = artifact(result);
            ToolResultArtifact secondArtifact = artifact(secondResult);
            assertTrue(Files.isRegularFile(runtimeRoot.resolve(firstArtifact.artifactRef())));
            assertTrue(Files.isRegularFile(runtimeRoot.resolve(secondArtifact.artifactRef())));
            assertNotEquals(firstArtifact.artifactRef(), secondArtifact.artifactRef());
            assertFalse(result.result().contains(firstProject.toString()));
            assertFalse(result.result().contains(secondProject.toString()));
        } finally {
            first.close();
            second.close();
            System.clearProperty(ToolResultArtifactStore.ROOT_PROPERTY);
        }
    }

    private static ToolResultArtifact artifact(ToolRegistry.ToolExecutionResult result) {
        return result.sideChannels().stream()
                .filter(ToolResultArtifact.class::isInstance)
                .map(ToolResultArtifact.class::cast)
                .findFirst()
                .orElseThrow();
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
