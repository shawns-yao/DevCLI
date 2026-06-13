package com.devcli.runtime.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeApiServerTest {

    @Test
    void exposesThreadTurnAndSseEvents(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, (threadId, prompt) -> "reply:" + prompt, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode());
            String threadId = extract(created.body(), "thread_");

            HttpResponse<String> turn = client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"hello\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turn.statusCode());

            String events = waitForEvents(client, base, threadId);
            assertTrue(events.contains("event: turn.started"));
            assertTrue(events.contains("event: message.delta"));
            assertTrue(events.contains("reply:hello"));
            assertTrue(events.contains("event: turn.completed"));
        }
    }

    @Test
    void rejectsMissingApiKey(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, (threadId, prompt) -> "x", 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/threads"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
        }
    }

    @Test
    void passesThreadIdToTurnRunnerForHistoryReplay(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> seenThreadId = new AtomicReference<>();
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, (threadId, prompt) -> {
                 seenThreadId.set(threadId);
                 // 执行侧按 threadId 重放历史：第二轮应能看到第一轮的输入输出
                 List<RuntimeThreadStore.TurnRecord> history = store.turnHistory(threadId);
                 return "history=" + history.size() + ";reply:" + prompt;
             }, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            String threadId = extract(created.body(), "thread_");

            client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                    "{\"input\":\"first\"}").build(), HttpResponse.BodyHandlers.ofString());
            String firstEvents = waitForEvents(client, base, threadId);
            assertTrue(firstEvents.contains("history=0;reply:first"));
            assertEquals(threadId, seenThreadId.get());

            client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                    "{\"input\":\"second\"}").build(), HttpResponse.BodyHandlers.ofString());
            String secondEvents = waitForSecondTurn(client, base, threadId);
            assertTrue(secondEvents.contains("history=1;reply:second"),
                    "第二轮应看到第一轮历史: " + secondEvents);
        }
    }

    private static String waitForSecondTurn(HttpClient client, String base, String threadId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request(base + "/v1/threads/" + threadId + "/events", "GET", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("reply:second")) {
                return response.body();
            }
            Thread.sleep(30);
        }
        fail("second turn did not complete");
        return "";
    }

    private static HttpRequest.Builder request(String url, String method, String body) {
        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer secret")
                .header("Content-Type", "application/json")
                .method(method, publisher);
    }

    private static String waitForEvents(HttpClient client, String base, String threadId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request(base + "/v1/threads/" + threadId + "/events", "GET", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("turn.completed")) {
                return response.body();
            }
            Thread.sleep(30);
        }
        fail("events did not complete");
        return "";
    }

    private static String extract(String body, String prefix) {
        int start = body.indexOf(prefix);
        assertTrue(start >= 0, body);
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
