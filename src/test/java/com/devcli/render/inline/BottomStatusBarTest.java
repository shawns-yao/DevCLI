package com.devcli.render.inline;

import com.devcli.render.StatusInfo;
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
                "default", 1500L, "running");
        String line = BottomStatusBar.formatFooterLine(info, 200);
        assertFalse(line.contains("Auto Model"), line);
        assertTrue(line.contains("running"), line);
        assertTrue(line.contains("glm-5.1"), line);
        assertTrue(line.contains("ctx"), line);
        assertTrue(line.contains("1%"), line);
        assertTrue(line.contains("in 1.0k out 234"), line);
        assertTrue(line.contains("cache 100"), line);
        assertTrue(line.contains("¥0.0123"), line);
        assertTrue(line.contains("1.5s"), line);
    }

    @Test
    void formatStatusLinePadsToColumnWidth() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, null, 0L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertEquals(80, new org.jline.utils.AttributedString(line).columnLength());
    }

    @Test
    void formatStatusLineTruncatesWhenLong() {
        StatusInfo info = new StatusInfo("very-long-model-name-exceeding-cols",
                999_999L, 200_000L, "bypassPermissions", 0L);
        String line = BottomStatusBar.formatFooterLine(info, 30);
        assertEquals(30, new org.jline.utils.AttributedString(line).columnLength());
    }

    @Test
    void formatStatusLineHidesElapsedWhenZero() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, null, 0L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertFalse(line.contains("ms"));
        assertFalse(line.contains("0s"));
    }

    @Test
    void formatStatusLineHandlesMillisecondElapsed() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 0L, null, 250L);
        String line = BottomStatusBar.formatFooterLine(info, 80);
        assertTrue(line.contains("250ms"), line);
    }

    @Test
    void footerLineFitsColumnWidth() {
        String line = BottomStatusBar.formatFooterLine(StatusInfo.idle("glm-5.1", 200_000L, "default"), 40);
        assertTrue(line.length() == 40, "footer should fill requested width: " + line.length());
        assertTrue(line.contains("glm-5.1"), line);
    }

    @Test
    void activeStatusLineShowsPhase() {
        // 模式与执行阶段分别保留在同一状态行。
        StatusInfo info = StatusInfo.active("glm-5.1", 200_000L, "acceptEdits", "plan")
                .withEnvironment("MCP 4/4", "Skill 2/2");
        String bottom = BottomStatusBar.formatFooterLine(info, 80);
        assertTrue(bottom.contains("MCP 4/4"), bottom);
        assertTrue(bottom.contains("Skill 2/2"), bottom);
        assertTrue(bottom.contains("acceptEdits"), bottom);
        assertTrue(bottom.contains("plan"), bottom);
        assertTrue(bottom.contains("glm-5.1"), bottom);
    }

    @Test
    void statusLinesUseJLineAttributes() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, null, 0L);
        var lines = BottomStatusBar.formatStatusLines(info, 80);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).toString().contains("glm-5.1"));
        assertTrue(lines.get(0).toAnsi().contains("[2m"), "footer row should use subtle style");
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
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, null));
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
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, null));
            sink.reset();
            bar.prepareInputLine();
            Mockito.verify(terminal, Mockito.never()).puts(
                    Mockito.eq(org.jline.utils.InfoCmp.Capability.cursor_address), Mockito.any(Object[].class));
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
