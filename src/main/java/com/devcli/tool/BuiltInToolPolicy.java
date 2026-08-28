package com.devcli.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 内置工具的副作用、审批与审计策略唯一来源。 */
public final class BuiltInToolPolicy {
    public record Policy(ToolRegistry.ToolEffect effect,
                         boolean requiresApproval,
                         boolean audited) {
    }

    private static final Map<String, Policy> POLICIES = policies();

    private BuiltInToolPolicy() {
    }

    public static Optional<Policy> find(String toolName) {
        return Optional.ofNullable(POLICIES.get(toolName == null ? "" : toolName));
    }

    public static ToolRegistry.ToolEffect effectOrDefault(String toolName) {
        return find(toolName).map(Policy::effect)
                .orElse(ToolRegistry.ToolEffect.EXTERNAL_MUTATION);
    }

    public static boolean requiresApproval(String toolName) {
        return find(toolName).map(Policy::requiresApproval).orElse(false);
    }

    public static boolean audited(String toolName) {
        return find(toolName).map(Policy::audited).orElse(false);
    }

    public static Set<String> approvalRequiredTools() {
        return POLICIES.entrySet().stream()
                .filter(entry -> entry.getValue().requiresApproval())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, Policy> policies() {
        Map<String, Policy> values = new LinkedHashMap<>();
        register(values, ToolRegistry.ToolEffect.READ_ONLY, false, false,
                "read_file", "read_tool_result", "list_dir", "search_code", "grep_code",
                "web_search", "web_fetch", "list_memory", "search_tools", "browser_status");
        register(values, ToolRegistry.ToolEffect.LOCAL_CONTEXT, false, false, "load_skill");
        register(values, ToolRegistry.ToolEffect.PROJECT_MUTATION, true, true,
                "write_file", "edit_file", "create_project", "revert_turn");
        register(values, ToolRegistry.ToolEffect.HOST_PROCESS, true, true, "execute_command");
        register(values, ToolRegistry.ToolEffect.EXTERNAL_MUTATION, false, false,
                "browser_connect", "browser_disconnect", "save_memory", "confirm_memory",
                "delegate_task");
        return Map.copyOf(values);
    }

    private static void register(Map<String, Policy> target,
                                 ToolRegistry.ToolEffect effect,
                                 boolean approval,
                                 boolean audited,
                                 String... names) {
        Policy policy = new Policy(effect, approval, audited);
        for (String name : names) {
            target.put(name, policy);
        }
    }
}
