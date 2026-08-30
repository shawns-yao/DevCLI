package com.devcli.memory;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Bounded parsed-memory working set. It is a cache, never the retrieval universe. */
final class MemoryHotCache {
    static final int DEFAULT_MAX_ENTRIES = 200;

    private final int maxEntries;
    private final Map<String, Slot> slots = new HashMap<>();
    private long sequence;

    MemoryHotCache() {
        this(readMaxEntries());
    }

    MemoryHotCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    synchronized Optional<MemoryEntry> get(String id) {
        Slot slot = slots.get(id);
        if (slot == null) return Optional.empty();
        slot.localAccessCount++;
        slot.lastAccessSequence = ++sequence;
        return Optional.of(slot.entry);
    }

    synchronized Optional<MemoryEntry> peek(String id) {
        Slot slot = slots.get(id);
        return slot == null ? Optional.empty() : Optional.of(slot.entry);
    }

    synchronized void put(MemoryEntry entry) {
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) return;
        Slot previous = slots.get(entry.getId());
        Slot slot = new Slot(entry,
                previous == null ? Math.max(1, entry.getRecallCount()) : previous.localAccessCount + 1,
                ++sequence, importanceOf(entry), pinned(entry));
        slots.put(entry.getId(), slot);
        evictIfNeeded();
    }

    synchronized void preload(MemoryEntry entry) {
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) return;
        slots.put(entry.getId(), new Slot(entry, entry.getRecallCount(), ++sequence,
                importanceOf(entry), pinned(entry)));
        evictIfNeeded();
    }

    synchronized void remove(String id) {
        slots.remove(id);
    }

    synchronized void clear() {
        slots.clear();
    }

    synchronized int size() {
        return slots.size();
    }

    synchronized boolean contains(String id) {
        return slots.containsKey(id);
    }

    synchronized java.util.Set<String> ids() {
        return java.util.Set.copyOf(slots.keySet());
    }

    int capacity() {
        return maxEntries;
    }

    private void evictIfNeeded() {
        while (slots.size() > maxEntries) {
            boolean hasUnpinned = slots.values().stream().anyMatch(slot -> !slot.pinned);
            Slot victim = slots.values().stream()
                    .filter(slot -> !hasUnpinned || !slot.pinned)
                    .min(Comparator.comparingDouble((Slot slot) -> slot.importance)
                            .thenComparingLong(slot -> slot.localAccessCount)
                            .thenComparingLong(slot -> slot.lastAccessSequence))
                    .orElseThrow();
            slots.remove(victim.entry.getId());
        }
    }

    private static boolean pinned(MemoryEntry entry) {
        return Boolean.parseBoolean(entry.getMetadata().getOrDefault("pinned", "false"));
    }

    static double importanceOf(MemoryEntry entry) {
        String raw = entry.getMetadata().getOrDefault("importance", "").trim();
        if (raw.isBlank()) return entry.getValidatedUseCount() > 0 ? 0.6 : 0.3;
        try {
            return Math.max(0, Math.min(1, Double.parseDouble(raw)));
        } catch (NumberFormatException ignored) {
            return switch (raw.toUpperCase(Locale.ROOT)) {
                case "CRITICAL" -> 1.0;
                case "HIGH" -> 0.8;
                case "MEDIUM" -> 0.5;
                case "LOW" -> 0.2;
                default -> 0.3;
            };
        }
    }

    private static int readMaxEntries() {
        String value = System.getProperty("devcli.memory.hot.max.entries");
        if (value == null || value.isBlank()) value = System.getenv("DEVCLI_MEMORY_HOT_MAX_ENTRIES");
        try {
            return value == null || value.isBlank()
                    ? DEFAULT_MAX_ENTRIES : Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_ENTRIES;
        }
    }

    private static final class Slot {
        private final MemoryEntry entry;
        private long localAccessCount;
        private long lastAccessSequence;
        private final double importance;
        private final boolean pinned;

        private Slot(MemoryEntry entry, long localAccessCount, long lastAccessSequence,
                     double importance, boolean pinned) {
            this.entry = entry;
            this.localAccessCount = Math.max(0, localAccessCount);
            this.lastAccessSequence = lastAccessSequence;
            this.importance = importance;
            this.pinned = pinned;
        }
    }
}
