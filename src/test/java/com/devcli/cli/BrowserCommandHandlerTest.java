package com.devcli.cli;

import com.devcli.browser.BrowserConnectivityCheck;
import com.devcli.browser.BrowserMode;
import com.devcli.browser.BrowserSession;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.hitl.TerminalHitlHandler;
import com.devcli.mcp.McpServer;
import com.devcli.mcp.McpServerManager;
import com.devcli.mcp.McpServerStatus;
import com.devcli.mcp.config.McpConfigLoader;
import com.devcli.mcp.config.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserCommandHandlerTest {

    @Test
    void browserStatusShowsCurrentMode(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = h.commands().handle("status");

        assertTrue(result.contains("当前模式"));
        assertTrue(result.contains("isolated"));
    }

    @Test
    void browserConnectRejectsInvalidPort(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = h.commands().handle("connect 80");

        assertTrue(result.contains("1024-65535"));
        assertEquals(BrowserMode.ISOLATED, h.session.mode());
    }

    @Test
    void browserConnectDefaultUsesAutoConnectWithoutLegacyProbe(@TempDir Path tempDir) {
        BrowserSession session = new BrowserSession();
        HitlToolRegistry registry = new HitlToolRegistry(new TerminalHitlHandler(false));
        CountingConnectivityCheck connectivity = new CountingConnectivityCheck();
        FakeMcpServerManager manager = new FakeMcpServerManager(registry, tempDir);

        String result = new BrowserCommandHandler(
                session, connectivity, manager, registry, new TerminalHitlHandler(false))
                .handle("connect");

        assertTrue(result.contains("--autoConnect"));
        assertEquals(BrowserMode.SHARED, session.mode());
        assertEquals("autoConnect", session.browserUrl());
        assertEquals(0, connectivity.probeCount);
        assertEquals(List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect"), manager.lastArgs);
    }

    @Test
    void browserDisconnectWithoutServerClearsSession(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);
        h.session.switchToShared("http://127.0.0.1:9222");

        String result = h.commands().handle("disconnect");

        assertTrue(result.contains("未配置"));
        assertEquals(BrowserMode.ISOLATED, h.session.mode());
    }

    @Test
    void browserTabsInIsolatedModeGivesConnectHint(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = h.commands().handle("tabs");

        assertTrue(result.contains("isolated"));
        assertTrue(result.contains("/browser connect"));
    }

    @Test
    void unknownBrowserSubCommandShowsHelp(@TempDir Path tempDir) throws IOException {
        Harness h = new Harness(tempDir);

        String result = h.commands().handle("wat");

        assertTrue(result.contains("未知 /browser 子命令"));
        assertTrue(result.contains("/browser connect"));
    }

    private static final class Harness {
        private final BrowserSession session = new BrowserSession();
        private final BrowserConnectivityCheck connectivity = new BrowserConnectivityCheck();
        private final TerminalHitlHandler handler = new TerminalHitlHandler(false);
        private final HitlToolRegistry registry = new HitlToolRegistry(handler);
        private final McpServerManager manager;

        private Harness(Path tempDir) throws IOException {
            manager = new McpServerManager(
                    registry,
                    tempDir,
                    new McpConfigLoader(tempDir.resolve("user.json"), tempDir.resolve("project.json"), tempDir));
            manager.loadConfiguredServers();
        }

        private BrowserCommandHandler commands() {
            return new BrowserCommandHandler(
                    session, connectivity, manager, registry, handler);
        }
    }

    private static final class CountingConnectivityCheck extends BrowserConnectivityCheck {
        private int probeCount;

        @Override
        public ProbeResult probe(int port) {
            probeCount++;
            return new ProbeResult(false, null, "should not probe");
        }
    }

    private static final class FakeMcpServerManager extends McpServerManager {
        private final McpServer server;
        private List<String> lastArgs = List.of();

        private FakeMcpServerManager(HitlToolRegistry registry, Path projectDir) {
            super(registry, projectDir);
            McpServerConfig config = new McpServerConfig();
            config.setCommand("npx");
            config.setArgs(List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
            this.server = new McpServer("chrome-devtools", config);
            this.server.status(McpServerStatus.READY);
        }

        @Override
        public synchronized String restartWithArgs(String name, List<String> args) {
            lastArgs = List.copyOf(args);
            server.config().setArgs(args);
            server.status(McpServerStatus.READY);
            return "✅ MCP server 已重启: " + name;
        }

        @Override
        public McpServer server(String name) {
            return "chrome-devtools".equals(name) ? server : null;
        }
    }
}
