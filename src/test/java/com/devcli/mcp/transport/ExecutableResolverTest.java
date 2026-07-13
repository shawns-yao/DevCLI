package com.devcli.mcp.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutableResolverTest {

    @Test
    void resolvesWindowsCommandWrapperFromPath(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("npx"), "#!/usr/bin/env node");
        Path wrapper = Files.writeString(tempDir.resolve("npx.cmd"), "@echo off");

        String resolved = ExecutableResolver.resolve("npx", Map.of(
                "PATH", tempDir.toString(),
                "PATHEXT", ".EXE;.CMD;.BAT"), true);

        assertEquals(wrapper.toAbsolutePath().normalize().toString(), resolved);
    }

    @Test
    void keepsExplicitPathUnchanged(@TempDir Path tempDir) {
        String command = tempDir.resolve("custom.cmd").toString();

        assertEquals(command, ExecutableResolver.resolve(command, Map.of(), true));
    }

    @Test
    void keepsCommandNameOnNonWindows() {
        assertEquals("npx", ExecutableResolver.resolve("npx", Map.of("PATH", "/tmp"), false));
    }
}
