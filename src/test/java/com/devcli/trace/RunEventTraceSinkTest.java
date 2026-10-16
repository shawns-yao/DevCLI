package com.devcli.trace;

import com.devcli.concurrent.RunContext;
import com.devcli.event.RunEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventTraceSinkTest {
    @Test
    void recordsObservedCacheHitRatio(@TempDir Path tempDir) throws Exception {
        var sink = new RunEventTraceSink(new TraceRecorder(tempDir));
        sink.emit(new RunEvent.ModelUsage(100, 20, 75, 0));
        try (var paths = Files.list(tempDir)) {
            var line = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(paths.findFirst().orElseThrow()));
            assertEquals(0.75, line.path("cache_hit_ratio").asDouble());
        }
        assertEquals(0, new RunEvent.ModelUsage(0, 0, 0, 0).cacheHitRatio());
    }


    @Test
    void skipsStreamingDeltasAndModelContext(@TempDir Path tempDir) throws Exception {
        RunEventTraceSink sink = new RunEventTraceSink(new TraceRecorder(tempDir));

        sink.emit(new RunEvent.ReasoningDelta("thinking"));
        sink.emit(new RunEvent.MessageDelta("answer"));
        sink.emit(new RunEvent.ModelContext(1, List.of()));
        sink.emit(new RunEvent.ModelMessage(
                new RunEvent.ModelMessageData("assistant", "MODEL", "x", "",
                        List.of(), "", 0, List.of())));
        var result = RunEvent.ToolResultRecorded.from("call", "read_file", "{}",
                com.devcli.tool.ToolOutput.success("private full body"), 1,
                com.devcli.tool.ToolPresentation.generic("read"));
        sink.emit(result);
        sink.emit(new RunEvent.AgentEvent("child", result));
        sink.emit(new RunEvent.AgentEvent("child", RunEvent.ModelMessage.from(
                com.devcli.llm.LlmClient.Message.user("private child context"))));

        try (var paths = Files.list(tempDir)) {
            assertEquals(0, paths.count(), "流式增量与全量上下文不应写入 trace");
        }
    }

    @Test
    void recordsStructuralEventWithStandaloneRunId(@TempDir Path tempDir) throws Exception {
        RunEventTraceSink sink = new RunEventTraceSink(new TraceRecorder(tempDir));

        sink.emit(new RunEvent.ToolCalls(List.of(new RunEvent.ToolCallData(
                "call-1", "read_file", "{}"))));

        Path file = Files.list(tempDir).findFirst().orElseThrow();
        String line = Files.readString(file);
        assertTrue(line.contains("\"traceId\":\"standalone\""), "无 RunContext 时归属 standalone");
        assertTrue(line.contains("\"type\":\"tool.calls\""));
        assertTrue(line.contains("read_file"));
    }

    @Test
    void ignoresNullEvent(@TempDir Path tempDir) throws Exception {
        RunEventTraceSink sink = new RunEventTraceSink(new TraceRecorder(tempDir));
        sink.emit(null);
        try (var paths = Files.list(tempDir)) {
            assertFalse(paths.findAny().isPresent());
        }
    }
}
