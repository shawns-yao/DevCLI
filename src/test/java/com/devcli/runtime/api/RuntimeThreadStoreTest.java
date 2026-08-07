package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeThreadStoreTest {

    @Test
    void turnHistoryReturnsCompletedTurnPairsInOrder(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            appendTurn(store, threadId, "t1", "你好", "我在");
            appendTurn(store, threadId, "t2", "项目结构？", "三层架构");

            List<RuntimeThreadStore.TurnRecord> history = store.turnHistory(threadId);

            assertEquals(2, history.size());
            assertEquals("你好", history.get(0).input());
            assertEquals("我在", history.get(0).output());
            assertEquals("项目结构？", history.get(1).input());
            assertEquals("三层架构", history.get(1).output());
        }
    }

    @Test
    void turnHistorySkipsFailedAndIncompleteTurns(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            // 失败 turn：有 started 无 completed
            store.appendEvent(threadId, "turn.started", "{\"turn_id\":\"bad\",\"input\":\"会失败\"}");
            store.appendEvent(threadId, "turn.failed", "{\"turn_id\":\"bad\",\"error\":\"boom\"}");
            // 进行中 turn：无终态
            store.appendEvent(threadId, "turn.started", "{\"turn_id\":\"running\",\"input\":\"执行中\"}");
            // 完整 turn
            appendTurn(store, threadId, "ok", "正常输入", "正常输出");

            List<RuntimeThreadStore.TurnRecord> history = store.turnHistory(threadId);

            assertEquals(1, history.size());
            assertEquals("正常输入", history.get(0).input());
        }
    }

    @Test
    void turnHistoryToleratesMalformedEventData(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            store.appendEvent(threadId, "turn.started", "not-a-json{{{");
            appendTurn(store, threadId, "ok", "输入", "输出");

            List<RuntimeThreadStore.TurnRecord> history = store.turnHistory(threadId);

            assertEquals(1, history.size());
        }
    }

    @Test
    void contextViewRestoresLatestCheckpointAndOnlyLaterTurns(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        String threadId;
        long coverage;
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        String summary = "[已压缩的历史对话摘要]\n"
                + metadata.renderBoundaryBlock() + "\nsummary";
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            threadId = store.createThread();
            coverage = appendTurn(store, threadId, "t1", "第一轮", "第一轮回答");
            store.saveCheckpoint(threadId, coverage, new TurnRunner.CheckpointCandidate(
                    List.of(
                            LlmClient.Message.user(summary),
                            LlmClient.Message.assistant("已恢复")),
                    summary,
                    metadata));
            long secondCoverage = appendTurn(store, threadId, "t2", "第二轮", "第二轮回答");

            RuntimeThreadStore.ContextView view = store.contextView(threadId);
            assertEquals(2, view.checkpointMessages().size());
            assertEquals(1, view.turns().size());
            assertEquals("第二轮", view.turns().getFirst().input());
            assertEquals(secondCoverage, view.lastCompletedEventId());
        }

        try (RuntimeThreadStore reopened = new RuntimeThreadStore(db)) {
            RuntimeThreadStore.RuntimeCheckpoint checkpoint = reopened.latestCheckpoint(threadId).orElseThrow();
            assertEquals(coverage, checkpoint.coveredThroughEventId());
            assertEquals(40_000, checkpoint.metadata().preTokens());
            assertEquals(summary, checkpoint.summary());
            assertEquals(checkpoint.messages().size(), checkpoint.messageTree().size());
            assertEquals("", checkpoint.messageTree().getFirst().parentId());
            assertEquals(checkpoint.messageTree().get(0).id(), checkpoint.messageTree().get(1).parentId());
        }
    }

    @Test
    void corruptedLatestCheckpointFallsBackToEarlierValidCheckpoint(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("runtime.db");
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            String threadId = store.createThread();
            long coverage = appendTurn(store, threadId, "t1", "第一轮", "回答");
            store.saveCheckpoint(threadId, coverage, new TurnRunner.CheckpointCandidate(
                    List.of(LlmClient.Message.user("summary")), "summary", metadata));

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 PreparedStatement ps = connection.prepareStatement("""
                         INSERT INTO runtime_checkpoints(
                             thread_id, covered_through_event_id, messages_json,
                             summary, metadata_json, created_at
                         ) VALUES (?, ?, ?, ?, ?, ?)
                         """)) {
                ps.setString(1, threadId);
                ps.setLong(2, coverage + 100);
                ps.setString(3, "not-json");
                ps.setString(4, "corrupted");
                ps.setString(5, "not-json");
                ps.setString(6, java.time.Instant.now().toString());
                ps.executeUpdate();
            }

            RuntimeThreadStore.RuntimeCheckpoint checkpoint = store.latestCheckpoint(threadId).orElseThrow();
            assertEquals(coverage, checkpoint.coveredThroughEventId());
            assertEquals("summary", checkpoint.summary());
        }
    }

    @Test
    void branchesPreserveForkHistoryAndDivergeAfterActivation(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            long forkEventId = appendTurn(store, threadId, "t1", "shared", "shared answer");
            RuntimeThreadStore.BranchRecord branch = store.createBranch(
                    threadId, "alternative", forkEventId);

            store.activateBranch(threadId, branch.id());
            appendTurn(store, threadId, "t2", "branch input", "branch answer");
            assertEquals(List.of("shared", "branch input"), store.turnHistory(threadId).stream()
                    .map(RuntimeThreadStore.TurnRecord::input).toList());

            store.activateBranch(threadId, "main");
            appendTurn(store, threadId, "t3", "main input", "main answer");
            assertEquals(List.of("shared", "main input"), store.turnHistory(threadId).stream()
                    .map(RuntimeThreadStore.TurnRecord::input).toList());

            store.activateBranch(threadId, branch.id());
            assertEquals(List.of("shared", "branch input"), store.turnHistory(threadId).stream()
                    .map(RuntimeThreadStore.TurnRecord::input).toList());
        }
    }

    private static long appendTurn(RuntimeThreadStore store, String threadId,
                                   String turnId, String input, String output) {
        store.appendEvent(threadId, "turn.started",
                "{\"turn_id\":\"" + turnId + "\",\"input\":\"" + input + "\"}");
        store.appendEvent(threadId, "message.delta",
                "{\"turn_id\":\"" + turnId + "\",\"content\":\"" + output + "\"}");
        return store.appendEvent(threadId, "turn.completed",
                "{\"turn_id\":\"" + turnId + "\",\"status\":\"completed\"}");
    }
}
