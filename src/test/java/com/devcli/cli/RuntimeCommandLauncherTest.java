package com.devcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCommandLauncherTest {

    @Test
    void recognizesHttpServeCommand() {
        assertTrue(RuntimeCommandLauncher.isServeCommand(
                new String[]{"serve", "--http", "--port", "9090"}));
        assertFalse(RuntimeCommandLauncher.isServeCommand(new String[]{"serve"}));
        assertFalse(RuntimeCommandLauncher.isServeCommand(new String[]{"--http"}));
    }

    @Test
    void parsesServePortWithFallback() {
        assertEquals(9090, RuntimeCommandLauncher.parsePort(
                new String[]{"serve", "--http", "--port", "9090"}, 8080));
        assertEquals(8080, RuntimeCommandLauncher.parsePort(
                new String[]{"serve", "--http", "--port", "invalid"}, 8080));
        assertEquals(8080, RuntimeCommandLauncher.parsePort(null, 8080));
    }
}
