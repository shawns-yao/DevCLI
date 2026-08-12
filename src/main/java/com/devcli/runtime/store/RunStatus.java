package com.devcli.runtime.store;

import java.util.Locale;

/** 持久运行的统一状态。 */
public enum RunStatus {
    ENQUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
    RECOVERY_REQUIRED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }

    public static RunStatus from(String value) {
        if (value == null || value.isBlank()) {
            return ENQUEUED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ENQUEUED;
        }
    }
}
