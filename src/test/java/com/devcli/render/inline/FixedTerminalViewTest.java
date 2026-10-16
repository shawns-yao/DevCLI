package com.devcli.render.inline;

import com.devcli.render.StatusInfo;
import org.jline.builtins.ScreenTerminal;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FixedTerminalViewTest {
    @Test
    void outputAndActivityKeepDraftAndCursorAtBottom() throws Exception {
        try (Fixture f = new Fixture(80, 24)) {
            f.view.start();
            f.view.append("DevCLI\n");
            f.view.input(List.of(new AttributedString("> next question")), 15);
            f.view.status(StatusInfo.active("gemini", 100_000, "auto", "running"));
            f.capture();
            assertTrue(f.row(0).startsWith("DevCLI"), f.screen.toString());
            f.view.activity(List.of(new AttributedString("thinking...")));
            f.view.preview("answer streaming");
            f.capture();
            assertTrue(f.row(1).startsWith("answer streaming"), f.screen.toString());
            assertTrue(f.row(20).startsWith("thinking..."), f.screen.toString());
            assertTrue(f.row(22).startsWith("> next question"), f.screen.toString());
            assertTrue(f.row(23).contains("gemini"), f.screen.toString());
            assertArrayEquals(new int[]{15, 22}, f.cursor());
            f.view.append("answer streaming complete\n");
            f.view.activity(List.of());
            f.capture();
            assertTrue(f.row(22).startsWith("> next question"), f.screen.toString());
            assertArrayEquals(new int[]{15, 22}, f.cursor());
        }
    }

    @Test
    void tokenUpdatesPreserveMcpAndSkillStatus() throws Exception {
        try (Fixture f = new Fixture(80, 24)) {
            f.view.start();
            f.view.status(StatusInfo.idle("gemini", 100_000, "auto")
                    .withEnvironment("MCP 9/9", "Skill 149/149").withGrantSummary("hard_deny 2"));
            f.view.status(StatusInfo.active("gemini", 100_000, "auto", "running"));
            f.capture();
            assertTrue(f.row(23).contains("MCP 9/9"), f.screen.toString());
            assertTrue(f.row(23).contains("Skill 149/149"), f.screen.toString());
            assertTrue(f.row(23).contains("hard_deny 2"), f.screen.toString());
        }
    }

    @Test
    void historyScrollingDoesNotMoveEditableInput() throws Exception {
        try (Fixture f = new Fixture(40, 10)) {
            f.view.start();
            for (int i = 0; i < 30; i++) f.view.append("line " + i + "\n");
            f.view.input(List.of(new AttributedString("> draft")), 7);
            f.capture();
            assertTrue(f.row(6).startsWith("line 29"), f.screen.toString());
            f.view.scroll(1);
            f.capture();
            assertTrue(f.row(6).startsWith("line 24"), f.screen.toString());
            assertTrue(f.row(8).startsWith("> draft"), f.screen.toString());
            assertArrayEquals(new int[]{7, 8}, f.cursor());
            f.view.scroll(-1);
            f.capture();
            assertTrue(f.row(6).startsWith("line 29"), f.screen.toString());
        }
    }

    @Test
    void multilineInputKeepsCursorInVisibleWindow() throws Exception {
        try (Fixture f = new Fixture(40, 10)) {
            f.view.start();
            f.view.input(List.of("first", "second", "third", "fourth", "fifth", "sixth")
                    .stream().map(AttributedString::new).toList(), 5 * 41 + 3);
            f.capture();
            assertTrue(f.row(5).startsWith("third"), f.screen.toString());
            assertTrue(f.row(8).startsWith("sixth"), f.screen.toString());
            assertArrayEquals(new int[]{3, 8}, f.cursor());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Terminal terminal;
        final FixedTerminalView view;
        final ScreenTerminal screen;
        final int width;
        final int height;
        Fixture(int width, int height) throws Exception {
            this.width = width;
            this.height = height;
            terminal = TerminalBuilder.builder().system(false).type("xterm-256color")
                    .streams(new ByteArrayInputStream(new byte[0]), output)
                    .encoding(StandardCharsets.UTF_8).size(new Size(width, height)).build();
            view = new FixedTerminalView(terminal);
            screen = new ScreenTerminal(width, height);
        }
        void capture() {
            screen.write(output.toString(StandardCharsets.UTF_8));
            output.reset();
        }
        String row(int row) { return screen.toString().split("\n", -1)[row]; }
        int[] cursor() {
            int[] cursor = new int[2];
            screen.dump(new long[width * height], cursor);
            return cursor;
        }
        public void close() throws Exception { terminal.close(); }
    }
}
