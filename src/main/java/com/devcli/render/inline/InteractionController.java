package com.devcli.render.inline;

import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.List;

/** input line、HITL、palette 共用的唯一交互入口。 */
final class InteractionController {
    private final PrintStream out;
    private final Terminal terminal;
    private volatile LineReader lineReader;

    InteractionController(PrintStream out, Terminal terminal) {
        this.out = out;
        this.terminal = terminal;
    }

    void bind(LineReader reader) {
        this.lineReader = reader;
    }

    ApprovalResult promptApproval(ApprovalRequest request, PlainFallback fallback) {
        if (terminal == null) return fallback.prompt(request);
        return new InlineApprovalPrompter(out, terminal, lineReader).prompt(request);
    }

    int openPalette(String title, List<String> items, PaletteFallback fallback) {
        if (terminal == null) return fallback.open(title, items);
        return new SlashPalette(out, terminal).open(title, items);
    }

    @FunctionalInterface interface PlainFallback { ApprovalResult prompt(ApprovalRequest request); }
    @FunctionalInterface interface PaletteFallback { int open(String title, List<String> items); }
}
