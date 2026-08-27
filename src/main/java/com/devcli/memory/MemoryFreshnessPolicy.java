package com.devcli.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** 与有效性、相关性分离的按类型新鲜度策略。 */
final class MemoryFreshnessPolicy {
    private MemoryFreshnessPolicy() {
    }

    static double weight(MemoryEntry entry, Instant now) {
        if (entry == null) return 0D;
        long halfLifeDays = halfLifeDays(entry.getType());
        if (halfLifeDays <= 0) return 1D;
        Instant current = now == null ? Instant.now() : now;
        Instant ageAnchor = entry.getLastRecalledAt() != null
                && entry.getLastRecalledAt().isAfter(entry.getTimestamp())
                ? entry.getLastRecalledAt()
                : entry.getTimestamp();
        double ageDays = Math.max(0D,
                Duration.between(ageAnchor, current).toMillis() / 86_400_000D);
        return Math.pow(0.5D, ageDays / halfLifeDays);
    }

    /** 使用频率只做近似同分项微调，不能盖过语义相关度；新记忆没有冷启动惩罚。 */
    static double usageBoost(MemoryEntry entry) {
        if (entry == null || entry.getRecallCount() <= 0) return 1D;
        return 1D + Math.min(0.01D, Math.log1p(entry.getRecallCount()) * 0.002D);
    }

    static long halfLifeDays(MemoryEntry.MemoryType type) {
        MemoryEntry.MemoryType effective = type == null ? MemoryEntry.MemoryType.FACT : type;
        long fallback = switch (effective) {
            case FACT -> 120L;
            case FEEDBACK -> 240L;
            case SUMMARY -> 21L;
            case CONVERSATION, TOOL_RESULT -> 3L;
        };
        String suffix = effective.name().toLowerCase(Locale.ROOT);
        String value = configured("devcli.memory.freshness." + suffix + ".half.life.days",
                "DEVCLI_MEMORY_FRESHNESS_" + suffix.toUpperCase(Locale.ROOT) + "_HALF_LIFE_DAYS");
        if (value == null || value.isBlank()) return fallback;
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String configured(String property, String environment) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? System.getenv(environment) : value;
    }
}
