package com.devcli.memory;

import com.devcli.context.ContextProfile;
import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorWindowTest {
    private static final String SUMMARY = "## 主要请求与意图\n- Preserve project decisions\n";
    private static final String OPERATION = """
            {"operations":[{"action":"ADD","section":"关键技术概念","subject":"runtime",
            "content":"Java 17","lifecycle":"STABLE","importance":80}]}
            """;

    @Test
    void defaultTailBudgetFollowsModelChangesButExplicitBudgetIsPreserved() {
        BudgetClient small = new BudgetClient(8_000, request -> SUMMARY);
        BudgetClient large = new BudgetClient(128_000, request -> SUMMARY);
        var adaptive = new ConversationHistoryCompactor(small);
        assertEquals(4_000, adaptive.retainRecentTokens());
        adaptive.setLlmClient(large);
        assertEquals(10_240, adaptive.retainRecentTokens());
        var explicit = new ConversationHistoryCompactor(small, 2_048, true);
        explicit.setLlmClient(large);
        assertEquals(2_048, explicit.retainRecentTokens());
    }

    @Test
    void mapChunksUseWindowInsteadOfSixtyThousandCharacters() throws IOException {
        BudgetClient client = new BudgetClient(64_000, request -> SUMMARY);
        new ConversationHistoryCompactor(client).summarize(List.of(LlmClient.Message.user("x".repeat(400_000))));
        assertTrue(client.requests.stream().anyMatch(r -> r.get(1).content().length() > 60_000));
        assertRequestsFit(client);
    }

    @Test
    void incrementalChunksIncludePreviousSummaryAndRollForward() throws IOException {
        String previous = SUMMARY + "中".repeat(3_800);
        String added = "新增历史\n" + "a\uD83D\uDE00中".repeat(4_000);
        BudgetClient client = new BudgetClient(8_000, request -> OPERATION);
        String result = new ConversationHistoryCompactor(client)
                .summarizeIncremental(previous, List.of(LlmClient.Message.user(added)));
        assertTrue(client.requests.size() > 1);
        StringBuilder consumed = new StringBuilder();
        for (var request : client.requests) {
            String prompt = request.get(1).content();
            assertTrue(prompt.contains("=== 已有摘要（六段） ==="));
            consumed.append(between(prompt, "=== 新增对话 ===\n", "\n=== 新增对话（结束） ==="));
        }
        assertEquals("USER: " + added + "\n\n", consumed.toString());
        assertTrue(client.requests.get(1).get(1).content().contains("Java 17"));
        assertTrue(result.contains("Java 17"));
        assertRequestsFit(client);
    }

    @Test
    void reduceBatchesFitEvenWhenEightSummariesDoNot() throws IOException {
        BudgetClient client = new BudgetClient(8_000, request -> SUMMARY + "x".repeat(4_500));
        new ConversationHistoryCompactor(client).summarize(List.of(LlmClient.Message.user("x".repeat(110_000))));
        assertTrue(client.requests.size() > 3);
        assertRequestsFit(client);
    }

    @Test
    void emptyMapDoesNotCommitPartialSummary() {
        BudgetClient client = new BudgetClient(8_000, request -> "");
        assertThrows(IOException.class, () -> new ConversationHistoryCompactor(client)
                .summarize(List.of(LlmClient.Message.user("x".repeat(60_000)))));
    }

    @Test
    void malformedIncrementalOutputDoesNotDiscardNewHistory() {
        BudgetClient client = new BudgetClient(8_000, request -> "invalid operation");
        var compactor = new ConversationHistoryCompactor(client, 100, true);
        List<LlmClient.Message> history = new ArrayList<>(List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.internalUser(ConversationHistoryCompactor.SUMMARY_MARKER + SUMMARY),
                LlmClient.Message.assistant("OK."),
                LlmClient.Message.user("new decision " + "x".repeat(8_000)),
                LlmClient.Message.assistant("noted"),
                LlmClient.Message.user("latest request")));
        var original = List.copyOf(history);
        assertFalse(compactor.compactIfNeeded(history, 1_000));
        assertEquals(original, history);
        assertEquals(1, compactor.getConsecutiveFailures());
    }

    @Test
    void oversizedPreviousSummaryFailsBeforeCallingModel() {
        BudgetClient client = new BudgetClient(8_000, request -> OPERATION);
        assertThrows(IOException.class, () -> new ConversationHistoryCompactor(client)
                .summarizeIncremental(SUMMARY + "中".repeat(7_000), List.of(LlmClient.Message.user("new"))));
        assertTrue(client.requests.isEmpty());
    }

    private static String between(String value, String start, String end) {
        return value.substring(value.indexOf(start) + start.length(), value.lastIndexOf(end));
    }

    private static void assertRequestsFit(BudgetClient client) {
        int budget = ContextProfile.from(client).compressionTriggerTokens();
        for (var request : client.requests) {
            assertTrue(TokenBudget.estimateMessagesTokens(request) <= budget,
                    "Every complete summary request must fit the model input budget");
            for (var message : request) {
                String text = message.content();
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (Character.isHighSurrogate(c)) {
                        assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)));
                    } else {
                        assertFalse(Character.isLowSurrogate(c));
                    }
                }
            }
        }
    }

    private static final class BudgetClient implements LlmClient {
        private final int window;
        private final Function<List<Message>, String> responder;
        private final List<List<Message>> requests = new ArrayList<>();

        private BudgetClient(int window, Function<List<Message>, String> responder) {
            this.window = window;
            this.responder = responder;
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            requests.add(List.copyOf(messages));
            return new ChatResponse("assistant", responder.apply(messages), null, 100, 50);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }
        @Override public String getModelName() { return "window-test"; }
        @Override public String getProviderName() { return "test"; }
        @Override public int maxContextWindow() { return window; }
        @Override public int maxOutputTokens() { return 2_048; }
    }
}
