package com.devcli.mcp.config;

import com.devcli.mcp.protocol.McpToolDescriptor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 本地配置定义的 MCP 工具信任边界。服务端 annotations 默认不可信。
 */
public record McpToolTrustPolicy(boolean trustReadOnlyAnnotations,
                                 Set<String> readOnlyTools,
                                 Set<String> deniedTools) {
    public McpToolTrustPolicy {
        readOnlyTools = normalize(readOnlyTools);
        deniedTools = normalize(deniedTools);
    }

    public static McpToolTrustPolicy untrusted() {
        return new McpToolTrustPolicy(false, Set.of(), Set.of());
    }

    public static McpToolTrustPolicy from(McpServerConfig config) {
        if (config == null) {
            return untrusted();
        }
        return new McpToolTrustPolicy(
                config.isTrustReadOnlyAnnotations(),
                Set.copyOf(config.getReadOnlyTools()),
                Set.copyOf(config.getDeniedTools()));
    }

    public McpToolTrustPolicy withReadOnlyTools(String... names) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(readOnlyTools);
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    merged.add(name.trim());
                }
            }
        }
        return new McpToolTrustPolicy(trustReadOnlyAnnotations, merged, deniedTools);
    }

    public boolean isDenied(String toolName) {
        return deniedTools.contains(normalizeName(toolName));
    }

    public boolean isReadOnly(McpToolDescriptor descriptor) {
        if (descriptor == null || isDenied(descriptor.name())) {
            return false;
        }
        McpToolDescriptor.Annotations annotations = descriptor.annotations();
        if (annotations != null && (annotations.destructive() || annotations.openWorld())) {
            return false;
        }
        if (readOnlyTools.contains(normalizeName(descriptor.name()))) {
            return true;
        }
        return trustReadOnlyAnnotations
                && annotations != null
                && annotations.readOnly();
    }

    private static Set<String> normalize(Set<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            String value = normalizeName(name);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }
}
