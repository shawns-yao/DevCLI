package com.devcli.render.inline;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCapabilitiesTest {

    private String savedSysProp;
    private String savedForceAnsi;

    @BeforeEach
    void save() {
        savedSysProp = System.getProperty("devcli.no.statusbar");
        savedForceAnsi = System.getProperty("devcli.terminal.force.ansi");
    }

    @AfterEach
    void restore() {
        if (savedSysProp == null) {
            System.clearProperty("devcli.no.statusbar");
        } else {
            System.setProperty("devcli.no.statusbar", savedSysProp);
        }
        if (savedForceAnsi == null) {
            System.clearProperty("devcli.terminal.force.ansi");
        } else {
            System.setProperty("devcli.terminal.force.ansi", savedForceAnsi);
        }
    }

    @Test
    void nullTerminalIsNotAnsiCapable() {
        assertFalse(TerminalCapabilities.supportsAnsi(null));
    }

    @Test
    void dumbTerminalIsNotAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        assertFalse(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void explicitOverrideEnablesAnsiForMisdetectedTerminal() {
        System.setProperty("devcli.terminal.force.ansi", "true");
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        assertTrue(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void xtermTerminalIsAnsiCapable() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        assertTrue(TerminalCapabilities.supportsAnsi(terminal));
    }

    @Test
    void scrollRegionRequiresMinimumSize() {
        Terminal small = Mockito.mock(Terminal.class);
        Mockito.when(small.getType()).thenReturn("xterm-256color");
        Mockito.when(small.getSize()).thenReturn(new Size(40, 4));
        assertFalse(TerminalCapabilities.supportsScrollRegion(small));
    }

    @Test
    void scrollRegionTrueOnNormalTerminal() {
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertTrue(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void noStatusbarPropertyDisablesScrollRegion() {
        System.setProperty("devcli.no.statusbar", "true");
        Terminal normal = Mockito.mock(Terminal.class);
        Mockito.when(normal.getType()).thenReturn("xterm-256color");
        Mockito.when(normal.getSize()).thenReturn(new Size(120, 40));
        assertFalse(TerminalCapabilities.supportsScrollRegion(normal));
    }

    @Test
    void safeSizeFallbackOnNullSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(null);
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }

    @Test
    void safeSizeFallbackOnZeroSize() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(0, 0));
        Size size = TerminalCapabilities.safeSize(terminal);
        assertEquals(80, size.getColumns());
        assertEquals(24, size.getRows());
    }
}
