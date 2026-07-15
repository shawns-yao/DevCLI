package com.devcli.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookConfigLoaderTest {

    @Test
    void projectDefinitionOverridesUserDefinitionById(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user.json");
        Path project = tempDir.resolve("project.json");
        Files.writeString(user, """
                {"hooks":[{
                  "id":"audit","event":"agent_start","tool":"list_dir",
                  "arguments":{"path":"user"}
                }]}
                """);
        Files.writeString(project, """
                {"hooks":[{
                  "id":"audit","name":"project hook","event":"turn_start",
                  "tool":"read_file","arguments":{"path":"project.txt"},
                  "failureMode":"required"
                }]}
                """);

        List<HookDefinition> hooks = HookConfigLoader.loadFiles(List.of(user, project));

        assertEquals(1, hooks.size());
        assertEquals("project hook", hooks.get(0).name());
        assertEquals(HookEvent.TURN_START, hooks.get(0).event());
        assertEquals(HookDefinition.FailureMode.REQUIRED, hooks.get(0).failureMode());
    }

    @Test
    void invalidProjectFileDoesNotDiscardValidUserHooks(@TempDir Path tempDir) throws Exception {
        Path user = tempDir.resolve("user.json");
        Path project = tempDir.resolve("project.json");
        Files.writeString(user, """
                {"hooks":[{
                  "id":"safe","event":"agent_start","tool":"list_dir",
                  "arguments":{"path":"."}
                }]}
                """);
        Files.writeString(project, "{not-json");

        List<HookDefinition> hooks = HookConfigLoader.loadFiles(List.of(user, project));

        assertEquals(1, hooks.size());
        assertEquals("safe", hooks.get(0).id());
    }

    @Test
    void unsupportedSchemaVersionIsIgnored(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("future.json");
        Files.writeString(file, """
                {"schemaVersion":2,"hooks":[{
                  "id":"future","event":"agent_start","tool":"list_dir"
                }]}
                """);

        assertTrue(HookConfigLoader.loadFiles(List.of(file)).isEmpty());
    }

    @Test
    void missingFilesProduceEmptyConfiguration(@TempDir Path tempDir) {
        assertTrue(HookConfigLoader.loadFiles(
                List.of(tempDir.resolve("missing.json"))).isEmpty());
    }
}
