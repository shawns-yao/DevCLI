package com.devcli.runtime.store;

import java.util.Locale;

/** Run 的产品提交入口，不再为不同入口维护独立状态机。 */
public enum SubmissionSource {
    CLI,
    RUNTIME_API,
    BACKGROUND,
    RECOVERY;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SubmissionSource from(String value) {
        if (value == null || value.isBlank()) {
            return CLI;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CLI;
        }
    }
}
