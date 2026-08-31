package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * microcompact（第 0 层）测试：只回收旧工具结果，不修改用户或助手语义消息。
 */
class ConversationHistoryCompactorMicrocompactTest {

    @TempDir
    Path tempDir;

    @Test
    void neverModifiesUserOrAssistantMessages() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        String user = "user-requirement-".repeat(5_000);
        String assistant = "assistant-decision-".repeat(4_000);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        history.add(LlmClient.Message.user(user));
        history.add(LlmClient.Message.assistant(assistant));

        assertFalse(c.microcompactOversizeMessages(history));
        assertEquals(user, history.get(1).content());
        assertEquals(assistant, history.get(2).content());
    }

    @Test
    void clearsOldOversizeToolResultAndKeepsMetadata() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        c.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> history = toolHistory(
                tool("old", "read_file"),
                tool("r1", "read_file"),
                tool("r2", "grep_code"),
                tool("r3", "list_dir"),
                tool("r4", "search_code"));
        history.set(2, new LlmClient.Message("tool", "x".repeat(30_000), null, null, "old"));

        boolean changed = c.microcompactOversizeMessages(history);

        assertTrue(changed);
        String compacted = history.get(2).content();
        assertTrue(compacted.length() < 30_000, "旧工具结果应被回收");
        assertTrue(compacted.contains("<microcompact_boundary>"));
        assertEquals("old", history.get(2).toolCallId(), "回收不能丢失 toolCallId");
        assertEquals("tool", history.get(2).role());
    }

    @Test
    void persistsToolResultWithProjectRelativeRecoveryPath() throws IOException {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        c.setMicrocompactOutputRoot(tempDir);
        String original = "tool-output-".repeat(3_000);
        List<LlmClient.Message> history = toolHistory(
                tool("call/with:unsafe", "read_file"),
                tool("r1", "read_file"),
                tool("r2", "grep_code"),
                tool("r3", "list_dir"),
                tool("r4", "search_code"));
        history.set(2, new LlmClient.Message("tool", original, null, null, "call/with:unsafe"));

        assertTrue(c.microcompactOversizeMessages(history));

        String compacted = history.get(2).content();
        assertTrue(compacted.contains("storedPath=.devcli/microcompact_tool_outputs/"), compacted);
        assertFalse(compacted.contains(tempDir.toAbsolutePath().toString()),
                "恢复提示不得暴露 Docker 无法访问的宿主机绝对路径");
        Path output = tempDir.resolve(ConversationHistoryCompactor.MICROCOMPACT_OUTPUTS_DIR)
                .resolve(ConversationHistoryCompactor.microcompactSessionId())
                .resolve("call_with_unsafe.txt");
        assertTrue(Files.isRegularFile(output));
        assertEquals(original, Files.readString(output));
    }

    @Test
    void keepsMostRecentToolResultsByCount() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        c.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> history = toolHistory(
                tool("old", "read_file"),
                tool("recent-1", "read_file"),
                tool("recent-2", "grep_code"),
                tool("recent-3", "list_dir"),
                tool("recent-4", "search_code"));

        assertTrue(c.microcompactOversizeMessages(history));

        assertTrue(history.get(2).content().contains("<microcompact_boundary>"));
        for (int i = 1; i <= ConversationHistoryCompactor.MICRO_COMPACT_RETAIN_RECENT_TOOL_RESULTS; i++) {
            assertEquals("result-recent-" + i, history.get(2 + i).content());
        }
    }

    @Test
    void protectsCriticalExternalAndFailedCommandResults() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        c.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> history = toolHistory(
                tool("memory", "save_memory"),
                tool("mcp", "mcp__memory__recall"),
                tool("web", "web_fetch"),
                tool("failed", "execute_command"),
                tool("old-read", "read_file"),
                tool("r1", "read_file"),
                tool("r2", "grep_code"),
                tool("r3", "list_dir"),
                tool("r4", "search_code"));
        history.set(5, new LlmClient.Message("tool",
                "命令执行完成 (exit code: 1)\nBUILD FAILURE", null, null, "failed"));

        assertTrue(c.microcompactOversizeMessages(history));

        assertEquals("result-memory", history.get(2).content());
        assertEquals("result-mcp", history.get(3).content());
        assertEquals("result-web", history.get(4).content());
        assertTrue(history.get(5).content().contains("exit code: 1"));
        assertTrue(history.get(6).content().contains("<microcompact_boundary>"));
    }

    @Test
    void supportsConfiguredToolExclusions() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        c.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> history = toolHistory(
                tool("protected-read", "read_file"),
                tool("clearable-grep", "grep_code"),
                tool("r1", "read_file"),
                tool("r2", "grep_code"),
                tool("r3", "list_dir"),
                tool("r4", "search_code"));
        String previous = System.getProperty(
                ConversationHistoryCompactor.MICRO_COMPACT_EXCLUDE_TOOLS_PROPERTY);
        try {
            System.setProperty(ConversationHistoryCompactor.MICRO_COMPACT_EXCLUDE_TOOLS_PROPERTY,
                    "read_file");
            assertTrue(c.microcompactOversizeMessages(history));
        } finally {
            if (previous == null) {
                System.clearProperty(ConversationHistoryCompactor.MICRO_COMPACT_EXCLUDE_TOOLS_PROPERTY);
            } else {
                System.setProperty(ConversationHistoryCompactor.MICRO_COMPACT_EXCLUDE_TOOLS_PROPERTY, previous);
            }
        }

        assertEquals("result-protected-read", history.get(2).content());
        assertTrue(history.get(3).content().contains("<microcompact_boundary>"));
    }

    @Test
    void recordsTokenChangesByRoleAndTool() {
        ConversationHistoryCompactor c = new ConversationHistoryCompactor(null, 30_000, true);
        c.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> history = toolHistory(
                tool("old", "read_file"),
                tool("r1", "read_file"),
                tool("r2", "grep_code"),
                tool("r3", "list_dir"),
                tool("r4", "search_code"));
        history.set(2, new LlmClient.Message("tool", "large-output-".repeat(3_000), null, null, "old"));

        assertTrue(c.microcompactOversizeMessages(history));

        ConversationHistoryCompactor.MicrocompactStats stats = c.lastMicrocompactStats();
        assertEquals(1, stats.clearedToolResults());
        assertTrue(stats.afterTokens() < stats.beforeTokens());
        assertTrue(stats.removedTokensByTool().getOrDefault("read_file", 0) > 0);
        assertEquals(stats.roleTokensBefore().get("user"), stats.roleTokensAfter().get("user"));
        assertEquals(stats.roleTokensBefore().get("assistant"), stats.roleTokensAfter().get("assistant"));
        assertTrue(stats.roleTokensAfter().get("tool") < stats.roleTokensBefore().get("tool"));
    }

    @Test
    void microcompactAloneAvoidsLlmSummarization() {
        AtomicInteger summarizeCalls = new AtomicInteger();
        ConversationHistoryCompactor c = summaryCountingCompactor(summarizeCalls);
        c.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> history = toolHistory(
                tool("old", "read_file"),
                tool("r1", "read_file"),
                tool("r2", "grep_code"),
                tool("r3", "list_dir"),
                tool("r4", "search_code"));
        history.set(2, new LlmClient.Message("tool", "x".repeat(120_000), null, null, "old"));

        assertFalse(c.compactIfNeeded(history, 5_000));
        assertEquals(0, summarizeCalls.get());
        assertTrue(TokenBudget.estimateMessagesTokens(history) < 5_000);
    }

    @Test
    void semanticCompactionStillRunsWhenToolGcCannotBringHistoryBelow256k() {
        AtomicInteger summarizeCalls = new AtomicInteger();
        ConversationHistoryCompactor c = summaryCountingCompactor(summarizeCalls);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 25; i++) {
            history.add(LlmClient.Message.user("requirement-" + i + "-" + "u".repeat(22_000)));
            history.add(LlmClient.Message.assistant("decision-" + i + "-" + "a".repeat(22_000)));
        }
        assertTrue(TokenBudget.estimateMessagesTokens(history) > 256_000);

        assertTrue(c.compactIfNeeded(history, 256_000));

        assertEquals(1, summarizeCalls.get());
        assertTrue(history.get(1).content().startsWith(ConversationHistoryCompactor.SUMMARY_MARKER));
        assertEquals(0, c.lastMicrocompactStats().clearedToolResults());
    }

    private static ConversationHistoryCompactor summaryCountingCompactor(AtomicInteger calls) {
        return new ConversationHistoryCompactor(null, 30_000, true) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) {
                calls.incrementAndGet();
                return "SUMMARY";
            }

            @Override
            protected String summarizeIncremental(String previousSummary, List<LlmClient.Message> newMessages) {
                calls.incrementAndGet();
                return "SUMMARY";
            }
        };
    }

    private static LlmClient.ToolCall tool(String id, String name) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, "{}"));
    }

    private static List<LlmClient.Message> toolHistory(LlmClient.ToolCall... calls) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.user("Q"));
        history.add(LlmClient.Message.assistant(null, null, List.of(calls)));
        for (LlmClient.ToolCall call : calls) {
            history.add(new LlmClient.Message("tool", "result-" + call.id(), null, null, call.id()));
        }
        history.add(LlmClient.Message.assistant("done"));
        return history;
    }
}
