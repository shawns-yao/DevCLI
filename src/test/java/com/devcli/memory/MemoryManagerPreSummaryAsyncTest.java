package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerPreSummaryAsyncTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsOnlyLatestPendingPreSummaryWhileOneRequestIsRunning() throws Exception {
        BlockingClient client = new BlockingClient();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     client, 4_096, 128_000, longTermMemory)) {
            CompletableFuture<MemoryManager.SessionPreSummaryMaintenanceResult> first =
                    memoryManager.maintainSessionPreSummaryAfterTurnAsync(
                            history("first"), 4, 0);
            assertTrue(client.firstCallStarted.await(2, TimeUnit.SECONDS));

            CompletableFuture<MemoryManager.SessionPreSummaryMaintenanceResult> superseded =
                    memoryManager.maintainSessionPreSummaryAfterTurnAsync(
                            history("superseded"), 4, 0);
            CompletableFuture<MemoryManager.SessionPreSummaryMaintenanceResult> latest =
                    memoryManager.maintainSessionPreSummaryAfterTurnAsync(
                            history("latest"), 4, 0);

            assertTrue(superseded.isDone(), "较新的快照应替换尚未执行的旧任务");
            assertNotEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    superseded.get(100, TimeUnit.MILLISECONDS));

            client.releaseFirstCall.countDown();
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    first.get(2, TimeUnit.SECONDS));
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    latest.get(2, TimeUnit.SECONDS));
            assertEquals(2, client.calls.get(), "运行中任务结束后只应执行最新待处理快照");
            assertTrue(client.prompts.get(1).contains("latest"));
            assertTrue(!client.prompts.get(1).contains("superseded"));
        } finally {
            client.releaseFirstCall.countDown();
        }
    }

    @Test
    void exhaustedBudgetDoesNotStartBackgroundModelCall() throws Exception {
        BlockingClient client = new BlockingClient();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager manager = new MemoryManager(client, 4_096, 128_000, longTermMemory)) {
            var result = manager.maintainSessionPreSummaryAfterTurnAsync(
                    history("budget"), 4, 0, 100_000, () -> false,
                    response -> { throw new AssertionError("预算耗尽后不得产生调用费用"); });
            assertNotEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    result.get(2, TimeUnit.SECONDS));
            assertEquals(0, client.calls.get());
            assertTrue(manager.getCompactionSummaryCache().currentPreSummary().isEmpty());
        }
    }

    @Test
    void asyncProductionSnapshotIsReusableByFormalCompaction() throws Exception {
        BlockingClient client = new BlockingClient();
        client.releaseFirstCall.countDown();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager manager = new MemoryManager(client, 4_096, 128_000, longTermMemory)) {
            List<LlmClient.Message> history = new java.util.ArrayList<>(history("closed-loop"));

            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    manager.maintainSessionPreSummaryAfterTurnAsync(history, 4, 0, 100,
                            () -> true, response -> { }).get(2, TimeUnit.SECONDS));
            assertEquals(1, client.calls.get());

            ConversationHistoryCompactor formal = new ConversationHistoryCompactor(client);
            formal.setCompactionSummaryCache(manager.getCompactionSummaryCache());
            assertTrue(formal.compactIfNeeded(history, 100));
            assertEquals(1, client.calls.get(),
                    "正式压缩应消费异步生产的预摘要，而不是再次请求摘要");
        }
    }

    @Test
    void deterministicPreSummaryFailureEntersFingerprintCooldown() throws Exception {
        FailingSummaryClient client = new FailingSummaryClient();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager manager = new MemoryManager(client, 4_096, 128_000, longTermMemory)) {
            List<LlmClient.Message> history = history("failure-cooldown");

            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.FAILED,
                    manager.maintainSessionPreSummaryAfterTurn(history, 4, 0));
            int callsAfterFirstAttempt = client.calls.get();
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.SKIPPED_FAILURE_COOLDOWN,
                    manager.maintainSessionPreSummaryAfterTurn(history, 4, 0));
            assertEquals(callsAfterFirstAttempt, client.calls.get(),
                    "相同失败输入在冷却期内不得重复消耗模型调用");
        }
    }

    @Test
    void switchingLlmClientClearsPreSummaryCache() throws Exception {
        BlockingClient first = new BlockingClient();
        first.releaseFirstCall.countDown();
        BlockingClient second = new BlockingClient();
        second.releaseFirstCall.countDown();
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager manager = new MemoryManager(first, 4_096, 128_000, longTermMemory)) {
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    manager.maintainSessionPreSummaryAfterTurn(history("model-a"), 4, 0));
            assertTrue(manager.getCompactionSummaryCache().currentPreSummary().isPresent());

            manager.setLlmClient(second);

            assertTrue(manager.getCompactionSummaryCache().currentPreSummary().isEmpty(),
                    "切换模型后不得复用旧模型生成的预摘要");
        }
    }

    private static List<LlmClient.Message> history(String marker) {
        return List.of(
                LlmClient.Message.system("S"),
                LlmClient.Message.user(marker + " " + "x".repeat(2_100)),
                LlmClient.Message.assistant("completed"),
                LlmClient.Message.user("retained-tail " + "中".repeat(24_000))
        );
    }

    private static final class BlockingClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstCallStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            int call = calls.incrementAndGet();
            prompts.add(messages.stream()
                    .map(message -> message.content() == null ? "" : message.content())
                    .reduce("", (left, right) -> left + "\n" + right));
            if (call == 1) {
                firstCallStarted.countDown();
                try {
                    if (!releaseFirstCall.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("等待测试释放超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("预摘要调用被中断", e);
                }
            }
            return new ChatResponse("assistant", """
                    {"schema_version":2,"request_intent":"异步预摘要-%d",\
                    "concepts":[],"files":[],"pitfalls":[],"resolution_steps":[],\
                    "user_messages":["异步预摘要"],"protected_facts":[]}
                    """.formatted(call), List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "blocking-test";
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

    private static final class FailingSummaryClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            calls.incrementAndGet();
            return new ChatResponse("assistant", "", List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "failing-summary";
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
