package com.devcli.runtime;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 可协作取消信号。首次取消原因不可覆盖，监听器用于终止进程、网络请求等外部执行。
 */
public class CancellationToken implements AutoCloseable {
    public enum Reason {
        USER,
        TIMEOUT,
        UPSTREAM,
        INTERRUPTED
    }

    public record Cancellation(Reason reason, String message) {
        public Cancellation {
            reason = reason == null ? Reason.USER : reason;
            message = message == null ? "" : message;
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        Registration NO_OP = () -> { };

        @Override
        void close();
    }

    private final AtomicReference<Cancellation> cancellation = new AtomicReference<>();
    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
    private final Registration parentRegistration;

    public CancellationToken() {
        this.parentRegistration = Registration.NO_OP;
    }

    private CancellationToken(CancellationToken parent) {
        this.parentRegistration = parent == null
                ? Registration.NO_OP
                : parent.onCancel(parentCancellation -> cancel(
                        Reason.UPSTREAM,
                        parentCancellation.message().isBlank()
                                ? "上游运行已取消"
                                : parentCancellation.message()));
    }

    public CancellationToken childToken() {
        return new CancellationToken(this);
    }

    public void cancel() {
        cancel(Reason.USER, "用户取消了此次运行");
    }

    public boolean cancel(Reason reason, String message) {
        Cancellation next = new Cancellation(reason, message);
        if (!cancellation.compareAndSet(null, next)) {
            return false;
        }
        for (ListenerRegistration listener : listeners) {
            listener.fire(next);
        }
        listeners.clear();
        return true;
    }

    public boolean isCancelled() {
        return cancellation.get() != null || Thread.currentThread().isInterrupted();
    }

    public Optional<Cancellation> cancellation() {
        Cancellation current = cancellation.get();
        if (current != null) {
            return Optional.of(current);
        }
        if (Thread.currentThread().isInterrupted()) {
            return Optional.of(new Cancellation(Reason.INTERRUPTED, "执行线程已中断"));
        }
        return Optional.empty();
    }

    public Registration onCancel(Consumer<Cancellation> listener) {
        if (listener == null) {
            return Registration.NO_OP;
        }
        ListenerRegistration registration = new ListenerRegistration(listener);
        Cancellation current = cancellation.get();
        if (current != null) {
            registration.fire(current);
            return Registration.NO_OP;
        }
        listeners.add(registration);
        current = cancellation.get();
        if (current != null && listeners.remove(registration)) {
            registration.fire(current);
        }
        return () -> listeners.remove(registration);
    }

    @Override
    public void close() {
        parentRegistration.close();
        listeners.clear();
    }

    private static final class ListenerRegistration {
        private final Consumer<Cancellation> listener;
        private final AtomicBoolean fired = new AtomicBoolean(false);

        private ListenerRegistration(Consumer<Cancellation> listener) {
            this.listener = listener;
        }

        private void fire(Cancellation value) {
            if (fired.compareAndSet(false, true)) {
                listener.accept(value);
            }
        }
    }
}
