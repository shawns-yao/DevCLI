package com.devcli.mcp;

import com.devcli.mcp.protocol.McpToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    /** 历史字段名仍为 schemaFingerprint，指纹覆盖描述、schema 和 annotations 的完整定义。 */
    public static String fingerprint(List<McpToolDescriptor> tools) {
        var definitions = MAPPER.createArrayNode();
        for (McpToolDescriptor tool : tools.stream().sorted(Comparator.comparing(McpToolDescriptor::namespacedName)).toList()) {
            ObjectNode definition = definitions.addObject();
            definition.put("name", tool.namespacedName());
            definition.put("description", tool.description());
            definition.set("schema", canonical(tool.inputSchema()));
            definition.set("annotations", MAPPER.valueToTree(tool.annotations()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(definitions.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node == null) return MAPPER.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            var names = new java.util.ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, canonical(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            var array = MAPPER.createArrayNode();
            node.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return node;
    }
}
