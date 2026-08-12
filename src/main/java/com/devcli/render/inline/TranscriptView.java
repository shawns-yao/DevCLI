package com.devcli.render.inline;

import com.devcli.render.state.RunSnapshot;

import java.io.PrintStream;

/** 稳定 transcript 区只追加已确认内容，不清理 scrollback。 */
final class TranscriptView {
    private final PrintStream out;
    private long renderedVersion = -1;

    TranscriptView(PrintStream out) {
        this.out = out;
    }

    void append(String text) {
        if (text == null || text.isEmpty()) return;
        out.print(text);
        out.flush();
    }

    void renderTerminalState(RunSnapshot snapshot) {
        if (snapshot == null || snapshot.version() == renderedVersion) return;
        renderedVersion = snapshot.version();
    }
}
