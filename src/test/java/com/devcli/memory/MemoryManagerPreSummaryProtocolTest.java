package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerPreSummaryProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void fullPreSummaryUsesTheFormalSixSectionProtocol() {
        RecordingClient client = new RecordingClient(List.of(fullSnapshotResponse()));
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("full").toFile());
             MemoryManager memoryManager = new MemoryManager(
                     client, 4_096, 128_000, longTermMemory)) {
            List<LlmClient.Message> history = List.of(
                    LlmClient.Message.system("system"),
                    LlmClient.Message.user("统一预摘要协议"));

            MemoryManager.SessionPreSummaryMaintenanceResult result =
                    memoryManager.maintainSessionPreSummaryAfterTurn(history, 4, 0);

            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED, result);
            String summary = memoryManager.getCompactionSummaryCache()
                    .currentPreSummary().orElseThrow().summary();
            RollingSummary parsed = RollingSummary.parse(summary);
            assertFalse(parsed.isEmpty(), summary);
            for (String section : RollingSummary.SECTIONS) {
                assertTrue(summary.contains("## " + section), summary);
            }
            assertTrue(parsed.get("主要请求与意图").contains("统一预摘要协议"), summary);
        }
    }

    @Test
    void incrementalPreSummaryUsesRestrictedOperationsInsteadOfRewritingTheOldBody() {
        RecordingClient client = new RecordingClient(List.of(
                fullSnapshotResponse(),
                """
                        {"operations":[{"action":"ADD","section":"关键技术概念",
                        "subject":"pre-summary.protocol","content":"增量协议已统一",
                        "lifecycle":"STABLE","importance":80,"evidence_refs":[]}]}
                        """));
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("incremental").toFile());
             MemoryManager memoryManager = new MemoryManager(
                     client, 4_096, 128_000, longTermMemory)) {
            List<LlmClient.Message> initial = List.of(
                    LlmClient.Message.system("system"),
                    LlmClient.Message.user("统一预摘要协议"));
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    memoryManager.maintainSessionPreSummaryAfterTurn(initial, 4, 0));

            List<LlmClient.Message> extended = new ArrayList<>(initial);
            extended.add(LlmClient.Message.assistant("已开始处理"));
            extended.add(LlmClient.Message.user("新增消息只产生受限变更操作"));
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    memoryManager.maintainSessionPreSummaryAfterTurn(extended, 4, 0));

            List<LlmClient.Message> incrementalRequest = client.requests.get(1);
            assertTrue(incrementalRequest.get(0).content().contains("滚动摘要变更提取器"));
            assertTrue(incrementalRequest.get(1).content().contains("\"operations\""));
            assertFalse(incrementalRequest.get(1).content().contains("旧摘要："));
            assertFalse(incrementalRequest.get(1).content().contains("用户要求统一预摘要协议"),
                    "增量请求只能包含旧摘要结构索引，不能回传旧摘要正文");

            String summary = memoryManager.getCompactionSummaryCache()
                    .currentPreSummary().orElseThrow().summary();
            RollingSummary parsed = RollingSummary.parse(summary);
            assertTrue(parsed.get("关键技术概念").contains("增量协议已统一"), summary);
        }
    }

    private static String fullSnapshotResponse() {
        return """
                {"schema_version":2,"request_intent":"统一预摘要协议",
                "concepts":[],"files":[],"pitfalls":[],"resolution_steps":[],
                "user_messages":["用户要求统一预摘要协议"],"protected_facts":[]}
                """;
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<String> responses;
        private final List<List<Message>> requests = new ArrayList<>();

        private RecordingClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            requests.add(List.copyOf(messages));
            String response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return new ChatResponse("assistant", response, List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "pre-summary-protocol-test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 128_000;
        }
    }
}
