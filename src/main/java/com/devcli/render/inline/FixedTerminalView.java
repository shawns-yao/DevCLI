package com.devcli.render.inline;

import com.devcli.render.StatusInfo;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

import java.util.ArrayList;
import java.util.List;

/** One screen owner for transcript, activity, editable input and footer. */
public final class FixedTerminalView {
    private static final int HISTORY_LIMIT = 2000;
    private final Terminal terminal;
    private final Display display;
    private final List<AttributedString> transcript = new ArrayList<>();
    private List<AttributedString> input = List.of(new AttributedString("> "));
    private List<AttributedString> activity = List.of();
    private List<AttributedString> overlay = List.of();
    private AttributedString preview = AttributedString.EMPTY;
    private StatusInfo status;
    private int inputCursor = 2;
    private int scrollOffset;
    private boolean started;

    public FixedTerminalView(Terminal terminal) {
        this.terminal = terminal;
        this.display = new Display(terminal, true);
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_address, 0, 0);
        render();
    }

    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) return;
        preview = AttributedString.EMPTY;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int count = lines.length - (normalized.endsWith("\n") ? 1 : 0);
        for (int i = 0; i < count; i++) transcript.add(AttributedString.fromAnsi(lines[i]));
        if (transcript.size() > HISTORY_LIMIT) transcript.subList(0, transcript.size() - HISTORY_LIMIT).clear();
        scrollOffset = 0;
        render();
    }

    public synchronized void preview(String text) {
        preview = AttributedString.fromAnsi(text == null ? "" : text);
        render();
    }

    public synchronized void input(List<AttributedString> lines, int cursor) {
        input = lines.isEmpty() ? List.of(new AttributedString("> ")) : List.copyOf(lines);
        inputCursor = Math.max(0, cursor);
        render();
    }

    public synchronized void clearInput() {
        input = List.of(new AttributedString("> "));
        inputCursor = 2;
        render();
    }

    public synchronized void activity(List<AttributedString> lines) {
        activity = List.copyOf(lines);
        render();
    }

    public synchronized void status(StatusInfo value) {
        status = BottomStatusBar.mergeEnvironment(value, status);
        render();
    }

    public synchronized void scroll(int direction) {
        scrollOffset = Math.max(0, scrollOffset + direction * Math.max(1, terminal.getHeight() / 2));
        render();
    }

    public synchronized void overlay(List<String> lines) {
        overlay = lines.stream().map(AttributedString::fromAnsi).toList();
        render();
    }

    public synchronized void close() {
        if (!started) return;
        started = false;
        terminal.puts(Capability.cursor_address, TerminalCapabilities.safeSize(terminal).getRows() - 1, 0);
        terminal.puts(Capability.clr_eol);
        terminal.writer().println();
        terminal.flush();
    }

    private void render() {
        if (!started) return;
        int columns = Math.max(4, TerminalCapabilities.safeSize(terminal).getColumns());
        int rows = Math.max(4, TerminalCapabilities.safeSize(terminal).getRows());
        int cursorRow = inputCursor / (columns + 1);
        int inputRows = Math.min(Math.min(4, rows - 3), Math.max(1, input.size()));
        int inputStart = Math.max(0, Math.min(cursorRow - inputRows + 1, input.size() - inputRows));
        int activityRows = Math.min(Math.min(2, activity.size()), Math.max(0, rows - inputRows - 4));
        int transcriptRows = rows - inputRows - activityRows - 2;
        List<AttributedString> wrapped = new ArrayList<>();
        for (AttributedString line : overlay.isEmpty() ? transcript : overlay)
            wrapped.addAll(line.columnSplitLength(columns, false, true));
        if (overlay.isEmpty() && preview.length() > 0) wrapped.addAll(preview.columnSplitLength(columns, false, true));
        scrollOffset = Math.min(scrollOffset, Math.max(0, wrapped.size() - transcriptRows));
        int end = wrapped.size() - scrollOffset;
        int start = Math.max(0, end - transcriptRows);
        List<AttributedString> frame = new ArrayList<>(wrapped.subList(start, end));
        while (frame.size() < transcriptRows) frame.add(AttributedString.EMPTY);
        for (int i = 0; i < activityRows; i++) frame.add(activity.get(i).columnSubSequence(0, columns));
        frame.add(new AttributedString("─".repeat(columns), AttributedStyle.DEFAULT.faint()));
        int inputTop = frame.size();
        for (int i = 0; i < inputRows; i++) frame.add(input.get(inputStart + i).columnSubSequence(0, columns));
        frame.add(status == null ? AttributedString.EMPTY : new AttributedString(
                BottomStatusBar.formatFooterLine(status, columns), AttributedStyle.DEFAULT.faint()));
        int cursor = (inputTop + Math.max(0, cursorRow - inputStart)) * (columns + 1)
                + Math.min(columns - 1, inputCursor % (columns + 1));
        display.resize(rows, columns);
        display.update(frame, cursor, true);
    }
}
