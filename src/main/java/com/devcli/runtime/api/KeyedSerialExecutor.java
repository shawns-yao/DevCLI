package com.devcli.runtime.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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

        AtomicBoolean schedule = new AtomicBoolean();
        Lane lane = lanes.compute(key, (ignored, current) -> {
            Lane selected = current == null ? new Lane() : current;
            synchronized (selected) {
                selected.tasks.addLast(task);
                if (!selected.running) {
                    selected.running = true;
                    selected.scheduling = true;
                    selected.schedulingFailure = null;
                    schedule.set(true);
                }
            }
            return selected;
        });
        if (!schedule.get()) {
            awaitSchedulingOutcome(lane);
            return;
        }

        try {
            executor.execute(() -> drain(key, lane));
            completeScheduling(lane);
        } catch (RejectedExecutionException e) {
            rejectScheduling(key, lane, e);
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
        completeScheduling(lane);
        Throwable taskFailure = null;
        while (true) {
            Runnable task;
            synchronized (lane) {
                task = lane.tasks.pollFirst();
            }
            if (task == null) {
                if (retireLane(key, lane)) {
                    rethrow(taskFailure);
                    return;
                }
                continue;
            }
            try {
                task.run();
            } catch (Throwable failure) {
                if (taskFailure == null) {
                    taskFailure = failure;
                } else {
                    taskFailure.addSuppressed(failure);
                }
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("serial task failed", failure);
    }

    private boolean retireLane(String key, Lane lane) {
        AtomicBoolean retired = new AtomicBoolean();
        lanes.compute(key, (ignored, current) -> {
            if (current != lane) {
                retired.set(true);
                return current;
            }
            synchronized (lane) {
                if (!lane.tasks.isEmpty()) {
                    return lane;
                }
                lane.running = false;
                retired.set(true);
                return null;
            }
        });
        return retired.get();
    }

    private void completeScheduling(Lane lane) {
        synchronized (lane) {
            if (!lane.scheduling) {
                return;
            }
            lane.scheduling = false;
            lane.notifyAll();
        }
    }

    private void rejectScheduling(String key, Lane lane, RejectedExecutionException failure) {
        AtomicInteger released = new AtomicInteger();
        lanes.compute(key, (ignored, current) -> {
            if (current != lane) {
                return current;
            }
            synchronized (lane) {
                if (!lane.scheduling) {
                    return lane;
                }
                released.set(lane.tasks.size());
                lane.tasks.clear();
                lane.running = false;
                lane.scheduling = false;
                lane.schedulingFailure = failure;
                lane.notifyAll();
                return null;
            }
        });
        pending.addAndGet(-released.get());
    }

    private void awaitSchedulingOutcome(Lane lane) {
        boolean interrupted = false;
        synchronized (lane) {
            while (lane.scheduling) {
                try {
                    lane.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (lane.schedulingFailure != null) {
                throw lane.schedulingFailure;
            }
        }
    }

    private static final class Lane {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean running;
        private boolean scheduling;
        private RejectedExecutionException schedulingFailure;
    }
}
