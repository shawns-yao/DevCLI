package com.devcli.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class MemoryLifecyclePolicy {
    static final String EXPIRY_MODE_METADATA = "expiry_mode";
    static final String EXPIRY_MODE_SLIDING = "SLIDING";
    static final String EXPIRY_MODE_FIXED = "FIXED";
    static final String EXPIRY_MODE_VALIDATED = "VALIDATED";

    private MemoryLifecyclePolicy() {
    }

    static MemoryEntry initializeExpiration(MemoryEntry entry, Instant now) {
        if (entry == null) return null;
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        Instant expiresAt = entry.getExpiresAt();
        if (expiresAt != null) {
            if (!EXPIRY_MODE_VALIDATED.equals(metadata.get(EXPIRY_MODE_METADATA))) {
                metadata.put(EXPIRY_MODE_METADATA, EXPIRY_MODE_FIXED);
            }
        } else {
            expiresAt = expiresAt(entry.getType(), now);
            if (expiresAt != null) {
                metadata.put(EXPIRY_MODE_METADATA, EXPIRY_MODE_FIXED);
            }
        }
        return entry.withLifecycle(entry.getRevision(), expiresAt, Map.copyOf(metadata));
    }

    static MemoryEntry recordRecall(MemoryEntry entry, Instant recalledAt) {
        Instant effectiveTime = recalledAt == null ? Instant.now() : recalledAt;
        MemoryEntry initialized = initializeExpiration(entry, effectiveTime);
        return initialized.withRecallAt(effectiveTime);
    }

    static MemoryEntry recordValidated(MemoryEntry entry, Instant validatedAt) {
        Instant effectiveTime = validatedAt == null ? Instant.now() : validatedAt;
        MemoryEntry validated = initializeExpiration(entry, effectiveTime).withValidatedAt(effectiveTime);
        long baseDays = ttlDays(validated.getType());
        if (baseDays <= 0) return validated;
        long multiplier = validated.getValidatedUseCount() >= 7 ? 4
                : validated.getValidatedUseCount() >= 3 ? 3 : 2;
        Instant extended = effectiveTime.plus(Duration.ofDays(baseDays * multiplier));
        Instant current = validated.getExpiresAt();
        Instant next = current == null || extended.isAfter(current) ? extended : current;
        Map<String, String> metadata = new HashMap<>(validated.getMetadata());
        metadata.put(EXPIRY_MODE_METADATA, EXPIRY_MODE_VALIDATED);
        return validated.withLifecycle(validated.getRevision(), next, Map.copyOf(metadata));
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
