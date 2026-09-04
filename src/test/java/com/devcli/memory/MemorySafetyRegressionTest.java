package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySafetyRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void ignoreMemoryDoesNotRecordCurrentTaskOrQueuePromotion() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new NoopClient(), 4_096, 128_000, longTermMemory)) {
            memoryManager.setMemoryCuratorClient(new NoopCurator());
            memoryManager.beginTask("ignore-task");
            memoryManager.addUserMessage("这次不要使用记忆，请只处理当前请求");
            memoryManager.addToolResult("read_file", "{\"path\":\"secret.txt\"}", "secret content");

            assertTrue(memoryManager.isMemoryIgnored());
            assertTrue(memoryManager.getSessionMemory().getVolatileFacts().isEmpty());
            assertTrue(memoryManager.getSessionMemory().getRecentToolResults().isEmpty());
            assertTrue(memoryManager.completeTask(
                    "ignore-task", "当前请求", "完成", tempDir.toString()).isBlank());
        }
    }

    @Test
    void clearingShortTermMemoryReenablesMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new NoopClient(), 4_096, 128_000, longTermMemory)) {
            memoryManager.addUserMessage("这次不要使用记忆");
            assertTrue(memoryManager.isMemoryIgnored());

            memoryManager.clearShortTerm();

            assertFalse(memoryManager.isMemoryIgnored());
        }
    }

    @Test
    void editFileIsRetainedAsModifiedEvidence() {
        SessionMemory memory = new SessionMemory();
        memory.recordToolResult("edit_file", "{\"path\":\"src/App.java\"}",
                "文件已精确修改: src/App.java");

        assertTrue(memory.snapshot().modifiedFiles().contains("src/App.java"));
        assertTrue(memory.snapshot().evidenceJournal().stream()
                .anyMatch(item -> item.kind() == SessionMemory.EvidenceKind.CRITICAL));
        assertTrue(memory.renderForPostCompactRestore().contains("src/App.java"));
    }

    @Test
    void curatorOutputIsRedactedBeforePersistence() throws Exception {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new NoopClient(), 4_096, 128_000, longTermMemory)) {
            memoryManager.setMemoryCuratorClient(new LlmClient() {
                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                    return new ChatResponse("assistant", """
                            {"action":"SAVE","kind":"FACT","content":"token=sk-live-secret-value",
                             "scope_type":"GLOBAL","scope_key":"","confidence":"HIGH",
                             "source_refs":["result"],"reason":"stub"}
                            """, List.of(), 1, 1);
                }

                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                         StreamListener listener) {
                    return chat(messages, tools);
                }

                @Override
                public String getModelName() { return "curator"; }

                @Override
                public String getProviderName() { return "test"; }
            });
            memoryManager.beginTask("curator-task");
            String job = memoryManager.completeTask(
                    "curator-task", "记录构建命令", "构建完成", tempDir.toString());
            assertFalse(job.isBlank());

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline
                    && longTermMemory.getAll().stream().noneMatch(entry ->
                    entry.getContent().contains("sk-live-secret-value"))) {
                Thread.sleep(20);
            }
            assertTrue(longTermMemory.getAll().stream().noneMatch(entry ->
                    entry.getContent().contains("sk-live-secret-value")));
        }
    }

    @Test
    void failedPromotionIsRetriedWithoutASecondTask() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new NoopClient(), 4_096, 128_000, longTermMemory)) {
            memoryManager.setMemoryCuratorClient(new LlmClient() {
                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
                    if (calls.incrementAndGet() == 1) {
                        throw new IOException("temporary curator failure");
                    }
                    return new ChatResponse("assistant", "{\"action\":\"SKIP\"}", List.of(), 1, 1);
                }

                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                         StreamListener listener) throws IOException {
                    return chat(messages, tools);
                }

                @Override
                public String getModelName() { return "retry-curator"; }

                @Override
                public String getProviderName() { return "test"; }
            });
            memoryManager.beginTask("retry-task");
            assertFalse(memoryManager.completeTask(
                    "retry-task", "记录事实", "完成", tempDir.toString()).isBlank());

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (calls.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(calls.get() >= 2, "后台失败后应自动重试");
        }
    }

    @Test
    void taskSnapshotCarriesModifiedFilesAndOpenTaskState() {
        SessionMemory session = new SessionMemory();
        session.accept(new SessionMemory.PlanChanged(
                "plan-1", "修复构建", java.util.Map.of("step-1", "修改入口"),
                "planner", "", 1));
        session.accept(new SessionMemory.StepChanged(
                "step-1", TaskLedger.StepStatus.PENDING, "", "worker", 2));
        session.recordToolResult("edit_file", "{\"path\":\"src/App.java\"}",
                "文件已精确修改: src/App.java");

        TaskMemorySnapshot snapshot = TaskMemorySnapshot.capture(
                "task", "project", "请求", "结果", session.snapshot());

        assertTrue(snapshot.evidenceSummaries().stream()
                .anyMatch(value -> value.contains("src/App.java")));
        assertTrue(snapshot.evidenceSummaries().stream()
                .anyMatch(value -> value.contains("step-1")));
    }

    @Test
    void longUserConstraintIsStoredOutsideTheShortPreview() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new NoopClient(), 4_096, 128_000, longTermMemory)) {
            memoryManager.beginTask("constraint-task");
            memoryManager.addUserMessage(
                    "背景说明 ".repeat(20) + "仍然要求：禁止修改 config 目录。" );

            assertTrue(memoryManager.buildSessionMemorySection().contains("禁止修改 config 目录"));
        }
    }

    @Test
    void memoryListingRedactsEvidenceReasoningAndMetadata() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new NoopClient(), 4_096, 128_000, longTermMemory)) {
            MemoryEntry entry = new MemoryEntry(
                    "sensitive-reasoning", "普通事实", MemoryEntry.MemoryType.FACT,
                    java.time.Instant.now(), Map.of("debug", "token=metadata-secret"), 4,
                    "fact", true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1, null,
                    new MemoryEvidence(MemoryEvidence.Confidence.LOW, "", "token=reasoning-secret",
                            MemoryEvidence.ReviewState.REVIEWED, List.of()));
            longTermMemory.storeManaged(entry);

            String rendered = memoryManager.listLongTermMemory(10);

            assertFalse(rendered.contains("metadata-secret"));
            assertFalse(rendered.contains("reasoning-secret"));
        }
    }

    private static final class NoopCurator implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "{\"action\":\"SKIP\"}", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() { return "curator"; }

        @Override
        public String getProviderName() { return "test"; }
    }

    private static final class NoopClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() { return "test"; }

        @Override
        public String getProviderName() { return "test"; }

        @Override
        public int maxContextWindow() { return 128_000; }
    }
}
