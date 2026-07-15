package com.devcli.hook;

import java.util.Locale;

/** Agent 运行生命周期中的可订阅 Hook 事件。 */
public enum HookEvent {
    AGENT_START,
    TURN_START,
    MESSAGE_START,
    MESSAGE_END,
    TOOL_EXECUTION_START,
    TOOL_EXECUTION_END,
    TURN_END,
    AGENT_END;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static HookEvent parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Hook event 不能为空");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
