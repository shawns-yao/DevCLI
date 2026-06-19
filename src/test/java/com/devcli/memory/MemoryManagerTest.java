package com.devcli.memory;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径 B 重构后：MemoryManager 三层职责（WorkingMemory / LongTermMemory / 派生视图）。
 * 短期记忆压缩职责整体迁出——真实窗口治理由 {@link ConversationHistoryCompactor} 在
 * {@code Agent.conversationHistory} 上做。
 */
class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void addToolResultShouldRecordEvidenceInWorkingMemory() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.addToolResult("read_file", "{\"path\":\"pom.xml\"}", "<file>pom.xml content</file>");

            String section = memoryManager.buildWorkingMemorySection();
            assertTrue(section.contains("read_file"));
            assertTrue(section.contains("pom.xml"));
            assertEquals(1, memoryManager.getWorkingMemory().getRecentToolResults().size());
        }
    }

    @Test
    void addUserMessageShouldRecordVolatileFactSnippet() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.addUserMessage("帮我读取 pom.xml 并解释其中的依赖配置");

            String section = memoryManager.buildWorkingMemorySection();
            assertTrue(section.contains("用户最新输入"));
            assertTrue(memoryManager.getWorkingMemory().getVolatileFacts().stream()
                    .anyMatch(f -> f.contains("pom.xml")));
        }
    }

    @Test
    void addAssistantMessageShouldNotPolluteWorkingMemory() {
        // assistant 内容已在 conversationHistory 里，重复存到 working memory 没价值——v2 是 no-op
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.addAssistantMessage("已分析完毕，结果如下...");

            assertTrue(memoryManager.getWorkingMemory().getVolatileFacts().isEmpty());
            assertTrue(memoryManager.getWorkingMemory().getRecentToolResults().isEmpty());
        }
    }

    @Test
    void exposesStableSessionMemoryForConversationCompactor() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            assertSame(memoryManager.getSessionMemory(), memoryManager.getSessionMemory());
        }
    }

    @Test
    void clearShortTermShouldClearWorkingMemoryButPreserveLongTerm() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 4096, 128000, longTermMemory)) {
            memoryManager.storeFact("用户偏好简体中文");
            memoryManager.addToolResult("execute_command", "{\"cmd\":\"ls\"}", "stdout output");
            memoryManager.addVolatileFact("刚跑过 mvn test -Pquick");

            memoryManager.clearShortTerm();

            assertTrue(memoryManager.getWorkingMemory().getRecentToolResults().isEmpty());
            assertTrue(memoryManager.getWorkingMemory().getVolatileFacts().isEmpty());
            assertEquals(1, longTermMemory.size(), "/clear 不应清空长期记忆");
        }
    }

    @Test
    void shouldClearLongTermMemoryOnlyWhenExplicitlyRequested() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.storeFact("用户偏好使用中文交流");
            memoryManager.storeFact("项目路径: /tmp/demo");
            assertEquals(2, longTermMemory.size());

            memoryManager.clearLongTerm();

            assertEquals(0, longTermMemory.size());
        }
    }
    @Test
    void listLongTermMemoryShouldReturnReadOnlyPersistentSnapshot() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.storeFact("用户称号是派大星");
            memoryManager.storeFact("项目名是 DevCLI");

            String snapshot = memoryManager.listLongTermMemory(1);

            assertTrue(snapshot.contains("长期记忆（LongTermMemory）"));
            assertTrue(snapshot.contains("content:"));
            assertTrue(snapshot.contains("项目名是 DevCLI") || snapshot.contains("用户称号是派大星"));
            assertFalse(snapshot.contains("长期记忆为空"));
        }
    }

    @Test
    void listLongTermMemoryShouldReportEmptyStore() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            assertEquals("长期记忆为空。", memoryManager.listLongTermMemory(20));
        }
    }


    @Test
    void buildContextForQueryShouldIncludeInventorySnapshotWhenQueryDoesNotMatchMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.storeFact("用户的名称叫做派大星");

            String context = memoryManager.buildContextForQuery("你现在有什么长期记忆", 512);

            assertTrue(context.contains("长期记忆索引快照"));
            assertTrue(context.contains("total: 1"));
            assertTrue(context.contains("派大星"));
        }
    }


    @Test
    void searchCodeToolResultShouldRecordRagEvidenceWithSymbolVersion() {

        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            String result = """
                    检索摘要:
                    搜索摘要:
                    - 最相关的入口是 [method:CodeRetriever.search]，位于 rag/CodeRetriever.java。

                    检索结果:
                    1. [method:CodeRetriever.search(String,int,String,int)] (相似度: 0.910) src/main/java/com/devcli/rag/CodeRetriever.java
                       evidence: symbolVersion=sv_test123, indexEpoch=idx-1, classpathEpoch=cp-1
                       negativeFact: Do not rely on CodeRetriever.search from symbolVersion sv_old.
                       public List<SearchResult> search(...) { return List.of(); }
                    """;

            memoryManager.addToolResult("search_code", "{\"query\":\"CodeRetriever search\"}", result);

            String section = memoryManager.buildWorkingMemorySection();
            assertEquals(1, memoryManager.getWorkingMemory().getRagEvidenceMemory().size());
            assertTrue(section.contains("RAG 证据记忆"));
            assertTrue(section.contains("symbolVersion=sv_test123"));
            assertTrue(section.contains("indexEpoch=idx-1"));
            assertTrue(section.contains("classpathEpoch=cp-1"));
            assertTrue(section.contains("NegativeFact（负向事实）"));
            assertTrue(section.contains("query=CodeRetriever search"));
        }
    }

    @Test
    void storeFactWithPolicyShouldSkipLowValueTemporaryFacts() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy("今天地铁好挤，天气也不错");

            assertFalse(result.stored());
            assertEquals(LongTermMemoryPolicy.Action.SKIP, result.decision().action());
            assertEquals(0, longTermMemory.size());
        }
    }

    @Test
    void storeFactWithPolicyShouldPersistExplicitLowRiskPreferenceWithMetadata() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy("记住：我默认使用 Java 17 开发");

            assertTrue(result.stored(), result.message());
            assertEquals(1, longTermMemory.size());
            MemoryEntry entry = longTermMemory.getAll().get(0);
            assertEquals("preference", entry.getMetadata().get("memory_type"));
            assertEquals("explicit", entry.getMetadata().get("source"));
            assertEquals("EXPLICIT_STABLE_MEMORY", entry.getMetadata().get("reason_code"));
            assertEquals("HIGH", entry.getMetadata().get("confidence"));
            assertFalse(entry.getMetadata().containsKey("score"));
        }
    }

    @Test
    void storeFactWithPolicyShouldTreatSaveMemoryToolCallAsExplicitRequest() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy("用户默认使用简体中文短句回答", true);

            assertTrue(result.stored(), result.message());
            assertEquals("explicit", longTermMemory.getAll().get(0).getMetadata().get("source"));
        }
    }

    @Test
    void addUserMessageShouldAutoPersistStableProfileAttribute() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.addUserMessage("我是医生");

            assertEquals(1, longTermMemory.size());
            MemoryEntry entry = longTermMemory.getAll().get(0);
            assertEquals("我是医生", entry.getContent());
            assertEquals("PROFILE_ATTRIBUTE", entry.getMetadata().get("reason_code"));
        }
    }

    @Test
    void addUserMessageShouldPromoteRepeatedStableProjectFact() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.addUserMessage("项目默认测试命令是 mvn test -Pquick");
            memoryManager.addUserMessage("项目默认测试命令是 mvn test -Pquick");
            memoryManager.addUserMessage("项目默认测试命令是 mvn test -Pquick");

            assertEquals(1, longTermMemory.size());
            MemoryEntry entry = longTermMemory.getAll().get(0);
            assertEquals("recurrence", entry.getMetadata().get("source"));
            assertEquals("REPEATED_STABLE_MEMORY", entry.getMetadata().get("reason_code"));
        }
    }

    @Test
    void addUserMessageShouldNotPersistUnrelatedThirdPartyEvent() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.addUserMessage("我朋友的孩子今天高考");

            assertEquals(0, longTermMemory.size());
        }
    }

    @Test
    void compressionTriggerRatioAppliesToAllModelsUniformly() {
        // 验证：长 window 模型也使用统一的 90% 压缩触发阈值，没有"长模式不压缩"的二元开关
        MemoryManager memoryManager = new MemoryManager(new GLMClient("test-key"));

        assertEquals(0.90, memoryManager.getContextProfile().compressionTriggerRatio(), 0.001);
        assertEquals(200000, memoryManager.getTokenBudget().getContextWindow());
        assertEquals(180000, memoryManager.getContextProfile().compressionTriggerTokens());
    }

    @Test
    void taskStateRendersInWorkingMemorySection() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.setTaskState("plan_task", "task_3 (analyzing log)");
            memoryManager.setTaskState("last_error", "MCP schema missing required");

            String section = memoryManager.buildWorkingMemorySection();
            assertTrue(section.contains("plan_task"));
            assertTrue(section.contains("task_3"));
            assertTrue(section.contains("last_error"));
        }
    }

    @Test
    void buildWorkingMemorySectionReturnsEmptyWhenAllSubStoresEmpty() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            assertTrue(memoryManager.buildWorkingMemorySection().isBlank());
        }
    }

    @Test
    void buildWorkingMemorySectionForAgentShouldIsolateRoleSpecificViews() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.setTaskState("plan_task", "step_1");
            memoryManager.addVolatileFact("用户最新输入: 修复 agent 记忆隔离");
            memoryManager.addToolResult("read_file", "{\"path\":\"Secret.java\"}", "secret tool evidence");

            String planner = memoryManager.buildWorkingMemorySectionForAgent("planner");
            assertTrue(planner.contains("plan_task"));
            assertTrue(planner.contains("用户最新输入"));
            assertFalse(planner.contains("secret tool evidence"), planner);
            assertFalse(planner.contains("最近工具调用证据"), planner);

            String worker = memoryManager.buildWorkingMemorySectionForAgent("worker");
            assertTrue(worker.contains("plan_task"));
            assertTrue(worker.contains("用户最新输入"));
            assertTrue(worker.contains("secret tool evidence"));

            String reviewer = memoryManager.buildWorkingMemorySectionForAgent("reviewer");
            assertTrue(reviewer.contains("plan_task"));
            assertTrue(reviewer.contains("secret tool evidence"));
            assertFalse(reviewer.contains("用户最新输入"), reviewer);
        }
    }

    @Test
    void workingMemoryShouldTolerateParallelSubAgentToolWrites() throws Exception {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            int workers = 5;
            int writesPerWorker = 20;
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            CountDownLatch ready = new CountDownLatch(workers);
            CountDownLatch start = new CountDownLatch(1);

            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < writesPerWorker; i++) {
                            memoryManager.addToolResult(
                                    "worker_" + workerId,
                                    "{\"i\":" + i + "}",
                                    "result-" + workerId + '-' + i);
                            memoryManager.addVolatileFact("worker-" + workerId + "-fact-" + i);
                            memoryManager.buildWorkingMemorySection();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(WorkingMemory.DEFAULT_MAX_TOOL_RESULTS,
                    memoryManager.getWorkingMemory().getRecentToolResults().size());
            assertEquals(WorkingMemory.DEFAULT_MAX_VOLATILE_FACTS,
                    memoryManager.getWorkingMemory().getVolatileFacts().size());
            assertFalse(memoryManager.buildWorkingMemorySection().isBlank());
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
