package com.devcli.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在一组 ToolRegistry 之间共享单个后台线程，定时回收过期资源租约。
 */
public final class ResourceLeaseMaintenance implements AutoCloseable {
    public static final String INTERVAL_PROPERTY =
            "devcli.resource.lease.cleanup.interval.seconds";
    public static final String INTERVAL_ENV =
            "DEVCLI_RESOURCE_LEASE_CLEANUP_INTERVAL_SECONDS";
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(ResourceLeaseMaintenance.class);
    /** 所有维护实例共享一个可重建的守护执行器。 */
    private static final Object SHARED_SCHEDULER_LOCK = new Object();
    private static ScheduledExecutorService sharedScheduler;
    private static int sharedSchedulerUsers;

    private final Set<ResourceLeaseManager> managers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> scheduledTask;
    private final AtomicInteger registrations = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResourceLeaseMaintenance() {
        this(resolveInterval(System.getProperties(), System.getenv()));
    }

    ResourceLeaseMaintenance(Duration interval) {
        Duration normalized = interval == null || interval.isZero() || interval.isNegative()
                ? DEFAULT_INTERVAL : interval;
        long delayMillis = Math.max(1, normalized.toMillis());
        scheduler = acquireSharedScheduler();
        try {
            scheduledTask = scheduler.scheduleWithFixedDelay(this::pruneAll,
                    delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            releaseSharedScheduler(scheduler);
            throw error;
        }
    }

    synchronized Registration attach(ResourceLeaseManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("resource lease manager is required");
        }
        if (closed.get()) {
            throw new IllegalStateException("resource lease maintenance is closed");
        }
        if (!managers.add(manager)) {
            throw new IllegalStateException("resource lease manager is already registered");
        }
        registrations.incrementAndGet();
        return new Registration(this, manager);
    }

    private synchronized void detach(ResourceLeaseManager manager) {
        if (!managers.remove(manager)) {
            return;
        }
        if (registrations.decrementAndGet() == 0) {
            close();
        }
    }

    private void pruneAll() {
        for (ResourceLeaseManager manager : managers) {
            try {
                manager.pruneExpiredLeases();
            } catch (RuntimeException e) {
                log.warn("后台清理资源租约失败: {}", e.getMessage());
            }
        }
    }

    int registrationCount() {
        return registrations.get();
    }

    int managerCount() {
        return managers.size();
    }

    boolean isClosed() {
        return closed.get();
    }

    boolean isTerminated() {
        return scheduledTask.isCancelled() || scheduledTask.isDone() || scheduler.isTerminated();
    }

    static Duration resolveInterval(Properties properties, Map<String, String> environment) {
        String value = properties == null ? null : properties.getProperty(INTERVAL_PROPERTY);
        if (value == null || value.isBlank()) {
            value = environment == null ? null : environment.get(INTERVAL_ENV);
        }
        if (value == null || value.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? DEFAULT_INTERVAL : Duration.ofSeconds(seconds);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return DEFAULT_INTERVAL;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        managers.clear();
        registrations.set(0);
        scheduledTask.cancel(false);
        releaseSharedScheduler(scheduler);
    }

    private static ScheduledExecutorService acquireSharedScheduler() {
        synchronized (SHARED_SCHEDULER_LOCK) {
            if (sharedScheduler == null || sharedScheduler.isShutdown()) {
                sharedScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "devcli-resource-lease-cleaner");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            sharedSchedulerUsers++;
            return sharedScheduler;
        }
    }

    private static void releaseSharedScheduler(ScheduledExecutorService scheduler) {
        synchronized (SHARED_SCHEDULER_LOCK) {
            if (sharedScheduler != scheduler) return;
            sharedSchedulerUsers = Math.max(0, sharedSchedulerUsers - 1);
            if (sharedSchedulerUsers != 0) return;
            ScheduledExecutorService closing = sharedScheduler;
            sharedScheduler = null;
            closing.shutdownNow();
            try {
                if (!closing.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("资源租约共享清理线程未在超时内终止");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class Registration implements AutoCloseable {
        private final ResourceLeaseMaintenance owner;
        private final ResourceLeaseManager manager;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(ResourceLeaseMaintenance owner, ResourceLeaseManager manager) {
            this.owner = owner;
            this.manager = manager;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.detach(manager);
            }
        }
    }
}
