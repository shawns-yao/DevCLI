package com.devcli.cli.turn;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 当前 CLI 会话内的有界提示词队列。 */
public final class PromptQueue {
    private final int capacity;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextSequence = 1;

    public PromptQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("提示词队列容量必须大于 0");
        }
        this.capacity = capacity;
    }

    public synchronized EnqueueResult enqueue(String text) {
        return enqueue(text, false);
    }

    public synchronized EnqueueResult enqueueFirst(String text) {
        return enqueue(text, true);
    }

    public synchronized Optional<Entry> poll() {
        return Optional.ofNullable(entries.pollFirst());
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(entries));
    }

    private EnqueueResult enqueue(String text, boolean first) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return EnqueueResult.rejected("提示词不能为空");
        }
        if (entries.size() >= capacity) {
            return EnqueueResult.rejected("提示词队列已满");
        }
        Entry entry = new Entry(nextSequence++, normalized, Instant.now());
        if (first) {
            entries.addFirst(entry);
        } else {
            entries.addLast(entry);
        }
        return EnqueueResult.accepted(entry);
    }

    public record Entry(long sequence, String text, Instant queuedAt) {
    }

    public record EnqueueResult(boolean accepted, Entry entry, String reason) {
        private static EnqueueResult accepted(Entry entry) {
            return new EnqueueResult(true, entry, "");
        }

        private static EnqueueResult rejected(String reason) {
            return new EnqueueResult(false, null, reason);
        }
    }
}
