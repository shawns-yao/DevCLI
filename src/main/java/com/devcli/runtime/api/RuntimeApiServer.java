package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RuntimeApiServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_HTTP_THREADS = 16;
    private static final int DEFAULT_TURN_THREADS = 2;
    private static final int DEFAULT_TURN_QUEUE_SIZE = 64;

    private final RuntimeThreadStore store;
    private final TurnRunner runner;
    private final String apiKey;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final ThreadPoolExecutor turnExecutor;
    private final KeyedSerialExecutor serialTurnExecutor;

    public RuntimeApiServer(RuntimeThreadStore store, TurnRunner runner, int port, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Runtime API 需要配置 DEVCLI_RUNTIME_API_KEY 或 -Ddevcli.runtime.api.key");
        }
        this.store = store;
        this.runner = runner;
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.httpExecutor = Executors.newFixedThreadPool(configuredPositiveInt(
                "devcli.runtime.api.http.threads", DEFAULT_HTTP_THREADS), daemonThreadFactory("devcli-runtime-api-http"));
        int turnThreads = configuredPositiveInt(
                "devcli.runtime.api.turn.threads", DEFAULT_TURN_THREADS);
        int turnQueueSize = configuredPositiveInt(
                "devcli.runtime.api.turn.queue", DEFAULT_TURN_QUEUE_SIZE);
        this.turnExecutor = new ThreadPoolExecutor(
                turnThreads,
                turnThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(turnQueueSize),
                daemonThreadFactory("devcli-runtime-api-turn"),
                new ThreadPoolExecutor.AbortPolicy());
        this.serialTurnExecutor = new KeyedSerialExecutor(
                turnExecutor, turnThreads + turnQueueSize);
        this.server.createContext("/v1/threads", this::handleThreads);
        this.server.setExecutor(httpExecutor);
    }

    public static String configuredApiKey() {
        String configured = System.getProperty("devcli.runtime.api.key");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_RUNTIME_API_KEY");
        }
        return configured;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                writeJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v1/threads".equals(path)) {
                String id = store.createThread();
                writeJson(exchange, 200, "{\"id\":\"" + id + "\",\"object\":\"thread\"}");
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/threads/[^/]+/turns")) {
                handleTurn(exchange, threadId(path));
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/threads/[^/]+/events")) {
                handleEvents(exchange, threadId(path));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            writeJson(exchange, 500, "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private void handleTurn(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        String input = body.path("input").asText("");
        if (input.isBlank()) {
            writeJson(exchange, 400, "{\"error\":\"input_required\"}");
            return;
        }
        String turnId = "turn_" + Long.toHexString(System.nanoTime());
        try {
            serialTurnExecutor.execute(threadId,
                    () -> runTurn(threadId, turnId, input),
                    fatal -> new RuntimeEventPublisher(store, threadId, turnId)
                            .emit(new RunEvent.TurnFailed("fatal_runtime_error")));
        } catch (RejectedExecutionException e) {
            new RuntimeEventPublisher(store, threadId, turnId)
                    .emit(new RunEvent.TurnRejected("runtime_busy"));
            writeJson(exchange, 429, "{\"error\":\"runtime_busy\"}");
            return;
        }
        writeJson(exchange, 202, "{\"id\":\"" + turnId + "\",\"object\":\"turn\",\"status\":\"running\"}");
    }

    private void runTurn(String threadId, String turnId, String input) {
        RuntimeEventPublisher events = new RuntimeEventPublisher(store, threadId, turnId);
        try {
            events.emit(new RunEvent.TurnStarted(input));
            TurnRunner.TurnResult runResult = runner.run(threadId, input, events);
            if (runResult == null) {
                runResult = TurnRunner.TurnResult.completed("");
            }
            if (!events.hasMessageDelta()) {
                events.emit(new RunEvent.MessageDelta(runResult.output()));
            }
            long completedEventId = events.publish(new RunEvent.TurnCompleted("completed"));
            persistCheckpoint(events, threadId, completedEventId, runResult.checkpoint());
        } catch (Exception e) {
            events.emit(new RunEvent.TurnFailed(e.getMessage()));
        }
    }

    private void persistCheckpoint(RuntimeEventPublisher events, String threadId,
                                   long completedEventId,
                                   TurnRunner.CheckpointCandidate checkpoint) {
        if (checkpoint == null) return;
        try {
            store.saveCheckpoint(threadId, completedEventId, checkpoint);
            events.emit(new RunEvent.CheckpointCreated(
                    completedEventId,
                    checkpoint.metadata().preTokens(),
                    checkpoint.metadata().postTokens(),
                    checkpoint.metadata().semanticGuardStatus()));
        } catch (Exception e) {
            events.emit(new RunEvent.CheckpointFailed(completedEventId, e.getMessage()));
        }
    }

    private void handleEvents(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        long after = parseAfter(exchange.getRequestURI().getQuery());
        List<RuntimeEvent> events = store.events(threadId, after);
        byte[] body = formatSse(events).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String direct = exchange.getRequestHeaders().getFirst("X-DevCLI-API-Key");
        return ("Bearer " + apiKey).equals(auth) || apiKey.equals(direct);
    }

    private static String threadId(String path) {
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static long parseAfter(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("after=")) {
                try {
                    return Long.parseLong(part.substring("after=".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatSse(List<RuntimeEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (RuntimeEvent event : events) {
            sb.append("id: ").append(event.id()).append('\n');
            sb.append("event: ").append(event.type()).append('\n');
            sb.append("data: ").append(event.data()).append("\n\n");
        }
        return sb.toString();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static int configuredPositiveInt(String propertyName, int defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger threadId = new AtomicInteger();
        return r -> {
            Thread thread = new Thread(r, prefix + "-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        server.stop(0);
        turnExecutor.shutdownNow();
        httpExecutor.shutdownNow();
    }
}
