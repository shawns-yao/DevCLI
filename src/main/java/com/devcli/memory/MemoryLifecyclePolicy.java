package com.devcli.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

final class MemoryLifecyclePolicy {
    private MemoryLifecyclePolicy() {
    }

    static Instant expiresAt(MemoryEntry.MemoryType type, Instant createdAt) {
        long days = ttlDays(type);
        if (days <= 0) return null;
        return (createdAt == null ? Instant.now() : createdAt).plus(Duration.ofDays(days));
    }

    static long ttlDays(MemoryEntry.MemoryType type) {
        String suffix = (type == null ? MemoryEntry.MemoryType.FACT : type)
                .name().toLowerCase(Locale.ROOT);
        long fallback = switch (type == null ? MemoryEntry.MemoryType.FACT : type) {
            case FACT -> 180L;
            case FEEDBACK -> 365L;
            case SUMMARY -> 30L;
            case CONVERSATION, TOOL_RESULT -> 7L;
        };
        String specific = configured("devcli.memory.ttl." + suffix + ".days",
                "DEVCLI_MEMORY_TTL_" + suffix.toUpperCase(Locale.ROOT) + "_DAYS");
        String common = configured("devcli.memory.ttl.days", "DEVCLI_MEMORY_TTL_DAYS");
        return parse(specific, parse(common, fallback));
    }

    private static String configured(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value;
    }

    private static long parse(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
