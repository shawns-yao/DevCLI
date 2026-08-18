package com.devcli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.devcli.runtime.CancellationToken;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface McpTransport extends AutoCloseable {
    void send(JsonNode message) throws IOException;

    default void send(JsonNode message, CancellationToken cancellationToken) throws IOException {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new IOException("MCP request cancelled before transport send");
        }
        send(message);
    }

    /** Best-effort MCP cancellation notification; HTTP transports may dispatch it asynchronously. */
    default void sendCancellation(JsonNode notification) throws IOException {
        send(notification);
    }

    void onReceive(Consumer<JsonNode> listener);

    default List<String> stderrLines() {
        return List.of();
    }

    default Long processId() {
        return null;
    }

    default String transportName() {
        return "unknown";
    }

    @Override
    void close();
}
