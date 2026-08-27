package com.devcli.trace;

import com.devcli.runtime.event.RunEvent;
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
    void skipsStreamingDeltasAndModelContext(@TempDir Path tempDir) throws Exception {
        RunEventTraceSink sink = new RunEventTraceSink(new TraceRecorder(tempDir));

        sink.emit(new RunEvent.ReasoningDelta("thinking"));
        sink.emit(new RunEvent.MessageDelta("answer"));
        sink.emit(new RunEvent.ModelContext(1, List.of()));
        sink.emit(new RunEvent.ModelMessage(
                new RunEvent.ModelMessageData("assistant", "MODEL", "x", "",
                        List.of(), "", 0)));

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
