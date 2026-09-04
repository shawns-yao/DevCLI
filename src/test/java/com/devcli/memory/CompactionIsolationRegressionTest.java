package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionIsolationRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void pendingTriggerFromAnotherHistoryIsIgnored() {
        ConversationHistoryCompactor failed = new ConversationHistoryCompactor(null, 2, true);
        failed.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> firstHistory = history("first");
        assertFalse(failed.compactIfNeeded(firstHistory, 20));

        ConversationHistoryCompactor other = new StubCompactor();
        other.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> otherHistory = history("second");

        assertFalse(other.compactIfNeeded(otherHistory, 2_000));
    }

    @Test
    void microcompactArtifactsDoNotOverwriteSameToolCallId() throws Exception {
        ConversationHistoryCompactor first = new ConversationHistoryCompactor(null, 30_000, true);
        first.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> firstHistory = duplicateHistory("first-content", "same-call");
        assertTrue(first.microcompactOversizeMessages(firstHistory));
        String firstPath = storedPath(firstHistory.get(6).content());

        ConversationHistoryCompactor second = new ConversationHistoryCompactor(null, 30_000, true);
        second.setMicrocompactOutputRoot(tempDir);
        List<LlmClient.Message> secondHistory = duplicateHistory("second-content", "same-call");
        assertTrue(second.microcompactOversizeMessages(secondHistory));
        String secondPath = storedPath(secondHistory.get(6).content());

        assertNotEquals(firstPath, secondPath);
        assertTrue(Files.exists(tempDir.resolve(firstPath)));
        assertTrue(Files.exists(tempDir.resolve(secondPath)));
        assertTrue(Files.readString(tempDir.resolve(firstPath)).contains("first-content"));
        assertTrue(Files.readString(tempDir.resolve(secondPath)).contains("second-content"));
        assertTrue(secondHistory.get(6).content().contains("read_file"));
    }

    @Test
    void microcompactRecognizesDuplicateManagedResultsAcrossCalls() {
        String previousRoot = System.getProperty("devcli.tool.results.root");
        System.setProperty("devcli.tool.results.root", tempDir.resolve("tool-results").toString());
        try {
            com.devcli.tool.ToolResultSizeManager.resetTurnBudget();
            String raw = "same managed output ".repeat(4_000);
            String first = com.devcli.tool.ToolResultSizeManager.process(
                    "read_file", "managed-1", tempDir.toString(), false, raw);
            com.devcli.tool.ToolResultSizeManager.resetTurnBudget();
            String second = com.devcli.tool.ToolResultSizeManager.process(
                    "read_file", "managed-2", tempDir.toString(), false, raw);
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                    "managed-1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")))));
            history.add(new LlmClient.Message("tool", first, null, null, "managed-1"));
            history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                    "managed-2", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")))));
            history.add(new LlmClient.Message("tool", second, null, null, "managed-2"));

            ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(null, 30_000, true);
            compactor.setMicrocompactOutputRoot(tempDir);

            assertTrue(compactor.microcompactOversizeMessages(history));
            assertTrue(history.get(3).content().contains("reason=duplicate"), history.get(3).content());
        } finally {
            if (previousRoot == null) System.clearProperty("devcli.tool.results.root");
            else System.setProperty("devcli.tool.results.root", previousRoot);
        }
    }

    private static List<LlmClient.Message> history(String marker) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 4; i++) {
            history.add(LlmClient.Message.user(marker + "-q" + i + " " + "x".repeat(400)));
            history.add(LlmClient.Message.assistant(marker + "-a" + i));
        }
        return history;
    }

    private static List<LlmClient.Message> duplicateHistory(String content, String id) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.user("inspect"));
        history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                id, new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")))));
        history.add(new LlmClient.Message("tool", content.repeat(2_000), null, null, id));
        history.add(LlmClient.Message.user("follow-up"));
        history.add(LlmClient.Message.assistant("done"));
        history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                "other-call", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")))));
        history.add(new LlmClient.Message("tool", content.repeat(2_000), null, null, "other-call"));
        return history;
    }

    private static String storedPath(String content) {
        for (String line : content.split("\\R")) {
            if (line.startsWith("storedPath=")) return line.substring("storedPath=".length()).trim();
        }
        throw new AssertionError("storedPath missing: " + content);
    }

    private static final class StubCompactor extends ConversationHistoryCompactor {
        StubCompactor() { super(null, 2, true); }

        @Override
        protected String summarize(List<LlmClient.Message> messages) throws IOException {
            return "SUMMARY";
        }
    }
}
