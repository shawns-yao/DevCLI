package com.devcli.llm;

import com.devcli.runtime.CancellationToken;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 协调并发模型采样请求的身份、替换和独立取消。 */
public final class SamplingRequestCoordinator {
    private static final SamplingRequestCoordinator SHARED = new SamplingRequestCoordinator();
    private static final InheritableThreadLocal<RequestScope> CURRENT = new InheritableThreadLocal<>();

    private final ConcurrentMap<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();

    public static SamplingRequestCoordinator shared() {
        return SHARED;
    }

    public RequestScope begin(String requestId) {
        String normalizedId = normalizeRequestId(requestId);
        RequestScope previous = CURRENT.get();
        ActiveRequest request = new ActiveRequest(
                normalizedId,
                UUID.randomUUID().toString(),
                new CancellationToken(),
                Thread.currentThread());
        ActiveRequest replaced = activeRequests.put(normalizedId, request);
        if (replaced != null) {
            cancelRequest(replaced);
        }
        RequestScope scope = new RequestScope(this, request, previous);
        CURRENT.set(scope);
        return scope;
    }

    public boolean cancel(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        ActiveRequest request = activeRequests.get(requestId.trim());
        if (request == null) {
            return false;
        }
        cancelRequest(request);
        return true;
    }

    public int activeCount() {
        return activeRequests.size();
    }

    public Optional<RequestSnapshot> find(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        ActiveRequest request = activeRequests.get(requestId.trim());
        return request == null ? Optional.empty() : Optional.of(request.snapshot());
    }

    public static boolean isCurrentCancelled() {
        RequestScope scope = CURRENT.get();
        return scope != null && scope.isCancelled();
    }

    private void close(RequestScope scope) {
        activeRequests.remove(scope.request.requestId, scope.request);
        if (CURRENT.get() == scope) {
            RequestScope previous = scope.previous;
            while (previous != null && previous.closed.get()) {
                previous = previous.previous;
            }
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private static void cancelRequest(ActiveRequest request) {
        request.token.cancel();
        Thread owner = request.owner;
        if (owner != Thread.currentThread()) {
            owner.interrupt();
        }
    }

    private static String normalizeRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        return normalized.isEmpty() ? "sample_" + UUID.randomUUID() : normalized;
    }

    private record ActiveRequest(String requestId, String generation,
                                 CancellationToken token, Thread owner) {
        RequestSnapshot snapshot() {
            return new RequestSnapshot(requestId, generation, token.isCancelled(), owner.getName());
        }
    }

    public record RequestSnapshot(String requestId, String generation,
                                  boolean cancelled, String ownerThread) {
    }

    public static final class RequestScope implements AutoCloseable {
        private final SamplingRequestCoordinator coordinator;
        private final ActiveRequest request;
        private final RequestScope previous;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RequestScope(SamplingRequestCoordinator coordinator,
                             ActiveRequest request,
                             RequestScope previous) {
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
            this.request = Objects.requireNonNull(request, "request");
            this.previous = previous;
        }

        public String requestId() {
            return request.requestId;
        }

        public CancellationToken cancellationToken() {
            return request.token;
        }

        public boolean isCancelled() {
            return request.token.isCancelled();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                coordinator.close(this);
            }
        }
    }
}
