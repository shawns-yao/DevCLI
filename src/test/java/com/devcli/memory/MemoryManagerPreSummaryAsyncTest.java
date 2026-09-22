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

    private static List<LlmClient.Message> history(String marker) {
        return List.of(
                LlmClient.Message.system("S"),
                LlmClient.Message.user(marker + " " + "x".repeat(2_100))
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
            return new ChatResponse("assistant", "摘要-" + call, List.of(), 10, 10);
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
}
