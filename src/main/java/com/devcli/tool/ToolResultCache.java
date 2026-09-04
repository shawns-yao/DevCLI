package com.devcli.tool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class ToolResultCache {
    private final int maxEntries;
    private final long ttlMillis;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    ToolResultCache() {
        this(readInt("devcli.tool.result.cache.max.entries", "DEVCLI_TOOL_RESULT_CACHE_MAX_ENTRIES", 128),
                Duration.ofSeconds(readInt("devcli.tool.result.cache.ttl.seconds",
                        "DEVCLI_TOOL_RESULT_CACHE_TTL_SECONDS", 30)));
    }

    ToolResultCache(int maxEntries, Duration ttl) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(1L, ttl == null ? 30_000L : ttl.toMillis());
    }

    synchronized ToolOutput get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.createdAtMillis() > ttlMillis) {
            entries.remove(key);
            return null;
        }
        return entry.output();
    }

    synchronized void put(String key, ToolOutput output) {
        if (key == null || output == null || !output.isSuccess() || output.hasImageParts()) return;
        if (output.text().contains("result_ref=")
                || output.sideChannels().stream().anyMatch(ToolResultArtifact.class::isInstance)) {
            return;
        }
        entries.put(key, new Entry(output, System.currentTimeMillis()));
        while (entries.size() > maxEntries) {
            String eldest = entries.entrySet().iterator().next().getKey();
            entries.remove(eldest);
        }
    }

    synchronized void clear() {
        entries.clear();
    }

    private static int readInt(String key, String environment, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        try {
            return Math.max(1, Integer.parseInt(value == null || value.isBlank()
                    ? String.valueOf(fallback) : value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Entry(ToolOutput output, long createdAtMillis) {
    }
}
