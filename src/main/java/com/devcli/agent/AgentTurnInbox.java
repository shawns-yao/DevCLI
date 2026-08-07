package com.devcli.agent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行层的双通道消息收件箱。
 * Steering 在当前工具批次完成后注入，Follow-up 在 Agent 原本准备结束时注入。
 */
public final class AgentTurnInbox {
    public static final int DEFAULT_CAPACITY = 8;

    private final int capacity;
    private final ArrayDeque<Item> steering = new ArrayDeque<>();
    private final ArrayDeque<Item> followUp = new ArrayDeque<>();
    private QueueMode steeringMode = QueueMode.ONE_AT_A_TIME;
    private QueueMode followUpMode = QueueMode.ONE_AT_A_TIME;
    private long nextSequence = 1;

    public AgentTurnInbox() {
        this(DEFAULT_CAPACITY);
    }

    public AgentTurnInbox(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("消息收件箱容量必须大于 0");
        }
        this.capacity = capacity;
    }

    public synchronized EnqueueResult enqueueSteering(String text) {
        return enqueue(Channel.STEERING, text);
    }

    public synchronized EnqueueResult enqueueFollowUp(String text) {
        return enqueue(Channel.FOLLOW_UP, text);
    }

    public synchronized List<Item> drainSteering() {
        return drain(steering, steeringMode);
    }

    public synchronized List<Item> drainFollowUp() {
        return drain(followUp, followUpMode);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(steering), List.copyOf(followUp), capacity);
    }

    public synchronized Snapshot clear() {
        Snapshot previous = snapshot();
        steering.clear();
        followUp.clear();
        return previous;
    }

    public synchronized boolean hasMessages() {
        return !steering.isEmpty() || !followUp.isEmpty();
    }

    public synchronized void setSteeringMode(QueueMode mode) {
        steeringMode = mode == null ? QueueMode.ONE_AT_A_TIME : mode;
    }

    public synchronized void setFollowUpMode(QueueMode mode) {
        followUpMode = mode == null ? QueueMode.ONE_AT_A_TIME : mode;
    }

    private EnqueueResult enqueue(Channel channel, String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return new EnqueueResult(false, null, "消息不能为空", snapshot());
        }
        if (steering.size() + followUp.size() >= capacity) {
            return new EnqueueResult(false, null, "消息收件箱已满", snapshot());
        }
        Item item = new Item(nextSequence++, channel, normalized, Instant.now());
        queue(channel).addLast(item);
        return new EnqueueResult(true, item, "", snapshot());
    }

    private ArrayDeque<Item> queue(Channel channel) {
        return channel == Channel.STEERING ? steering : followUp;
    }

    private static List<Item> drain(ArrayDeque<Item> queue, QueueMode mode) {
        if (queue.isEmpty()) {
            return List.of();
        }
        if (mode == QueueMode.ONE_AT_A_TIME) {
            return List.of(queue.removeFirst());
        }
        List<Item> drained = new ArrayList<>(queue);
        queue.clear();
        return List.copyOf(drained);
    }

    public enum Channel {
        STEERING,
        FOLLOW_UP
    }

    public enum QueueMode {
        ONE_AT_A_TIME,
        ALL
    }

    public record Item(long sequence, Channel channel, String text, Instant queuedAt) {
    }

    public record Snapshot(List<Item> steering, List<Item> followUp, int capacity) {
        public Snapshot {
            steering = steering == null ? List.of() : List.copyOf(steering);
            followUp = followUp == null ? List.of() : List.copyOf(followUp);
        }

        public int size() {
            return steering.size() + followUp.size();
        }
    }

    public record EnqueueResult(boolean accepted, Item item, String reason, Snapshot snapshot) {
    }
}
