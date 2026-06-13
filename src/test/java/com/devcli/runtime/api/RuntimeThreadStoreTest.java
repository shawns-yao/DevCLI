package com.devcli.runtime.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    private static void appendTurn(RuntimeThreadStore store, String threadId,
                                   String turnId, String input, String output) {
        store.appendEvent(threadId, "turn.started",
                "{\"turn_id\":\"" + turnId + "\",\"input\":\"" + input + "\"}");
        store.appendEvent(threadId, "message.delta",
                "{\"turn_id\":\"" + turnId + "\",\"content\":\"" + output + "\"}");
        store.appendEvent(threadId, "turn.completed",
                "{\"turn_id\":\"" + turnId + "\",\"status\":\"completed\"}");
    }
}
