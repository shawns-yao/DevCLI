package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.agent.AgentTurnInbox;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEvent;
import com.devcli.tool.ToolPresentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void persistsQueueSnapshotPerActiveBranch(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("runtime.db");
        String threadId;
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            threadId = store.createThread();
            AgentTurnInbox inbox = new AgentTurnInbox();
            inbox.enqueueSteering("interrupt");
            inbox.enqueueFollowUp("continue");
            store.saveQueueSnapshot(threadId, inbox.snapshot());
            assertEquals(List.of("interrupt"), store.queueSnapshot(threadId).steering()
                    .stream().map(AgentTurnInbox.Item::text).toList());
        }
        try (RuntimeThreadStore reopened = new RuntimeThreadStore(db)) {
            AgentTurnInbox.Snapshot snapshot = reopened.queueSnapshot(threadId);
            assertEquals(List.of("interrupt"), snapshot.steering().stream()
                    .map(AgentTurnInbox.Item::text).toList());
            assertEquals(List.of("continue"), snapshot.followUp().stream()
                    .map(AgentTurnInbox.Item::text).toList());
        }
    }

    @Test
    void contextViewPrefersCompletedModelContextAndIgnoresFailedTurn(@TempDir Path tempDir)
            throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            String completedTurn = "completed-turn";
            store.appendEvent(threadId, "turn.started", RunEventJsonCodec.encode(
                    new RunEvent.TurnStarted("original input"), completedTurn));
            RunEvent.ModelContext context = RunEvent.ModelContext.from(1, List.of(
                    LlmClient.Message.system("system"),
                    LlmClient.Message.plugin("internal reminder")));
            store.appendEvent(threadId, context.type(),
                    RunEventJsonCodec.encode(context, completedTurn));
            RunEvent.ModelMessage answer = RunEvent.ModelMessage.from(
                    LlmClient.Message.assistant("answer"));
            store.appendEvent(threadId, answer.type(),
                    RunEventJsonCodec.encode(answer, completedTurn));
            long completedEventId = store.appendEvent(threadId, "turn.completed",
                    RunEventJsonCodec.encode(new RunEvent.TurnCompleted("completed"), completedTurn));

            String failedTurn = "failed-turn";
            store.appendEvent(threadId, "turn.started", RunEventJsonCodec.encode(
                    new RunEvent.TurnStarted("must not replay"), failedTurn));
            RunEvent.ModelContext failedContext = RunEvent.ModelContext.from(1, List.of(
                    LlmClient.Message.system("wrong"), LlmClient.Message.user("wrong")));
            store.appendEvent(threadId, failedContext.type(),
                    RunEventJsonCodec.encode(failedContext, failedTurn));
            store.appendEvent(threadId, "turn.failed",
                    RunEventJsonCodec.encode(new RunEvent.TurnFailed("boom"), failedTurn));

            RuntimeThreadStore.ContextView view = store.contextView(threadId);

            assertEquals(completedEventId, view.lastCompletedEventId());
            assertTrue(view.turns().isEmpty());
            assertEquals(List.of("system", "internal reminder", "answer"),
                    view.checkpointMessages().stream().map(LlmClient.Message::content).toList());
            assertEquals(LlmClient.MessageSource.PLUGIN,
                    view.checkpointMessages().get(1).source());
        }
    }

    @Test
    void sessionProjectionRebuildsCorruptedCacheFromEventLog(@TempDir Path tempDir)
            throws Exception {
        Path db = tempDir.resolve("runtime.db");
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            String threadId = store.createThread();
            String turnId = "turn-projection";
            store.appendEvent(threadId, "turn.started", RunEventJsonCodec.encode(
                    new RunEvent.TurnStarted("projection title"), turnId));
            store.appendEvent(threadId, "model.usage", RunEventJsonCodec.encode(
                    new RunEvent.ModelUsage(10, 4, 2, 0.125), turnId));
            ToolPresentation presentation = new ToolPresentation(
                    ToolPresentation.Kind.TERMINAL, "执行检查", "command",
                    Map.of("stream", "stdout"));
            store.appendEvent(threadId, "tool.calls", RunEventJsonCodec.encode(
                    new RunEvent.ToolCalls(List.of(new RunEvent.ToolCallData(
                            "call-1", "check", "{\"command\":\"verify\"}", presentation))),
                    turnId));
            store.appendEvent(threadId, "tool.results", RunEventJsonCodec.encode(
                    new RunEvent.ToolResults(List.of(new RunEvent.ToolResultData(
                            "call-1", "check", "{}", "failed", "ERROR",
                            "EXECUTION_FAILED", false, 20, 0, presentation))), turnId));
            store.appendEvent(threadId, "hook.result", RunEventJsonCodec.encode(
                    new RunEvent.HookInvocationCompleted(
                            "hook-call", "audit", "turn_end", "record",
                            "FAILED", "WARN", 3, "failed"), turnId));
            store.appendEvent(threadId, "turn.completed", RunEventJsonCodec.encode(
                    new RunEvent.TurnCompleted("completed"), turnId));

            RuntimeThreadStore.SessionProjection first = store.sessionProjection(threadId);
            assertEquals("projection title", first.title());
            assertEquals(10, first.inputTokens());
            assertEquals(1, first.toolCalls());
            assertEquals(1, first.toolFailures());
            assertEquals(1, first.hookCalls());
            assertEquals(1, first.hookFailures());

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 PreparedStatement ps = connection.prepareStatement("""
                         UPDATE runtime_session_projections
                         SET data_json = 'not-json'
                         WHERE thread_id = ? AND branch_id = 'main'
                         """)) {
                ps.setString(1, threadId);
                ps.executeUpdate();
            }

            RuntimeThreadStore.SessionProjection rebuilt = store.sessionProjection(threadId);
            assertEquals(first.title(), rebuilt.title());
            assertEquals(first.eventCursor(), rebuilt.eventCursor());
            assertEquals(first.estimatedCostCny(), rebuilt.estimatedCostCny());
        }
    }

    @Test
    void awaitEventsRechecksAfterAppendAndReturnsCommittedEvent(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            long cursor = store.events(threadId, 0).getLast().id();
            AtomicReference<List<RuntimeEvent>> result = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    result.set(store.awaitEvents(threadId, cursor, 8, Duration.ofSeconds(30)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();
            awaitWaiting(waiter);

            long eventId = store.appendEvent(threadId, "after.wait", "{}");

            waiter.join(1_000);
            assertFalse(waiter.isAlive());
            assertEquals(List.of(eventId), result.get().stream().map(RuntimeEvent::id).toList());
        }
    }

    @Test
    void closingStoreWakesIdleEventWaiterPromptly(@TempDir Path tempDir) throws Exception {
        RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
        String threadId = store.createThread();
        long cursor = store.events(threadId, 0).getLast().id();
        AtomicReference<List<RuntimeEvent>> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                result.set(store.awaitEvents(threadId, cursor, 8, Duration.ofSeconds(30)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            waiter.start();
            awaitWaiting(waiter);
            store.close();
            waiter.join(1_000);
            assertFalse(waiter.isAlive());
            assertEquals(List.of(), result.get());
        } finally {
            store.close();
        }
    }

    @Test
    void boundedVisibleEventsSkipInvisibleBranchRowsBeforeApplyingLimit(@TempDir Path tempDir)
            throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            long forkEventId = store.appendEvent(threadId, "shared", "{}");
            RuntimeThreadStore.BranchRecord branch = store.createBranch(
                    threadId, "alternative", forkEventId);

            store.activateBranch(threadId, "main");
            for (int index = 0; index < 256; index++) {
                store.appendEvent(threadId, "main.hidden", "{\"index\":" + index + "}");
            }
            store.activateBranch(threadId, branch.id());
            long visibleFirst = store.appendEvent(threadId, "branch.visible.first", "{}");
            store.appendEvent(threadId, "branch.visible.second", "{}");

            List<RuntimeEvent> events = store.events(threadId, forkEventId, 1);

            assertEquals(1, events.size());
            assertEquals(visibleFirst, events.getFirst().id());
            assertEquals("branch.visible.first", events.getFirst().type());
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (thread.isAlive()
                && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(thread.getState() == Thread.State.WAITING
                        || thread.getState() == Thread.State.TIMED_WAITING,
                "waiter did not enter wait state: " + thread.getState());
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
