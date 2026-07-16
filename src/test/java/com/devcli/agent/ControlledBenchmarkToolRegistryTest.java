package com.devcli.agent;

import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlledBenchmarkToolRegistryTest {
    @Test
    void projectForkShouldPreserveAllowedTools(@TempDir Path workspace) {
        try (ControlledBenchmarkToolRegistry registry =
                     new ControlledBenchmarkToolRegistry(Set.of("read_file", "write_file", "list_dir"));
             ToolRegistry fork = registry.forkForProject(workspace)) {
            Set<String> names = fork.getToolDefinitions().stream().map(tool -> tool.name()).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("read_file", "write_file", "list_dir"), names);
            assertFalse(names.contains("execute_command"));
            assertTrue(fork.executeToolOutput("execute_command", "{\"command\":\"echo forbidden\"}")
                    .text().contains("benchmark policy rejected tool"));
        }
    }
}
