package com.devcli.render.inline;

import com.devcli.render.StatusInfo;
import com.devcli.render.state.RunSnapshot;
import com.devcli.observability.RunTelemetry;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomStatusBarTest {

    @Test
    void formatStatusLineIncludesAllFields() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1000L, 234L, 100L, "¥0.0123",
                true, 1500L, "running");
        String line = BottomStatusBar.formatFooterLine(info, 200);
        assertTrue(line.contains("Auto Model"), line);
        assertTrue(line.contains("running"), line);
        assertTrue(line.contains("glm-5.1"), line);
        assertTrue(line.contains("ctx"), line);
        assertTrue(line.contains("1%"), line);
        assertTrue(line.contains("1.2k/200.0k"), line);
        assertTrue(line.contains("in 1.0k out 234"), line);
        assertTrue(line.contains("cache 100"), line);
        assertTrue(line.contains("¥0.0123"), line);
        assertTrue(line.contains("1.5s"), line);
    }

    @Test
    void formatStatusLinePadsToColumnWidth() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 80);
        assertTrue(visible(line).length() == 80, "status line should fill the bar: " + visible(line).length());
    }

    @Test
    void formatStatusLineTruncatesWhenLong() {
        StatusInfo info = new StatusInfo("very-long-model-name-exceeding-cols",
                999_999L, 200_000L, true, 0L);
        String line = BottomStatusBar.formatStatusLine(info, 30);
        assertTrue(visible(line).length() == 30);
    }

    @Test
    void formatStatusLineHidesElapsedWhenZero() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertFalse(line.contains("ms"));
        assertFalse(line.contains("0s"));
    }

    @Test
    void formatStatusLineHandlesMillisecondElapsed() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 0L, false, 250L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertTrue(line.contains("250ms"), line);
    }

    @Test
    void footerLineFitsColumnWidth() {
        String line = BottomStatusBar.formatFooterLine(StatusInfo.idle("glm-5.1", 200_000L, false), 40);
        assertTrue(line.length() == 40, "footer should fill requested width: " + line.length());
        assertTrue(line.contains("Auto Model"), line);
    }

    @Test
    void activeStatusLineShowsPhase() {
        StatusInfo info = StatusInfo.active("glm-5.1", 200_000L, false, "plan")
                .withEnvironment("MCP 4/4", "Skill 2/2");
        String top = BottomStatusBar.formatStatusLine(info, 80);
        String bottom = BottomStatusBar.formatFooterLine(info, 80);
        assertTrue(top.contains("4 MCP servers"), top);
        assertTrue(top.contains("2 skills"), top);
        assertTrue(bottom.contains("plan"), bottom);
        assertTrue(bottom.contains("glm-5.1"), bottom);
    }

    @Test
    void statusLinesUseJLineAttributes() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        var lines = BottomStatusBar.formatStatusLines(info, 80);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).toString().contains("YOLO"), "status row should show current mode");
        assertTrue(lines.get(1).toAnsi().contains("[2m"), "footer row should use subtle style");
    }

    @Test
    void snapshotDockPrioritizesModePhaseSecurityAndTrace() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 0, 0, 0,
                null, false, 0, "idle");
        RunSnapshot snapshot = new RunSnapshot(4,
                new RunTelemetry("run", "turn", "step", "worker", "attempt", "trace-123456789"),
                "running", "worker", "read file", 120, 30, 10, 2, 1,
                "0.01", "USD", "CONTINUE", "sandboxed:allowed", "docker:started",
                "", "", "checkpoint", "snapshot", java.util.List.of(), java.util.Map.of(),
                java.time.Instant.now());
        String top = BottomStatusBar.formatStatusLine(info, snapshot, 120);
        String bottom = BottomStatusBar.formatFooterLine(info, snapshot, 120);
        assertTrue(top.contains("YOLO"), top);
        assertTrue(top.contains("worker"), top);
        assertTrue(top.contains("sandboxed:allowed"), top);
        assertTrue(bottom.contains("trace trace-1234"), bottom);
    }

    @Test
    void narrowDockDropsLowPriorityEnvironmentBeforeCoreState() {
        StatusInfo info = StatusInfo.active("glm-5.1", 200_000L, false, "worker")
                .withEnvironment("MCP 12/12", "Skill 20/20");
        RunSnapshot snapshot = new RunSnapshot(2, RunTelemetry.empty(), "running", "worker", "",
                0, 0, 0, 0, 0, "", "", "", "sandboxed:allowed", "", "", "",
                "", "", "", java.util.List.of(), java.util.Map.of(), java.time.Instant.now());
        String top = BottomStatusBar.formatStatusLine(info, snapshot, 36);
        assertTrue(top.contains("YOLO"), top);
        assertTrue(top.contains("sandboxed"), top);
        assertFalse(top.contains("MCP servers"), top);
    }

    @Test
    void closeBeforeStartIsSafe() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.close();
        // 不应抛异常；不应输出 ANSI（因为 start 没调过）
        assertTrue(sink.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void startDoesNotHandWriteScrollRegion() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertFalse(emitted.contains("[1;21r"), "dock setup must be delegated to JLine Status: " + emitted);
        } finally {
            bar.close();
        }
    }

    @Test
    void closeIsSafeAfterStart() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        bar.close();
        // 不应抛异常；mock terminal 不创建真实 JLine Status。
        assertTrue(true);
    }

    @Test
    void flushNowDoesNotPrintOutsideInputLifecycle() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            bar.flushNow();
            assertTrue(sink.toString(StandardCharsets.UTF_8).isEmpty());
        } finally {
            bar.close();
        }
    }

    @Test
    void inputLifecycleDoesNotHandWriteInlineStatusOrClearScrollback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            sink.reset();
            bar.prepareInputLine();
            String prepared = sink.toString(StandardCharsets.UTF_8);
            assertFalse(prepared.startsWith("\n\n"), "dock must not inject spacer rows under the prompt: " + prepared);
            assertFalse(prepared.contains(AnsiSeq.moveUp(3)), prepared);
            assertFalse(prepared.contains("[22;1H"), "prompt must stay in transcript flow: " + prepared);

            sink.reset();
            bar.finishInputLine();
            String finished = sink.toString(StandardCharsets.UTF_8);
            assertFalse(finished.contains(AnsiSeq.CLEAR_TO_EOS), finished);
            assertFalse(finished.contains("[22;1H"), "transcript scrolling is not manually forced anymore: " + finished);
        } finally {
            bar.close();
        }
    }

    private static String visible(String line) {
        return line;
    }
}
