package com.devcli.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.devcli.runtime.event.RunEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvalDriverTest {

    @Test
    void dryRunValidatesDatasetAndWritesManifest(@TempDir Path tempDir) throws Exception {
        Path fixture = tempDir.resolve("fixture");
        Files.createDirectories(fixture);
        Files.writeString(fixture.resolve("README.md"), "fixture\n", StandardCharsets.UTF_8);
        Path tasks = tempDir.resolve("tasks.jsonl");
        Files.writeString(tasks,
                "{\"id\":\"read-only\",\"prompt\":\"inspect\","
                        + "\"fixture\":\"fixture\",\"checks\":["
                        + "{\"type\":\"file_exists\",\"path\":\"README.md\"},"
                        + "{\"type\":\"no_changes\"}]}\n",
                StandardCharsets.UTF_8);
        Path output = tempDir.resolve("run");

        AgentEvalDriver.main(new String[]{tasks.toString(), output.toString(), "--dry-run"});

        JsonNode manifest = AgentEvalDriver.JSON.readTree(
                Files.readString(output.resolve("manifest.json")));
        JsonNode summary = AgentEvalDriver.JSON.readTree(
                Files.readString(output.resolve("summary.json")));
        assertEquals("定向测试", manifest.path("test_kind").asText());
        assertEquals(1, manifest.path("sample_count").asInt());
        assertEquals("dry_run", summary.path("results").get(0).path("status").asText());
    }

    @Test
    void rejectsUnsafeCheckPath(@TempDir Path tempDir) throws Exception {
        Path tasks = tempDir.resolve("tasks.jsonl");
        Files.writeString(tasks,
                "{\"id\":\"unsafe\",\"prompt\":\"inspect\","
                        + "\"fixture\":\".\",\"checks\":["
                        + "{\"type\":\"file_exists\",\"path\":\"../secret\"}]}\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AgentEvalDriver.loadTasks(tasks));
        assertTrue(error.getMessage().contains("unsafe relative path"));
    }

    @Test
    void rejectsDuplicateIds(@TempDir Path tempDir) throws Exception {
        Path tasks = tempDir.resolve("tasks.jsonl");
        String row = "{\"id\":\"same\",\"prompt\":\"inspect\","
                + "\"fixture\":\".\",\"checks\":["
                + "{\"type\":\"no_changes\"}]}\n";
        Files.writeString(tasks, row + row, StandardCharsets.UTF_8);

        List<JsonNode> parsed = AgentEvalDriver.loadTasks(tasks);
        assertThrows(IllegalArgumentException.class,
                () -> AgentEvalDriver.validateTaskIds(parsed));
    }

    @Test
    void classifiesStructuredLlmFailureAsExternal(@TempDir Path tempDir) throws Exception {
        AgentEvalDriver.EvalTraceSink sink = new AgentEvalDriver.EvalTraceSink(
                tempDir.resolve("trace.jsonl"));
        sink.emit(new RunEvent.FailureGuidance(
                "EXECUTION_FAILURE", "LLM request failed: status=503 no_available_account",
                "retry", List.of()));

        assertTrue(sink.externalFailure());
        assertEquals("EXECUTION_FAILURE", sink.failureCategory());
    }
}
