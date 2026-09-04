package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPromotionPipelineTest {
    @TempDir
    Path tempDir;

    @Test
    void claimedJobIsReplayableAfterProcessCrash() {
        TaskMemorySnapshot snapshot = snapshot("task-crash");
        String jobId;
        try (MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            jobId = queue.enqueue(snapshot);
            assertEquals(jobId, queue.claimNext().orElseThrow().id());
        }

        try (MemoryPromotionQueue reopened = new MemoryPromotionQueue(tempDir)) {
            MemoryPromotionQueue.Job replayed = reopened.claimNext().orElseThrow();
            assertEquals(jobId, replayed.id());
            reopened.markCommitted(jobId, "memory-1");
            assertEquals(MemoryPromotionQueue.State.COMMITTED,
                    reopened.find(jobId).orElseThrow().state());
        }
    }

    @Test
    void durableSnapshotRedactsSecretsBeforeEnqueue() {
        TaskMemorySnapshot captured = TaskMemorySnapshot.capture(
                "task-secret", "project-a", "token=tok-private-value",
                "使用 api_key=sk-private-value 完成", new SessionMemory().snapshot());

        assertFalse(captured.userRequest().contains("tok-private-value"));
        assertFalse(captured.finalResult().contains("sk-private-value"));
    }

    @Test
    void directSnapshotConstructionStillRedactsBeforePersistence() {
        TaskMemorySnapshot snapshot = new TaskMemorySnapshot(
                "task-direct-secret", "project-a", "token=tok-direct-private",
                "api_key=sk-direct-private", Map.of("credential", "password=secret-value"),
                List.of("authorization: Bearer bearer-private"), Instant.now());

        assertFalse(snapshot.userRequest().contains("tok-direct-private"));
        assertFalse(snapshot.finalResult().contains("sk-direct-private"));
        assertFalse(snapshot.workState().get("credential").contains("secret-value"));
        assertFalse(snapshot.evidenceSummaries().getFirst().contains("bearer-private"));
    }

    @Test
    void durableSnapshotIsBoundedBeforeCuratorInjection() {
        TaskMemorySnapshot snapshot = new TaskMemorySnapshot(
                "task-large", "project-a", "x".repeat(50_000), "y".repeat(50_000),
                Map.of("large", "z".repeat(20_000)),
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(index -> "e".repeat(3_000))
                        .toList(), Instant.now());

        assertTrue(snapshot.userRequest().length() <= 12_000);
        assertTrue(snapshot.finalResult().length() <= 12_000);
        assertTrue(snapshot.workState().get("large").length() <= 2_000);
        assertTrue(snapshot.evidenceSummaries().size() <= 32);
        assertTrue(snapshot.evidenceSummaries().stream().allMatch(item -> item.length() <= 2_000));
    }

    @Test
    void snapshotResolvesDurableSourceExcerptWithoutConversationHistory() {
        TaskMemorySnapshot snapshot = snapshot("task-source");

        assertEquals("修复构建流程", snapshot.sourceExcerpt("request").orElseThrow());
        assertEquals("已完成", snapshot.sourceExcerpt("result").orElseThrow());
        assertEquals("修复构建流程", snapshot.sourceExcerpt("state:goal").orElseThrow());
        assertEquals("execute_command: mvn test 通过",
                snapshot.sourceExcerpt("evidence:1").orElseThrow());
        assertTrue(snapshot.sourceExcerpt("history:old").isEmpty());
    }

    @Test
    void isolatedCuratorRejectsInventedProvenanceAndUnknownScope() {
        LlmClient inventedReference = new StubClient(new AtomicReference<>(), new AtomicReference<>(), """
                {"action":"SAVE","kind":"FACT","content":"可复用事实",\
                "scope_type":"PROJECT","scope_key":"project-a","confidence":"HIGH",\
                "source_refs":["history:old"]}
                """);
        LlmClient unknownScope = new StubClient(new AtomicReference<>(), new AtomicReference<>(), """
                {"action":"SAVE","kind":"FACT","content":"可复用事实",\
                "scope_type":"EVERYWHERE","scope_key":"project-a","confidence":"HIGH",\
                "source_refs":["request"]}
                """);

        assertThrows(IOException.class,
                () -> new IsolatedMemoryCurator(inventedReference).curate(snapshot("task-ref")));
        assertThrows(IOException.class,
                () -> new IsolatedMemoryCurator(unknownScope).curate(snapshot("task-scope")));
    }

    @Test
    void clearingQueuePreventsClaimedJobFromCommittingLater() {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            String jobId = queue.enqueue(snapshot("task-clear"));
            MemoryPromotionQueue.Job claimed = queue.claimNext().orElseThrow();
            queue.deleteAllJobs();

            assertFalse(queue.commitIfState(claimed.id(),
                    java.util.Set.of(MemoryPromotionQueue.State.CURATING), () -> "memory-never"));
            assertTrue(queue.find(jobId).isEmpty());
            assertEquals(0, memory.size());
        }
    }

    @Test
    void identicalContentInDifferentProjectsIsNotDeduplicated() {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            MemoryCurator curator = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, "PROCEDURE", "构建命令是 mvn test",
                    "PROJECT", "untrusted", "HIGH", List.of("result"), "");
            MemoryPromotionPipeline pipeline = new MemoryPromotionPipeline(queue, curator, memory);

            String first = pipeline.enqueue(snapshot("task-project-a"));
            TaskMemorySnapshot projectB = new TaskMemorySnapshot(
                    "task-project-b", "project-b", "验证构建", "已完成",
                    Map.of(), List.of("mvn test 通过"), Instant.now());
            String second = pipeline.enqueue(projectB);

            assertTrue(pipeline.processNext());
            assertTrue(pipeline.processNext());
            assertEquals(MemoryPromotionQueue.State.COMMITTED, queue.find(first).orElseThrow().state());
            assertEquals(MemoryPromotionQueue.State.COMMITTED, queue.find(second).orElseThrow().state());
            assertEquals(2, memory.getAll().stream()
                    .filter(entry -> entry.getContent().equals("构建命令是 mvn test"))
                    .count());
        }
    }

    @Test
    void isolatedCuratorReceivesNoToolsAndOnlyCurrentSnapshot() throws Exception {
        AtomicReference<List<LlmClient.Message>> seenMessages = new AtomicReference<>();
        AtomicReference<List<LlmClient.Tool>> seenTools = new AtomicReference<>();
        LlmClient client = new StubClient(seenMessages, seenTools, """
                {"action":"SAVE","kind":"PROCEDURE","content":"构建命令是 mvn test",\
                "scope_type":"PROJECT","scope_key":"project-a","confidence":"HIGH",\
                "source_refs":["request"]}
                """);

        IsolatedMemoryCurator.Decision decision = new IsolatedMemoryCurator(client)
                .curate(snapshot("task-isolated"));

        assertEquals(IsolatedMemoryCurator.Action.SAVE, decision.action());
        assertTrue(seenTools.get().isEmpty());
        assertEquals(2, seenMessages.get().size());
        assertTrue(seenMessages.get().get(1).content().contains("task-isolated"));
        assertFalse(seenMessages.get().get(1).content().contains("历史长期记忆"));
    }

    @Test
    void saveDecisionCommitsFactAndConfirmDecisionDoesNotBlock() {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            MemoryCurator save = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, "LESSON", "不要在主机运行构建命令",
                    "PROJECT", "project-a", "HIGH", List.of("evidence:1"), "");
            MemoryPromotionPipeline pipeline = new MemoryPromotionPipeline(queue, save, memory);
            String savedJob = pipeline.enqueue(snapshot("task-save"));

            assertTrue(pipeline.processNext());
            assertEquals(MemoryPromotionQueue.State.COMMITTED,
                    queue.find(savedJob).orElseThrow().state());
            MemoryEntry saved = memory.getAll().stream()
                    .filter(entry -> entry.getContent().contains("不要在主机运行"))
                    .findFirst().orElseThrow();
            assertEquals(MemoryEntry.MemoryKind.LESSON, saved.getKind());
            assertEquals(MemoryEvidence.ReviewState.CURATED, saved.getEvidence().reviewState());
            assertEquals("execute_command: mvn test 通过", saved.getEvidence().sourceQuote());
            assertEquals("task-save", saved.getMetadata().get("source_task_id"));
            assertFalse(saved.getMetadata().getOrDefault("source_quote_sha256", "").isBlank());

            MemoryCurator confirm = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.CONFIRM, "PREFERENCE", "以后都跳过测试",
                    "USER", "default", "MEDIUM", List.of("request"), "需要用户确认");
            MemoryPromotionPipeline confirmPipeline = new MemoryPromotionPipeline(queue, confirm, memory);
            String confirmJob = confirmPipeline.enqueue(snapshot("task-confirm"));

            assertTrue(confirmPipeline.processNext());
            assertEquals(MemoryPromotionQueue.State.AWAITING_CONFIRMATION,
                    queue.find(confirmJob).orElseThrow().state());
            assertTrue(confirmPipeline.confirm(confirmJob, true, ""));
            assertEquals(MemoryPromotionQueue.State.COMMITTED,
                    queue.find(confirmJob).orElseThrow().state());
            MemoryEntry confirmed = memory.getAll().stream()
                    .filter(entry -> entry.getContent().equals("以后都跳过测试"))
                    .findFirst().orElseThrow();
            assertEquals(MemoryEntry.MemoryKind.PREFERENCE, confirmed.getKind());
            assertEquals(MemoryEvidence.ReviewState.REVIEWED, confirmed.getEvidence().reviewState());
            assertEquals(1, confirmed.getValidatedUseCount());
        }
    }

    @Test
    void lowConfidenceSaveIsDowngradedToConfirmation() {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            MemoryCurator curator = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, "FACT", "项目使用 Java 17",
                    "PROJECT", "project-a", "MEDIUM", List.of("request"), "模型不确定");
            MemoryPromotionPipeline pipeline = new MemoryPromotionPipeline(queue, curator, memory);
            String jobId = pipeline.enqueue(snapshot("task-medium"));

            assertTrue(pipeline.processNext());

            assertEquals(MemoryPromotionQueue.State.AWAITING_CONFIRMATION,
                    queue.find(jobId).orElseThrow().state());
            assertEquals(0, memory.size());
        }
    }

    @Test
    void lateFailureCannotOverwriteCommittedPromotion() {
        try (MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            String jobId = queue.enqueue(snapshot("task-race"));
            queue.claimNext().orElseThrow();
            queue.markCommitted(jobId, "memory-race");

            queue.markFailedRetryable(jobId, "late curator failure");

            assertEquals(MemoryPromotionQueue.State.COMMITTED,
                    queue.find(jobId).orElseThrow().state());
        }
    }

    @Test
    void promotionGateCombinesDeduplicationConfirmationSupersedeAndExpiry() {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            MemoryCurator curated = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, "PROCEDURE", "项目默认测试命令是 mvn test",
                    "PROJECT", "untrusted", "HIGH", List.of("evidence:1"), "");
            MemoryPromotionPipeline pipeline = new MemoryPromotionPipeline(queue, curated, memory);

            String firstJob = pipeline.enqueue(snapshot("task-gate-first"));
            assertTrue(pipeline.processNext());
            String firstMemoryId = queue.find(firstJob).orElseThrow().resultRef();

            String duplicateJob = pipeline.enqueue(snapshot("task-gate-duplicate"));
            assertTrue(pipeline.processNext());
            assertEquals(MemoryPromotionQueue.State.COMMITTED,
                    queue.find(duplicateJob).orElseThrow().state());
            assertEquals(1, memory.getAll().stream()
                    .filter(entry -> entry.getContent().equals("项目默认测试命令是 mvn test"))
                    .count(), "重复事实只能保留一个 revision");

            MemoryCurator pending = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, "PROCEDURE", "项目默认测试命令是 mvn verify",
                    "PROJECT", "untrusted", "MEDIUM", List.of("evidence:1"), "待用户确认");
            MemoryPromotionPipeline pendingPipeline = new MemoryPromotionPipeline(queue, pending, memory);
            String pendingJob = pendingPipeline.enqueue(snapshot("task-gate-confirm"));
            assertTrue(pendingPipeline.processNext());
            assertEquals(MemoryPromotionQueue.State.AWAITING_CONFIRMATION,
                    queue.find(pendingJob).orElseThrow().state());
            assertTrue(memory.getAll().stream()
                            .filter(entry -> entry.getContent().equals("项目默认测试命令是 mvn verify"))
                            .noneMatch(MemoryEntry::isRecallable),
                    "未确认候选不得进入可召回集合");

            assertTrue(pendingPipeline.confirm(pendingJob, true, ""));
            String confirmedId = queue.find(pendingJob).orElseThrow().resultRef();
            assertEquals(MemoryPromotionQueue.State.COMMITTED,
                    queue.find(pendingJob).orElseThrow().state());
            MemoryEntry confirmed = memory.retrieve(confirmedId).orElseThrow();
            assertTrue(confirmed.isRecallable());
            assertEquals(1, confirmed.getValidatedUseCount());
            assertFalse(memory.retrieve(firstMemoryId).orElseThrow().isActive(),
                    "确认的新事实应 supersede 旧事实，但旧事实仍保留审计");

            memory.storeManaged(confirmed.withLifecycle(
                    confirmed.getRevision() + 1,
                    Instant.now().minusSeconds(1),
                    confirmed.getMetadata()));
            assertTrue(memory.search("项目默认测试命令是 mvn verify", 5).isEmpty(),
                    "过期事实不得继续被召回");
            MemoryEntry archived = memory.retrieve(confirmedId).orElseThrow();
            assertFalse(archived.isActive());
            assertEquals("ARCHIVED", archived.getMetadata().get("lifecycle_state"));
        }
    }

    @Test
    void sourceExcerptSurvivesPromotionQueueDeletionAndReload() {
        String memoryId;
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryPromotionQueue queue = new MemoryPromotionQueue(tempDir)) {
            MemoryCurator curator = ignored -> new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, "PROCEDURE", "构建命令是 mvn test",
                    "PROJECT", "project-a", "HIGH", List.of("evidence:1"), "可复用流程");
            MemoryPromotionPipeline pipeline = new MemoryPromotionPipeline(queue, curator, memory);
            String jobId = pipeline.enqueue(snapshot("task-durable-source"));

            assertTrue(pipeline.processNext());
            memoryId = queue.find(jobId).orElseThrow().resultRef();
            queue.deleteAllJobs();
        }

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry entry = reloaded.retrieve(memoryId).orElseThrow();
            assertEquals("execute_command: mvn test 通过", entry.getEvidence().sourceQuote());
            assertEquals("task-durable-source", entry.getMetadata().get("source_task_id"));
            assertEquals("SNAPSHOT", entry.getMetadata().get("source_availability"));
        }
    }

    private static TaskMemorySnapshot snapshot(String taskId) {
        return new TaskMemorySnapshot(taskId, "project-a", "修复构建流程", "已完成",
                Map.of("goal", "修复构建流程"), List.of("execute_command: mvn test 通过"),
                Instant.parse("2026-08-27T00:00:00Z"));
    }

    private static final class StubClient implements LlmClient {
        private final AtomicReference<List<Message>> messages;
        private final AtomicReference<List<Tool>> tools;
        private final String response;

        private StubClient(AtomicReference<List<Message>> messages,
                           AtomicReference<List<Tool>> tools, String response) {
            this.messages = messages;
            this.tools = tools;
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            this.messages.set(List.copyOf(messages));
            this.tools.set(List.copyOf(tools));
            return new ChatResponse("assistant", response, List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
