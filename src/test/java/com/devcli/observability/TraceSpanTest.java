package com.devcli.observability;

import com.devcli.trace.TraceRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSpanTest {
    @Test
    void persistsCorrelatedSpanWithoutSecrets(@TempDir Path tempDir) throws Exception {
        TraceRecorder recorder = new TraceRecorder(tempDir);
        RunTelemetry telemetry = new RunTelemetry("run", "turn", "step", "agent", "attempt", "trace");
        recorder.record(new TraceSpan("tool", telemetry, "parent", Instant.now(), Instant.now(),
                "ok", Map.of("authorization", "Bearer secret")));
        String content = Files.readString(Files.list(tempDir).findFirst().orElseThrow());
        assertTrue(content.contains("\"runId\":\"run\""));
        assertTrue(content.contains("Bearer ***"));
    }
}
