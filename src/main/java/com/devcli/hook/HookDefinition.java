package com.devcli.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** 单个生命周期 Hook 的受控工具调用定义。 */
public record HookDefinition(
        String id,
        String name,
        HookEvent event,
        boolean enabled,
        String tool,
        JsonNode arguments,
        FailureMode failureMode,
        boolean allowSideEffects) {

    public HookDefinition {
        id = normalize(id, "Hook id 不能为空");
        name = name == null || name.isBlank() ? id : name.trim();
        event = java.util.Objects.requireNonNull(event, "event");
        tool = normalize(tool, "Hook tool 不能为空");
        arguments = arguments == null || arguments.isNull()
                ? JsonNodeFactory.instance.objectNode()
                : arguments.deepCopy();
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("Hook arguments 必须是 JSON 对象");
        }
        failureMode = failureMode == null ? FailureMode.WARN : failureMode;
    }

    public enum FailureMode {
        WARN,
        REQUIRED;

        public static FailureMode parse(String value) {
            if (value == null || value.isBlank()) return WARN;
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static String normalize(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
