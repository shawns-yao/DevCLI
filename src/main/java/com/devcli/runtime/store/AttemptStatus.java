package com.devcli.runtime.store;

import java.util.Locale;

/** 一次 Run 执行尝试的状态。 */
public enum AttemptStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
    ABANDONED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this != RUNNING;
    }

    public static AttemptStatus from(String value) {
        if (value == null || value.isBlank()) {
            return RUNNING;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RUNNING;
        }
    }
}
