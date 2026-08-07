package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEvent;
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
             RuntimeApiServer server = new RuntimeApiServer(store,
                     (threadId, prompt, eventSink) -> TurnRunner.TurnResult.completed("reply:" + prompt),
                     0, "secret")) {
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
    void persistsTypedStreamingEventsWithoutDuplicatingFinalOutput(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store,
                     (threadId, prompt, eventSink) -> {
                         eventSink.emit(new RunEvent.ReasoningDelta("analysis"));
                         eventSink.emit(new RunEvent.MessageDelta("streamed reply"));
                         return TurnRunner.TurnResult.completed("fallback reply");
                     }, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> created = client.send(
                    request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            String threadId = extract(created.body(), "thread_");

            client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                    "{\"input\":\"stream\"}").build(), HttpResponse.BodyHandlers.ofString());
            waitForEvents(client, base, threadId);

            List<RuntimeEvent> events = store.events(threadId, 0);
            assertEquals(1, events.stream().filter(event -> "reasoning.delta".equals(event.type())).count());
            assertEquals(1, events.stream().filter(event -> "message.delta".equals(event.type())).count());
            assertTrue(events.stream().anyMatch(event -> event.data().contains("streamed reply")));
            assertFalse(events.stream().anyMatch(event -> event.data().contains("fallback reply")));
        }
    }

    @Test
    void persistsCheckpointAfterCompletedEvent(@TempDir Path tempDir) throws Exception {
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        String summary = "[已压缩的历史对话摘要]\n"
                + metadata.renderBoundaryBlock() + "\nsummary";
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, (threadId, prompt, eventSink) ->
                     new TurnRunner.TurnResult("reply", new TurnRunner.CheckpointCandidate(
                             List.of(
                                     LlmClient.Message.user(summary),
                                     LlmClient.Message.assistant("已恢复")),
                             summary,
                             metadata)), 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            String threadId = extract(created.body(), "thread_");

            client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                    "{\"input\":\"checkpoint\"}").build(), HttpResponse.BodyHandlers.ofString());
            String events = waitForEvent(client, base, threadId, "thread.checkpoint.created");

            assertTrue(events.contains("semantic_guard"));
            RuntimeThreadStore.RuntimeCheckpoint checkpoint = store.latestCheckpoint(threadId).orElseThrow();
            assertEquals(metadata.preTokens(), checkpoint.metadata().preTokens());
            assertEquals(2, checkpoint.messages().size());
            assertEquals(store.turnHistory(threadId).getFirst().completedEventId(),
                    checkpoint.coveredThroughEventId());
        }
    }

    @Test
    void checkpointFailureDoesNotChangeCompletedTurnState(@TempDir Path tempDir) throws Exception {
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        String summary = "[已压缩的历史对话摘要]\n"
                + metadata.renderBoundaryBlock() + "\nsummary";
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db")) {
                 @Override
                 public synchronized void saveCheckpoint(
                         String threadId, long coveredThroughEventId,
                         TurnRunner.CheckpointCandidate candidate) {
                     throw new IllegalStateException("checkpoint unavailable");
                 }
             };
             RuntimeApiServer server = new RuntimeApiServer(store, (threadId, prompt, eventSink) ->
                     new TurnRunner.TurnResult("reply", new TurnRunner.CheckpointCandidate(
                             List.of(LlmClient.Message.user(summary)), summary, metadata)),
                     0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> created = client.send(
                    request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            String threadId = extract(created.body(), "thread_");

            client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                    "{\"input\":\"checkpoint\"}").build(), HttpResponse.BodyHandlers.ofString());
            String events = waitForEvent(client, base, threadId, "thread.checkpoint.failed");

            assertTrue(events.contains("event: turn.completed"));
            assertFalse(events.contains("event: turn.failed"));
            assertEquals(1, store.turnHistory(threadId).size());
            assertEquals("reply", store.turnHistory(threadId).getFirst().output());
            assertTrue(store.latestCheckpoint(threadId).isEmpty());
        }
    }

    @Test
    void rejectsMissingApiKey(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store,
                     (threadId, prompt, eventSink) -> TurnRunner.TurnResult.completed("x"),
                     0, "secret")) {
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
             RuntimeApiServer server = new RuntimeApiServer(store, (threadId, prompt, eventSink) -> {
                 seenThreadId.set(threadId);
                 // 执行侧按 threadId 重放历史：第二轮应能看到第一轮的输入输出
                 List<RuntimeThreadStore.TurnRecord> history = store.turnHistory(threadId);
                 return TurnRunner.TurnResult.completed(
                         "history=" + history.size() + ";reply:" + prompt);
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

    @Test
    void exposesExplicitSteeringAndFollowUpQueueEndpoints(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> steering = new AtomicReference<>();
        AtomicReference<String> followUp = new AtomicReference<>();
        TurnRunner runner = new TurnRunner() {
            @Override
            public TurnResult run(String threadId, String input,
                                  com.devcli.runtime.event.RunEventSink eventSink) {
                return TurnResult.completed("ok");
            }

            @Override
            public QueueResult enqueueSteering(String threadId, String input) {
                steering.set(threadId + ":" + input);
                return new QueueResult(true, com.devcli.agent.AgentTurnInbox.Channel.STEERING,
                        "", 1, 0);
            }

            @Override
            public QueueResult enqueueFollowUp(String threadId, String input) {
                followUp.set(threadId + ":" + input);
                return new QueueResult(true, com.devcli.agent.AgentTurnInbox.Channel.FOLLOW_UP,
                        "", 1, 1);
            }
        };
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> created = client.send(
                    request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            String threadId = extract(created.body(), "thread_");

            HttpResponse<String> steeringResponse = client.send(request(
                    base + "/v1/threads/" + threadId + "/steer", "POST",
                    "{\"input\":\"interrupt now\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> followUpResponse = client.send(request(
                    base + "/v1/threads/" + threadId + "/follow-up", "POST",
                    "{\"input\":\"continue later\"}").build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(202, steeringResponse.statusCode());
            assertEquals(202, followUpResponse.statusCode());
            assertEquals(threadId + ":interrupt now", steering.get());
            assertEquals(threadId + ":continue later", followUp.get());
            assertTrue(store.events(threadId, 0).stream()
                    .anyMatch(event -> "queue.updated".equals(event.type())));
        }
    }

    @Test
    void createsListsAndActivatesConversationBranches(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> resetThread = new AtomicReference<>();
        TurnRunner runner = new TurnRunner() {
            @Override
            public TurnResult run(String threadId, String input,
                                  com.devcli.runtime.event.RunEventSink eventSink) {
                return TurnResult.completed("ok");
            }

            @Override
            public void resetSession(String threadId) {
                resetThread.set(threadId);
            }
        };
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> created = client.send(
                    request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            String threadId = extract(created.body(), "thread_");

            HttpResponse<String> branchResponse = client.send(request(
                    base + "/v1/threads/" + threadId + "/branches", "POST",
                    "{\"name\":\"alternative\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, branchResponse.statusCode());
            String branchId = extract(branchResponse.body(), "branch_");

            HttpResponse<String> activate = client.send(request(
                    base + "/v1/threads/" + threadId + "/branches/" + branchId + "/activate",
                    "POST", "").build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> branches = client.send(request(
                    base + "/v1/threads/" + threadId + "/branches", "GET", "").build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, activate.statusCode());
            assertEquals(threadId, resetThread.get());
            assertTrue(branches.body().contains("\"active_branch_id\":\"" + branchId + "\""));
            assertTrue(branches.body().contains("alternative"));
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

    private static String waitForEvent(HttpClient client, String base, String threadId, String event)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request(
                            base + "/v1/threads/" + threadId + "/events", "GET", "").build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains(event)) return response.body();
            Thread.sleep(30);
        }
        fail("event did not appear: " + event);
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
