package com.devcli.mcp;

import java.time.Instant;

public record McpConnectionEvent(
        Instant timestamp,
        String serverName,
        Type type,
        McpServerStatus status,
        long lifecycleVersion,
        int toolCount,
        String message
) {
    public enum Type {
        STARTING,
        READY,
        ERROR,
        DISABLED,
        RECONNECTING,
        TOOLS_CHANGED
    }

    public McpConnectionEvent {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        serverName = serverName == null ? "" : serverName;
        lifecycleVersion = Math.max(0, lifecycleVersion);
        toolCount = Math.max(0, toolCount);
        message = message == null ? "" : message;
    }

    public static McpConnectionEvent of(McpServer server, Type type, String message) {
        return new McpConnectionEvent(
                Instant.now(),
                server == null ? "" : server.name(),
                type,
                server == null ? null : server.status(),
                server == null ? 0 : server.lifecycleVersion(),
                server == null || server.tools() == null ? 0 : server.tools().size(),
                message);
    }
}
