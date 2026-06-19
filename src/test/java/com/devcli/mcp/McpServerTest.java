package com.devcli.mcp;

import com.devcli.mcp.config.McpServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpServerTest {

    @Test
    void lifecycleVersionIncrementsOnConnectionAndToolListRefresh() {
        McpServer server = new McpServer("demo", new McpServerConfig());

        assertEquals(0, server.lifecycleVersion());

        server.markStarted();
        assertEquals(1, server.lifecycleVersion());

        server.markToolsChanged();
        assertEquals(2, server.lifecycleVersion());
    }
}
