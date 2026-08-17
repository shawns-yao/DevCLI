package com.devcli.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 可级联、可监听并能保留取消原因的运行令牌。 */
public class CancellationToken implements AutoCloseable {
    private final AtomicReference<CancellationReason> reason =
            new AtomicReference<>(CancellationReason.NONE);
    private final AtomicLong listenerSequence = new AtomicLong();
    private final ConcurrentHashMap<Long, Runnable> listeners = new ConcurrentHashMap<>();
    private final Registration parentRegistration;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public CancellationToken() {
        this(null);
    }

    private CancellationToken(CancellationToken parent) {
        this.parentRegistration = parent == null
                ? null
                : parent.onCancel(() -> cancel(CancellationReason.PARENT_CANCELLED));
        if (parent != null && parent.isCancelled()) {
            cancel(CancellationReason.PARENT_CANCELLED);
        }
    }

    public CancellationToken child() {
        return new CancellationToken(this);
    }

    public boolean cancel() {
        return cancel(CancellationReason.USER_REQUEST);
    }

    public boolean cancel(CancellationReason cancellationReason) {
        CancellationReason effective = cancellationReason == null
                || cancellationReason == CancellationReason.NONE
                ? CancellationReason.USER_REQUEST
                : cancellationReason;
        if (!reason.compareAndSet(CancellationReason.NONE, effective)) {
            return false;
        }
        listeners.values().forEach(listener -> {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // 一个取消回调失败不能阻断其他资源的终止。
            }
        });
        return true;
    }

    public boolean isCancelled() {
        return reason.get() != CancellationReason.NONE || Thread.currentThread().isInterrupted();
    }

    public CancellationReason reason() {
        CancellationReason current = reason.get();
        return current == CancellationReason.NONE && Thread.currentThread().isInterrupted()
                ? CancellationReason.THREAD_INTERRUPTED
                : current;
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("运行已取消: " + reason());
        }
    }

    public Registration onCancel(Runnable listener) {
        if (listener == null) {
            return Registration.NO_OP;
        }
        long id = listenerSequence.incrementAndGet();
        listeners.put(id, listener);
        if (isCancelled()) {
            Runnable registered = listeners.remove(id);
            if (registered != null) {
                try {
                    registered.run();
                } catch (RuntimeException ignored) {
                    // 注册时发现已取消，回调失败同样不能阻断取消传播。
                }
            }
            return Registration.NO_OP;
        }
        return new Registration(() -> listeners.remove(id));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (parentRegistration != null) {
            parentRegistration.close();
        }
        listeners.clear();
    }

    public static final class Registration implements AutoCloseable {
        private static final Registration NO_OP = new Registration(() -> { });
        private final Runnable closer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Registration(Runnable closer) {
            this.closer = closer;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closer.run();
            }
        }
    }
}
