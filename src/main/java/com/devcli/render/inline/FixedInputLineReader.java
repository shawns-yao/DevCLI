package com.devcli.render.inline;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.Reference;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

import java.util.List;

/** Keeps JLine editing, history, completion and paste while the view owns screen placement. */
public final class FixedInputLineReader extends LineReaderImpl {
    private final FixedTerminalView view;

    public FixedInputLineReader(Terminal terminal) throws java.io.IOException {
        super(terminal);
        view = new FixedTerminalView(terminal);
        option(Option.AUTO_FRESH_LINE, false);
        getWidgets().put("transcript-up", () -> { view.scroll(1); return true; });
        getWidgets().put("transcript-down", () -> { view.scroll(-1); return true; });
        bindScroll("transcript-up", Capability.key_ppage);
        bindScroll("transcript-down", Capability.key_npage);
    }

    private void bindScroll(String widget, Capability capability) {
        String sequence = KeyMap.key(terminal, capability);
        if (sequence != null) getKeyMaps().get(MAIN).bind(new Reference(widget), sequence);
    }

    public FixedTerminalView view() { return view; }

    @Override
    protected void redisplay(boolean flush) {
        if (view != null && !(display instanceof InputDisplay)) {
            display = new InputDisplay();
            display.resize(size.getRows(), size.getColumns());
            display.setDelayLineWrap(isSet(Option.DELAY_LINE_WRAP));
        }
        super.redisplay(flush);
    }

    @Override
    public void printAbove(String text) {
        if (view == null) super.printAbove(text);
        else view.append(text);
    }

    @Override
    protected void doCleanup(boolean newline) {
        super.doCleanup(false);
    }

    private final class InputDisplay extends Display {
        InputDisplay() { super(FixedInputLineReader.this.terminal, false); }
        @Override
        public void update(List<AttributedString> lines, int cursor, boolean flush) {
            view.input(lines, cursor);
        }
    }
}
