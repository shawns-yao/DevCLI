package com.devcli.mcp;

import com.devcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public record McpToolDiscoveryEntry(
        String serverName,
        long lifecycleVersion,
        int toolCount,
        List<String> toolNames,
        String schemaFingerprint,
        Instant discoveredAt
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public McpToolDiscoveryEntry {
        serverName = serverName == null ? "" : serverName;
        lifecycleVersion = Math.max(0, lifecycleVersion);
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        toolCount = Math.max(0, toolCount);
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        discoveredAt = discoveredAt == null ? Instant.now() : discoveredAt;
    }

    public static McpToolDiscoveryEntry from(String serverName,
                                             long lifecycleVersion,
                                             List<McpToolDescriptor> tools) {
        List<McpToolDescriptor> normalized = tools == null ? List.of() : tools.stream()
                .sorted(Comparator.comparing(McpToolDescriptor::name))
                .toList();
        List<String> names = normalized.stream().map(McpToolDescriptor::name).toList();
        return new McpToolDiscoveryEntry(
                serverName,
                lifecycleVersion,
                normalized.size(),
                names,
                fingerprint(normalized),
                Instant.now());
    }

    private static String fingerprint(List<McpToolDescriptor> tools) {
        StringBuilder payload = new StringBuilder();
        for (McpToolDescriptor tool : tools) {
            payload.append(tool.name()).append('\n')
                    .append(tool.namespacedName()).append('\n')
                    .append(canonicalJson(tool.inputSchema())).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(payload.toString().hashCode());
        }
    }

    private static String canonicalJson(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return node.toString();
        }
    }
}
