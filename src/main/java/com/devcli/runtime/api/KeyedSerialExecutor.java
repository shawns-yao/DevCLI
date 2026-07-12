package com.devcli.runtime.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相同 key 串行、不同 key 可并行的有界执行器。
 */
final class KeyedSerialExecutor {
    private final Executor executor;
    private final int capacity;
    private final AtomicInteger pending = new AtomicInteger();
    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();

    KeyedSerialExecutor(Executor executor, int capacity) {
        this.executor = Objects.requireNonNull(executor, "executor");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    void execute(String key, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        reserve();

        Lane lane = lanes.computeIfAbsent(key, ignored -> new Lane());
        boolean schedule;
        synchronized (lane) {
            lane.tasks.addLast(task);
            schedule = !lane.running;
            if (schedule) {
                lane.running = true;
            }
        }
        if (!schedule) {
            return;
        }

        try {
            executor.execute(() -> drain(key, lane));
        } catch (RejectedExecutionException e) {
            int released;
            synchronized (lane) {
                released = lane.tasks.size();
                lane.tasks.clear();
                lane.running = false;
            }
            pending.addAndGet(-released);
            lanes.remove(key, lane);
            throw e;
        }
    }

    int pendingCount() {
        return pending.get();
    }

    private void reserve() {
        int current = pending.incrementAndGet();
        if (current > capacity) {
            pending.decrementAndGet();
            throw new RejectedExecutionException("serial executor queue is full");
        }
    }

    private void drain(String key, Lane lane) {
        while (true) {
            Runnable task;
            synchronized (lane) {
                task = lane.tasks.pollFirst();
                if (task == null) {
                    lane.running = false;
                    lanes.remove(key, lane);
                    return;
                }
            }
            try {
                task.run();
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private static final class Lane {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;
    }
}
