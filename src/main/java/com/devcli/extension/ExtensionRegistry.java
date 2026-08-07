package com.devcli.extension;

import com.devcli.hook.HookDefinition;
import com.devcli.mcp.config.McpServerConfig;
import com.devcli.skill.Skill;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展发现注册表。按稳定 id 去重，提供一致的列举、过滤和替换语义。
 */
public final class ExtensionRegistry {
    private final Map<String, ExtensionContract> extensions = new ConcurrentHashMap<>();

    public void register(ExtensionContract extension) {
        Objects.requireNonNull(extension, "extension");
        ExtensionContract previous = extensions.putIfAbsent(extension.descriptor().id(), extension);
        if (previous != null) {
            throw new IllegalStateException("扩展 id 已注册: " + extension.descriptor().id());
        }
    }

    public void registerOrReplace(ExtensionContract extension) {
        Objects.requireNonNull(extension, "extension");
        extensions.put(extension.descriptor().id(), extension);
    }

    public void replaceKind(ExtensionContract.Kind kind, Collection<ExtensionContract> replacements) {
        Objects.requireNonNull(kind, "kind");
        extensions.entrySet().removeIf(entry -> entry.getValue().descriptor().kind() == kind);
        if (replacements != null) {
            replacements.forEach(this::registerOrReplace);
        }
    }

    public Optional<ExtensionContract> find(String id) {
        return Optional.ofNullable(extensions.get(id));
    }

    public boolean remove(String id) {
        return extensions.remove(id) != null;
    }

    public List<ExtensionContract> list() {
        return sorted(extensions.values());
    }

    public List<ExtensionContract> list(ExtensionContract.Kind kind) {
        if (kind == null) return List.of();
        return sorted(extensions.values().stream()
                .filter(extension -> extension.descriptor().kind() == kind)
                .toList());
    }

    public List<ExtensionContract> enabled() {
        return sorted(extensions.values().stream()
                .filter(extension -> extension.descriptor().enabled())
                .toList());
    }

    public int size() {
        return extensions.size();
    }

    public static ExtensionContract fromSkill(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        return descriptor(new ExtensionContract.Descriptor(
                "skill:" + skill.name(), ExtensionContract.Kind.SKILL, skill.name(),
                skill.version(), source(skill.source()), true,
                Set.of("skill", "context:" + skill.context().wireName()),
                Map.of("description", skill.description(), "source", skill.displaySource())));
    }

    public static ExtensionContract fromHook(HookDefinition hook) {
        Objects.requireNonNull(hook, "hook");
        return descriptor(new ExtensionContract.Descriptor(
                "hook:" + hook.id(), ExtensionContract.Kind.HOOK, hook.name(), "0",
                ExtensionContract.Source.RUNTIME, hook.enabled(),
                Set.of("hook", "event:" + hook.event().name().toLowerCase()),
                Map.of("tool", hook.tool(), "failureMode", hook.failureMode().name().toLowerCase())));
    }

    public static ExtensionContract fromMcpServer(String id, McpServerConfig config) {
        Objects.requireNonNull(config, "config");
        String normalizedId = id == null || id.isBlank() ? config.getCommand() : id;
        if (normalizedId == null || normalizedId.isBlank()) {
            normalizedId = config.getUrl();
        }
        if (normalizedId == null || normalizedId.isBlank()) {
            throw new IllegalArgumentException("MCP server id is required");
        }
        return descriptor(new ExtensionContract.Descriptor(
                "mcp:" + normalizedId, ExtensionContract.Kind.MCP_SERVER, normalizedId, "0",
                ExtensionContract.Source.PROJECT, !config.isDisabled(),
                Set.of("mcp", "transport:" + config.transportName()),
                Map.of("transport", config.transportName(),
                        "readOnlyAnnotationsTrusted", Boolean.toString(config.isTrustReadOnlyAnnotations()))));
    }

    public static ExtensionContract command(String name, String description) {
        return descriptor(new ExtensionContract.Descriptor(
                "command:" + required(name), ExtensionContract.Kind.COMMAND, name, "0",
                ExtensionContract.Source.BUILTIN, true, Set.of("command"),
                Map.of("description", description == null ? "" : description)));
    }

    private static ExtensionContract descriptor(ExtensionContract.Descriptor descriptor) {
        return () -> descriptor;
    }

    private static ExtensionContract.Source source(Skill.Source source) {
        return switch (source) {
            case BUILTIN -> ExtensionContract.Source.BUILTIN;
            case USER -> ExtensionContract.Source.USER;
            case PROJECT -> ExtensionContract.Source.PROJECT;
        };
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("command name is required");
        }
        return value.trim();
    }

    private static List<ExtensionContract> sorted(Collection<ExtensionContract> values) {
        List<ExtensionContract> result = new ArrayList<>(values);
        result.sort(Comparator.comparing(extension -> extension.descriptor().id()));
        return List.copyOf(result);
    }
}
