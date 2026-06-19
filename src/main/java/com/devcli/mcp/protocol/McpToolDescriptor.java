package com.devcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
        String serverName,
        String name,
        String namespacedName,
        String description,
        JsonNode inputSchema,
        Annotations annotations
) {
    public McpToolDescriptor(String serverName, String name, String namespacedName,
                             String description, JsonNode inputSchema) {
        this(serverName, name, namespacedName, description, inputSchema, null);
    }

    public record Annotations(boolean readOnly, boolean destructive, boolean openWorld) {
    }

    public static String namespaced(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }
}
