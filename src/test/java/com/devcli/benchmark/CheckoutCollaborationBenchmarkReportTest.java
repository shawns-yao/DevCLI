package com.devcli.benchmark;

import com.devcli.llm.GLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutCollaborationBenchmarkReportTest {

    @Test
    void reportKeepsEachIncompleteAttemptSideAsNull(@TempDir Path tempDir) throws Exception {
        PairedBenchmarkRunner.Attempt<Void, Void> outcome =
                new PairedBenchmarkRunner.Attempt<>(null, null, false, false);
        PairedBenchmarkRunner.AttemptRecord<Void, Void> attempt =
                new PairedBenchmarkRunner.AttemptRecord<>(1, tempDir, outcome, "single failed");
        PairedBenchmarkRunner.Result<Void, Void> paired =
                new PairedBenchmarkRunner.Result<>(1, List.of(attempt), null);

        Method writeReport = CheckoutCollaborationBenchmarkIT.class.getDeclaredMethod(
                "writeReport", Path.class, com.devcli.llm.LlmClient.class,
                PairedBenchmarkRunner.Result.class);
        writeReport.setAccessible(true);
        Path report = (Path) writeReport.invoke(null, tempDir, new GLMClient("test-key"), paired);

        JsonNode json = new ObjectMapper().readTree(report.toFile());
        assertTrue(json.path("single_agent").isNull());
        assertTrue(json.path("planner_worker_reviewer").isNull());
        JsonNode attemptNode = json.path("attempts").get(0);
        assertTrue(attemptNode.path("single_agent").isNull());
        assertTrue(attemptNode.path("planner_worker_reviewer").isNull());
    }
}
