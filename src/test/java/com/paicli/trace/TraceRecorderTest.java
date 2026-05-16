package com.paicli.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceRecorderTest {

    @Test
    void writesJsonlTraceWithSanitizedSecrets(@TempDir Path tempDir) throws Exception {
        TraceRecorder recorder = new TraceRecorder(tempDir);
        TraceContext context = new TraceContext("trace-1", "react");

        recorder.record(context, "tool.result", Map.of(
                "tool", "mcp__demo__fetch",
                "resultPreview", "Authorization: Bearer secret-token"
        ));

        Path file = Files.list(tempDir).findFirst().orElseThrow();
        String line = Files.readString(file);

        assertTrue(line.contains("\"traceId\":\"trace-1\""));
        assertTrue(line.contains("\"event\":\"tool.result\""));
        assertTrue(line.contains("Bearer ***"));
        assertEquals(1, Files.readAllLines(file).size());
    }
}
