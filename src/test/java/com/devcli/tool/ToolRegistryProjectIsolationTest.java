package com.devcli.tool;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void isolatedRunRejectsForeignArtifactAndPathEscape(@TempDir Path tempDir) throws Exception {
        Path firstProject = Files.createDirectories(tempDir.resolve("first"));
        Path secondProject = Files.createDirectories(tempDir.resolve("second"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        Path runtimeRoot = tempDir.resolve("runtime-results").toAbsolutePath().normalize();
        System.setProperty(ToolResultArtifactStore.ROOT_PROPERTY, runtimeRoot.toString());
        try (ToolRegistry registry = new ToolRegistry();
             RunContext firstRun = CancellationContext.startRunContext(firstProject)) {
            registry.setProjectPath(firstProject.toString());
            ToolResultArtifactStore.StoredArtifact artifact =
                    ToolResultArtifactStore.store("call-first", "first-run-secret");

            try (RunContext secondRun = CancellationContext.startRunContext(secondProject)) {
                registry.setProjectPath(secondProject.toString());
                ToolOutput foreignArtifact = registry.runWithToolAccess(
                        ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                        () -> registry.executeToolOutput("read_tool_result", """
                                {"result_ref":"%s","offset":0,"limit":100}
                                """.formatted(artifact.ref())));
                assertEquals(ToolStatus.REJECTED, foreignArtifact.status());
                assertEquals(ToolErrorCode.CAPABILITY_DENIED, foreignArtifact.errorCode());

                ToolOutput escapedPath = registry.runWithToolAccess(
                        ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                        () -> registry.executeToolOutput("read_file", """
                                {"path":"../outside.txt"}
                                """));
                assertEquals(ToolStatus.REJECTED, escapedPath.status());
                assertEquals(ToolErrorCode.POLICY_DENIED, escapedPath.errorCode());
                assertTrue(Files.exists(outside));
            }
        } finally {
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
