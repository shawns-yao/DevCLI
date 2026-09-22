package com.devcli.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceQueryTest {

    private static final String FILE = "trace-2026-08-27.jsonl";

    private void seed(Path tempDir) throws Exception {
        List<String> lines = List.of(
                line("2026-08-27T10:00:00Z", "run_a", "turn.started",
                        "\"input\":\"hello task\""),
                line("2026-08-27T10:00:01Z", "run_a", "tool.calls",
                        "\"tools\":\"read_file,grep_code\""),
                line("2026-08-27T10:00:03Z", "run_a", "turn.completed",
                        "\"status\":\"completed\""),
                line("2026-08-27T11:00:00Z", "run_b", "turn.failed",
                        "\"error\":\"boom\""));
        Files.writeString(tempDir.resolve(FILE), String.join(System.lineSeparator(), lines));
    }

    private static String line(String ts, String runId, String type, String extra) {
        return "{\"timestamp\":\"" + ts + "\",\"traceId\":\"" + runId
                + "\",\"phase\":\"run\",\"event\":\"run.event\",\"type\":\"" + type
                + "\"," + extra + "}";
    }

    @Test
    void aggregatesByRunId(@TempDir Path tempDir) throws Exception {
        seed(tempDir);
        TraceQuery query = new TraceQuery(tempDir);

        Map<String, TraceQuery.RunSummary> byId = query.listRuns(10).stream()
                .collect(Collectors.toMap(TraceQuery.RunSummary::runId, Function.identity()));

        assertEquals(2, byId.size());
        TraceQuery.RunSummary runA = byId.get("run_a");
        assertEquals(3, runA.eventCount());
        assertEquals(2, runA.toolCallCount());
        assertEquals("completed", runA.terminalState());
        assertEquals("hello task", runA.firstInput());
        assertEquals("FAILED", byId.get("run_b").terminalState());
    }

    @Test
    void rendersSingleRunTimeline(@TempDir Path tempDir) throws Exception {
        seed(tempDir);
        TraceQuery query = new TraceQuery(tempDir);

        String output = query.renderRun("run_a");
        assertTrue(output.contains("run_a"));
        assertTrue(output.contains("read_file,grep_code"));
        assertTrue(query.renderRun("missing").contains("未找到"));
    }

    @Test
    void emptyDirectoryGivesGuidance(@TempDir Path tempDir) {
        TraceQuery query = new TraceQuery(tempDir);
        assertTrue(query.renderList(5).contains("暂无 trace"));
        assertTrue(query.renderRun("latest").contains("暂无 trace"));
    }
}
