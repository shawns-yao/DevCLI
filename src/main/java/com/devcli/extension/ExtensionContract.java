package com.devcli.extension;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Skill、Hook、MCP server 和 CLI command 共享的内部扩展描述契约。
 * 该契约只统一发现与生命周期元数据，不替代各模块已有的权限和执行管线。
 */
public interface ExtensionContract {
    Descriptor descriptor();

    enum Kind {
        SKILL,
        HOOK,
        MCP_SERVER,
        COMMAND
    }

    enum Source {
        BUILTIN,
        USER,
        PROJECT,
        RUNTIME
    }

    record Descriptor(String id,
                      Kind kind,
                      String name,
                      String version,
                      Source source,
                      boolean enabled,
                      Set<String> capabilities,
                      Map<String, String> metadata) {
        public Descriptor {
            id = required(id, "extension id");
            kind = kind == null ? Kind.COMMAND : kind;
            name = name == null || name.isBlank() ? id : name.trim();
            version = version == null || version.isBlank() ? "0" : version.trim();
            source = source == null ? Source.RUNTIME : source;
            capabilities = capabilities == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(capabilities));
            metadata = metadata == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }
    }
}
