package com.devcli.agent;

import com.devcli.llm.AnthropicClient;
import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AgentManualCompactionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUMMARY = """
            {"schema_version":2,"request_intent":"Earlier discussion.","concepts":[],"files":[],
            "pitfalls":[],"resolution_steps":[],"user_messages":["Earlier discussion."],"protected_facts":[]}
            """;
    @TempDir Path root;

    @Test
    void manualCompactionKeepsRequestedTurnsAndReusesWirePrefix() throws Exception {
        try (Fixture fixture = new Fixture(root, false)) {
            fixture.agent.seedHistory(history());
            List<LlmClient.Message> before = fixture.agent.getConversationHistory();
            List<LlmClient.Message> tail = List.copyOf(before.subList(before.size() - 4, before.size()));

            var result = fixture.agent.compactHistoryNow(2);

            assertTrue(result.compacted());
            assertTrue(result.afterTokens() < result.beforeTokens());
            List<LlmClient.Message> after = fixture.agent.getConversationHistory();
            assertEquals(tail, after.subList(after.size() - tail.size(), after.size()));
            assertEquals(1, fixture.requests.size());
            JsonNode request = fixture.requests.get(0);
            assertEquals(before.get(0).content(), request.path("system").asText());
            assertEquals(before.size() - tail.size(), request.path("messages").size());
            assertEquals(before.get(1).content(), request.path("messages").get(0).path("content").asText());
            assertTrue(request.path("tools").size() > 0);
            assertEquals("ephemeral", request.path("cache_control").path("type").asText());
            assertTrue(after.get(1).content().contains("trigger=manual"));
            assertEquals(1, fixture.agent.getMemoryManager().getTokenBudget().getLlmCallCount());
            assertEquals(120, fixture.agent.getMemoryManager().getTokenBudget().getTotalInputTokens());
            assertEquals(90, fixture.agent.getMemoryManager().getTokenBudget().getTotalCachedInputTokens());
        }
    }

    @Test
    void toolResponseIsDiscardedAndFallsBackWithoutExecuting() throws Exception {
        try (Fixture fixture = new Fixture(root, true)) {
            fixture.agent.seedHistory(history());

            assertTrue(fixture.agent.compactHistoryNow(1).compacted());

            assertEquals(2, fixture.requests.size());
            assertTrue(fixture.requests.get(0).path("tools").size() > 0);
            assertTrue(fixture.requests.get(1).path("tools").isMissingNode());
            assertEquals(1, fixture.requests.get(1).path("messages").size());
            assertFalse(Files.exists(root.resolve("unexpected.txt")));
            assertEquals(2, fixture.agent.getMemoryManager().getTokenBudget().getLlmCallCount());
        }
    }

    @Test
    void noOldTurnsAndUnfinishedToolsLeaveHistoryUnchangedWithoutCallingModel() throws Exception {
        try (Fixture fixture = new Fixture(root, false)) {
            fixture.agent.seedHistory(history());
            List<LlmClient.Message> before = fixture.agent.getConversationHistory();
            assertFalse(fixture.agent.compactHistoryNow(4).compacted());
            assertEquals(before, fixture.agent.getConversationHistory());
            assertThrows(IllegalArgumentException.class, () -> fixture.agent.compactHistoryNow(-1));
            fixture.agent.clearHistory();
            fixture.agent.seedHistory(List.of(LlmClient.Message.user("Pending request"),
                    LlmClient.Message.assistant("", List.of(new LlmClient.ToolCall("pending",
                            new LlmClient.ToolCall.Function("read_file", "{}"))))));
            before = fixture.agent.getConversationHistory();
            assertFalse(fixture.agent.compactHistoryNow(0).compacted());
            assertEquals(before, fixture.agent.getConversationHistory());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void retainedToolBatchAndImageRemainIntact() throws Exception {
        try (Fixture fixture = new Fixture(root, false)) {
            List<LlmClient.Message> messages = new ArrayList<>(history().subList(0, 6));
            messages.set(0, LlmClient.Message.user(List.of(LlmClient.ContentPart.text(messages.get(0).content()),
                    LlmClient.ContentPart.imageBase64("b2xk", "image/png"))));
            messages.add(LlmClient.Message.user(List.of(LlmClient.ContentPart.text("Recent image"),
                    LlmClient.ContentPart.imageBase64("aW1hZ2U=", "image/png"))));
            messages.add(LlmClient.Message.assistant("", List.of(new LlmClient.ToolCall("recent",
                    new LlmClient.ToolCall.Function("read_file", "{}")))));
            messages.add(LlmClient.Message.tool("recent", "read evidence"));
            messages.add(LlmClient.Message.assistant("Recent result"));
            fixture.agent.seedHistory(messages);
            List<LlmClient.Message> tail = List.copyOf(messages.subList(6, messages.size()));

            assertTrue(fixture.agent.compactHistoryNow(1).compacted());

            List<LlmClient.Message> after = fixture.agent.getConversationHistory();
            assertEquals(tail, after.subList(after.size() - tail.size(), after.size()));
            assertEquals("image", fixture.requests.get(0).path("messages").get(0)
                    .path("content").get(1).path("type").asText());
            assertEquals(7, fixture.requests.get(0).path("messages").size(), "保留区不进入摘要输入");
        }
    }

    @Test
    void automaticCompactionCanReusePrefixAfterThresholdWasCrossed() throws Exception {
        try (Fixture fixture = new Fixture(root, false)) {
            fixture.agent.seedHistory(history());

            assertTrue(fixture.agent.compactHistoryForPersistence(5_000));

            assertEquals(1, fixture.requests.size());
            assertTrue(fixture.requests.get(0).path("tools").size() > 0);
        }
    }

    @Test
    void zeroKeptTurnsCompressesAllOriginalRounds() throws Exception {
        try (Fixture fixture = new Fixture(root, false)) {
            fixture.agent.seedHistory(history());
            assertTrue(fixture.agent.compactHistoryNow(0).compacted());
            assertTrue(fixture.agent.getConversationHistory().stream()
                    .noneMatch(message -> message.source() == LlmClient.MessageSource.USER));
        }
    }

    @Test
    void invalidSummaryNeverReplacesOriginalHistory() throws Exception {
        try (Fixture fixture = new Fixture(root, false, "{\"schema_version\":99}")) {
            fixture.agent.seedHistory(history());
            List<LlmClient.Message> before = fixture.agent.getConversationHistory();

            assertFalse(fixture.agent.compactHistoryNow(1).compacted());

            assertEquals(before, fixture.agent.getConversationHistory());
            assertEquals(3, fixture.requests.size(), "一次前缀尝试和两次独立摘要协议检查");
            assertEquals(3, fixture.agent.getMemoryManager().getTokenBudget().getLlmCallCount());
        }
    }

    private static List<LlmClient.Message> history() {
        List<LlmClient.Message> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            messages.add(LlmClient.Message.user("Earlier discussion. " + "discussion ".repeat(700)));
            messages.add(LlmClient.Message.assistant("Explanation. " + "explanation ".repeat(700)));
        }
        return messages;
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new ArrayList<>();
        final ToolRegistry tools;
        final Agent agent;

        Fixture(Path root, boolean firstToolResponse) throws Exception {
            this(root, firstToolResponse, SUMMARY);
        }

        Fixture(Path root, boolean firstToolResponse, String summaryResponse) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", exchange -> {
                requests.add(JSON.readTree(exchange.getRequestBody()));
                boolean tool = firstToolResponse && requests.size() == 1;
                String start = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{"
                        + "\"input_tokens\":10,\"cache_creation_input_tokens\":20,\"cache_read_input_tokens\":90}}}\n\n";
                String content = tool
                        ? "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{"
                            + "\"type\":\"tool_use\",\"id\":\"unexpected\",\"name\":\"write_file\","
                            + "\"input\":{\"path\":\"unexpected.txt\",\"content\":\"unexpected\"}}}\n\n"
                        : "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{"
                            + "\"type\":\"text_delta\",\"text\":" + JSON.writeValueAsString(summaryResponse) + "}}\n\n";
                byte[] body = (start + content
                        + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":20}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            AnthropicClient client = new AnthropicClient("test-key", "claude-sonnet-4-20250514",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 1_024);
            tools = new ToolRegistry();
            tools.setProjectPath(root.toString());
            MemoryManager memory = new MemoryManager(client, 4_096, 200_000,
                    new LongTermMemory(root.resolve("memory").toFile()));
            agent = new Agent(client, tools, memory);
        }

        @Override public void close() {
            agent.close();
            tools.close();
            server.stop(0);
        }
    }
}
