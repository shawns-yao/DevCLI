package com.devcli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.mcp.transport.McpTransport;
import com.devcli.runtime.CancellationToken;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final McpTransport transport;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "devcli-mcp-jsonrpc-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public JsonRpcClient(McpTransport transport) {
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        return request(method, params, timeoutSeconds, null);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds,
                            CancellationToken cancellationToken) throws IOException {
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            if (cancellationToken != null) {
                cancellationToken.cancel(
                        CancellationToken.Reason.TIMEOUT,
                        "JSON-RPC request timed out: " + method);
                return;
            }
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutSeconds, TimeUnit.SECONDS);

        CancellationToken.Registration cancellationRegistration = cancellationToken == null
                ? CancellationToken.Registration.NO_OP
                : cancellationToken.onCancel(cancellation -> {
                    timeoutTask.cancel(false);
                    try {
                        transport.sendCancellation(cancellationNotification(
                                id, cancellation.message()));
                    } catch (IOException ignored) {
                        // Cancellation remains authoritative even if the peer is already unreachable.
                    }
                });

        try {
            transport.send(request, cancellationToken);
            return awaitResponse(future, cancellationToken, timeoutSeconds);
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            throw new IOException(e.getMessage(), e);
        } finally {
            timeoutTask.cancel(false);
            cancellationRegistration.close();
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    private static ObjectNode cancellationNotification(long requestId, String reason) {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/cancelled");
        ObjectNode params = notification.putObject("params");
        params.put("requestId", requestId);
        if (reason != null && !reason.isBlank()) {
            params.put("reason", reason);
        }
        return notification;
    }

    private static JsonNode awaitResponse(
            CompletableFuture<JsonNode> future,
            CancellationToken cancellationToken,
            long timeoutSeconds) throws Exception {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (cancellationToken != null && cancellationToken.isCancelled()) {
                        return future.get();
                    }
                    return future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (cancellationToken == null || !cancellationToken.isCancelled()) {
                        throw e;
                    }
                    Thread.interrupted();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    public void removeNotificationListener(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.remove(listener);
        }
    }

    private void handleMessage(JsonNode message) {
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            for (Consumer<JsonNode> listener : notificationListeners) {
                listener.accept(message);
            }
            return;
        }
        long id = idNode.asLong();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        IOException closed = new IOException("JSON-RPC client closed");
        pending.values().forEach(future -> future.completeExceptionally(closed));
        pending.clear();
        transport.close();
    }
}
