package com.devcli.memory;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.rag.RagEvidencePayload;
import com.devcli.rag.RagEvidenceSideChannel;
import com.devcli.rag.SymbolInvalidation;
import com.devcli.rag.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径 B 重构后：MemoryManager 三层职责（SessionMemory / LongTermMemory / 派生视图）。
 * 短期记忆压缩职责整体迁出——真实窗口治理由 {@link ConversationHistoryCompactor} 在
 * {@code Agent.conversationHistory} 上做。
 */
class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void addToolResultShouldRecordEvidenceInSessionMemory() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.addToolResult("read_file", "{\"path\":\"pom.xml\"}", "<file>pom.xml content</file>");

            String section = memoryManager.buildSessionMemorySection();
            assertTrue(section.contains("read_file"));
            assertTrue(section.contains("pom.xml"));
            assertEquals(1, memoryManager.getSessionMemory().getRecentToolResults().size());
        }
    }

    @Test
    void currentGradleObservationImmediatelySupersedesConflictingMavenMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");
            MemoryEntry old = longTermMemory.getAll().getFirst();

            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] gradlew\n[F] build.gradle\n[D] src\n");

            MemoryEntry superseded = longTermMemory.retrieve(old.getId()).orElseThrow();
            assertFalse(superseded.isActive());
            MemoryEntry negative = longTermMemory.getAll().stream()
                    .filter(MemoryEntry::isActive)
                    .findFirst()
                    .orElseThrow();
            assertEquals("true", negative.getMetadata().get("negative_fact"));
            assertEquals("CURRENT_STATE_CONFLICT", negative.getMetadata().get("reason_code"));
            assertEquals(old.getId(), negative.getMetadata().get("invalidates_memory_ids"));
            assertEquals(List.of(old.getId()), negative.getEvidence().conflictsWith());
            assertEquals(negative.getId(), superseded.getSupersededBy());
            assertTrue(memoryManager.buildSessionMemorySection().contains("NegativeFact（负向事实）"));
            assertTrue(memoryManager.drainCurrentStateConflictInstruction()
                    .contains("程序已确认当前状态推翻旧记忆"));
            assertTrue(memoryManager.drainCurrentStateConflictInstruction().isBlank());
            assertTrue(longTermMemory.getStatusSummary().contains("状态推翻: 1"));
            assertFalse(memoryManager.buildContextForQuery("项目构建工具", 512)
                    .contains("项目构建工具使用 Maven"));
        }
    }

    @Test
    void typedCurrentStateObservationInvalidatesAnyStructuredSubject() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("java.version=17");
            MemoryEntry old = longTermMemory.getAll().getFirst();

            memoryManager.addToolResult("read_file", "{\"path\":\"pom.xml\"}",
                    "文件内容: <maven.compiler.release>21</maven.compiler.release>",
                    List.of(new CurrentStateObservationSideChannel(
                            "key:java.version", "21", "pom.xml 声明 Java 21", "HIGH")));

            assertFalse(longTermMemory.retrieve(old.getId()).orElseThrow().isActive());
            MemoryEntry active = longTermMemory.getAll().stream()
                    .filter(MemoryEntry::isActive).findFirst().orElseThrow();
            assertEquals("21", active.getMetadata().get("observed_value"));
            assertEquals(List.of(old.getId()), active.getEvidence().conflictsWith());
        }
    }

    @Test
    void lowConfidenceObservationWarnsWithoutSupersedingMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("java.version=17");
            MemoryEntry old = longTermMemory.getAll().getFirst();

            memoryManager.addToolResult("read_file", "{\"path\":\"notes.txt\"}",
                    "文件内容: java.version=21",
                    List.of(new CurrentStateObservationSideChannel(
                            "key:java.version", "21", "普通文本提及 Java 21", "LOW")));

            assertTrue(longTermMemory.retrieve(old.getId()).orElseThrow().isActive());
            assertEquals(1, longTermMemory.size());
            assertTrue(memoryManager.drainCurrentStateConflictInstruction().contains("低强度观察"));
        }
    }

    @Test
    void nestedBuildFileDoesNotOverrideProjectBuildSystem() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");
            MemoryEntry old = longTermMemory.getAll().getFirst();

            memoryManager.addToolResult("read_file", "{\"path\":\"examples/gradle/build.gradle\"}",
                    "文件内容: plugins { id 'java' }");

            assertTrue(longTermMemory.retrieve(old.getId()).orElseThrow().isActive());
            assertEquals(1, longTermMemory.size());
        }
    }

    @Test
    void ruleConflictIsSurfacedForUserDecision() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.setRuleContextSupplier(() -> "## 强约束规则\n- 必须使用 Maven 构建");

            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] gradlew\n[F] build.gradle\n[D] src\n");

            String instruction = memoryManager.drainCurrentStateConflictInstruction();
            assertTrue(instruction.contains("规则与当前状态冲突"));
            assertTrue(instruction.contains("用户裁决"));
        }
    }

    @Test
    void repeatedObservationDoesNotCreateDuplicateConflictAuditEntries() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");

            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] gradlew\n[F] build.gradle\n");
            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] gradlew\n[F] build.gradle\n");

            assertEquals(2, longTermMemory.size());
        }
    }

    @Test
    void documentationMentionOfGradleDoesNotInvalidateBuildSystemMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");
            MemoryEntry old = longTermMemory.getAll().getFirst();

            memoryManager.addToolResult("read_file", "{\"path\":\"README.md\"}",
                    "文件内容:\n其他项目可以运行 ./gradlew test");

            assertTrue(longTermMemory.retrieve(old.getId()).orElseThrow().isActive());
            assertEquals(1, longTermMemory.size());
        }
    }

    @Test
    void mixedMavenAndGradleRootDoesNotProduceAFalseConflict() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");
            MemoryEntry old = longTermMemory.getAll().getFirst();

            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] pom.xml\n[F] gradlew\n[F] build.gradle\n");

            assertTrue(longTermMemory.retrieve(old.getId()).orElseThrow().isActive());
            assertEquals(1, longTermMemory.size());
        }
    }

    @Test
    void newerOppositeObservationSupersedesThePreviousNegativeFact() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");
            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] gradlew\n[F] build.gradle\n");
            MemoryEntry gradleNegative = longTermMemory.getAll().stream()
                    .filter(MemoryEntry::isActive).findFirst().orElseThrow();

            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] pom.xml\n");

            assertFalse(longTermMemory.retrieve(gradleNegative.getId()).orElseThrow().isActive());
            MemoryEntry latest = longTermMemory.getAll().stream()
                    .filter(MemoryEntry::isActive).findFirst().orElseThrow();
            assertEquals("maven", latest.getMetadata().get("observed_value"));
            assertEquals(List.of(gradleNegative.getId()), latest.getEvidence().conflictsWith());
        }
    }

    @Test
    void observationConflictAuditPersistsAcrossReload() {
        Path storage = tempDir.resolve("observation-persistence");
        String oldId;
        String negativeId;
        try (LongTermMemory longTermMemory = new LongTermMemory(storage.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目构建工具使用 Maven");
            oldId = longTermMemory.getAll().getFirst().getId();
            memoryManager.addToolResult("list_dir", "{\"path\":\".\"}",
                    "目录内容:\n[F] gradlew\n[F] build.gradle\n");
            negativeId = longTermMemory.getAll().stream()
                    .filter(MemoryEntry::isActive).findFirst().orElseThrow().getId();
        }

        try (LongTermMemory reloaded = new LongTermMemory(storage.toFile())) {
            assertFalse(reloaded.retrieve(oldId).orElseThrow().isActive());
            MemoryEntry negative = reloaded.retrieve(negativeId).orElseThrow();
            assertTrue(negative.isActive());
            assertEquals(List.of(oldId), negative.getEvidence().conflictsWith());
            assertEquals(oldId, negative.getMetadata().get("invalidates_memory_ids"));
        }
    }

    @Test
    void addUserMessageShouldRecordVolatileFactSnippet() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.addUserMessage("帮我读取 pom.xml 并解释其中的依赖配置");

            String section = memoryManager.buildSessionMemorySection();
            assertTrue(section.contains("用户最新输入"));
            assertTrue(memoryManager.getSessionMemory().getVolatileFacts().stream()
                    .anyMatch(f -> f.contains("pom.xml")));
        }
    }

    @Test
    void addAssistantMessageShouldNotPolluteWorkingMemory() {
        // assistant 内容已在 conversationHistory 里，重复存到 working memory 没价值——v2 是 no-op
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.addAssistantMessage("已分析完毕，结果如下...");

            assertTrue(memoryManager.getSessionMemory().getVolatileFacts().isEmpty());
            assertTrue(memoryManager.getSessionMemory().getRecentToolResults().isEmpty());
        }
    }

    @Test
    void exposesStableCompactionSummaryCache() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            assertSame(memoryManager.getCompactionSummaryCache(), memoryManager.getCompactionSummaryCache());
        }
    }

    @Test
    void sessionPreSummaryExpiresAfterConfiguredTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-19T00:00:00Z"));
        CompactionSummaryCache summaryCache = new CompactionSummaryCache(clock, Duration.ofMinutes(5));
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.user("需求"),
                LlmClient.Message.assistant("结果")
        );

        summaryCache.recordPreSummary(messages, "旧摘要");
        assertTrue(summaryCache.findReusablePreSummary(messages).isPresent());

        clock.advance(Duration.ofMinutes(6));

        assertTrue(summaryCache.findReusablePreSummary(messages).isEmpty());
        assertTrue(summaryCache.currentPreSummary().isEmpty());
    }

    @Test
    void sessionPreSummaryShouldExtendOnlyWhenCoveredMessagesRemainPrefix() {
        CompactionSummaryCache summaryCache = new CompactionSummaryCache();
        List<LlmClient.Message> original = List.of(
                LlmClient.Message.user("需求"),
                LlmClient.Message.assistant("结果")
        );
        summaryCache.recordPreSummary(original, "旧摘要");

        List<LlmClient.Message> extended = new ArrayList<>(original);
        extended.add(LlmClient.Message.user("新增需求"));
        assertTrue(summaryCache.findExtendablePreSummary(extended).isPresent());

        List<LlmClient.Message> changedPrefix = new ArrayList<>(extended);
        changedPrefix.set(0, LlmClient.Message.user("被修改的需求"));
        assertTrue(summaryCache.findExtendablePreSummary(changedPrefix).isEmpty());
    }

    @Test
    void maintainSessionPreSummaryAfterTurnTriggersOnTokenGrowth() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of(new LlmClient.ChatResponse(
                             "assistant", "AUTO PRE SUMMARY", null, 100, 20))),
                     4096, 128000, ltm)) {
            List<LlmClient.Message> history = List.of(
                    LlmClient.Message.system("SYSTEM_PROMPT"),
                    LlmClient.Message.user(longText(10_000)),
                    LlmClient.Message.assistant(longText(10_000))
            );

            MemoryManager.SessionPreSummaryMaintenanceResult result =
                    memoryManager.maintainSessionPreSummaryAfterTurn(history, 0, 0);

            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED, result);
            CompactionSummaryCache.PreSummary preSummary = memoryManager.getCompactionSummaryCache()
                    .findReusablePreSummary(history.subList(1, history.size()))
                    .orElseThrow();
            assertEquals("AUTO PRE SUMMARY", preSummary.summary());
        }
    }

    @Test
    void maintainSessionPreSummaryAfterTurnAsyncRunsInBackground() throws Exception {
        BlockingStubGLMClient llmClient = new BlockingStubGLMClient("ASYNC PRE SUMMARY");
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(llmClient, 4096, 128000, ltm)) {
            List<LlmClient.Message> history = List.of(
                    LlmClient.Message.system("SYSTEM_PROMPT"),
                    LlmClient.Message.user(longText(10_000)),
                    LlmClient.Message.assistant(longText(10_000))
            );

            CompletableFuture<MemoryManager.SessionPreSummaryMaintenanceResult> future =
                    memoryManager.maintainSessionPreSummaryAfterTurnAsync(history, 0, 0);

            assertTrue(llmClient.started.await(2, TimeUnit.SECONDS));
            assertFalse(future.isDone(), "异步维护不应阻塞调用方等待 LLM 完成");
            llmClient.release.countDown();

            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    future.get(2, TimeUnit.SECONDS));
            assertEquals("ASYNC PRE SUMMARY",
                    memoryManager.getCompactionSummaryCache().currentPreSummary().orElseThrow().summary());
        }
    }

    @Test
    void maintainSessionPreSummaryAfterTurnTriggersOnToolCallCount() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of(new LlmClient.ChatResponse(
                             "assistant", "TOOL PRE SUMMARY", null, 30, 10))),
                     4096, 128000, ltm)) {
            List<LlmClient.Message> history = List.of(
                    LlmClient.Message.system("SYSTEM_PROMPT"),
                    LlmClient.Message.user("short question"),
                    LlmClient.Message.assistant("short answer")
            );

            MemoryManager.SessionPreSummaryMaintenanceResult result =
                    memoryManager.maintainSessionPreSummaryAfterTurn(history, 4, 0);

            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED, result);
            assertTrue(memoryManager.getCompactionSummaryCache()
                    .findReusablePreSummary(history.subList(1, history.size()))
                    .orElseThrow()
                    .summary()
                    .contains("TOOL PRE SUMMARY"));
        }
    }

    @Test
    void maintainSessionPreSummaryShouldMergeOnlyNewMessagesAfterFirstSummary() {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "FIRST SUMMARY", null, 100, 20),
                new LlmClient.ChatResponse("assistant", "SECOND SUMMARY", null, 40, 10)
        ));
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(llmClient, 4096, 128000, ltm)) {
            List<LlmClient.Message> firstHistory = new ArrayList<>(List.of(
                    LlmClient.Message.system("SYSTEM_PROMPT"),
                    LlmClient.Message.user("OLD_RAW_MARKER-" + longText(10_000)),
                    LlmClient.Message.assistant(longText(10_000))
            ));
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    memoryManager.maintainSessionPreSummaryAfterTurn(firstHistory, 0, 0));

            List<LlmClient.Message> extendedHistory = new ArrayList<>(firstHistory);
            extendedHistory.add(LlmClient.Message.user("NEW_DELTA_MARKER"));
            extendedHistory.add(LlmClient.Message.assistant("新增结果"));
            assertEquals(MemoryManager.SessionPreSummaryMaintenanceResult.MAINTAINED,
                    memoryManager.maintainSessionPreSummaryAfterTurn(extendedHistory, 4, 0));

            String incrementalPrompt = llmClient.messagesByCall.get(1).get(1).content();
            assertTrue(incrementalPrompt.contains("FIRST SUMMARY"));
            assertTrue(incrementalPrompt.contains("NEW_DELTA_MARKER"));
            assertFalse(incrementalPrompt.contains("OLD_RAW_MARKER"));
            assertEquals("SECOND SUMMARY",
                    memoryManager.getCompactionSummaryCache().currentPreSummary().orElseThrow().summary());
            MemoryManager.SessionPreSummaryMetrics metrics = memoryManager.getSessionPreSummaryMetrics();
            assertEquals("incremental", metrics.mode());
            assertEquals(2, metrics.deltaMessages());
            assertEquals(1, metrics.fullCount());
            assertEquals(1, metrics.incrementalCount());
            assertEquals(0, metrics.failureCount());
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

            assertTrue(memoryManager.getSessionMemory().getRecentToolResults().isEmpty());
            assertTrue(memoryManager.getSessionMemory().getVolatileFacts().isEmpty());
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
    void buildContextForQueryShouldNotInjectInventoryForUnrelatedRequest() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("用户的名称叫做派大星");

            String context = memoryManager.buildContextForQuery("解释 Maven 生命周期", 512);

            assertTrue(context.isBlank());
        }
    }

    @Test
    void buildContextForQueryShouldKeepRelevantMemoryWithoutInventory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            memoryManager.storeFact("项目默认使用 Java 17");

            String context = memoryManager.buildContextForQuery("项目使用哪个 Java 版本", 512);

            assertTrue(context.contains("项目默认使用 Java 17"));
            assertFalse(context.contains("长期记忆索引快照"));
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

            String section = memoryManager.buildSessionMemorySection();
            assertEquals(1, memoryManager.getSessionMemory().getRagEvidenceMemory().size());
            assertTrue(section.contains("RAG 证据记忆"));
            assertTrue(section.contains("symbolVersion=sv_test123"));
            assertTrue(section.contains("indexEpoch=idx-1"));
            assertTrue(section.contains("classpathEpoch=cp-1"));
            assertTrue(section.contains("NegativeFact（负向事实）"));
            assertTrue(section.contains("query=CodeRetriever search"));
        }
    }

    @Test
    void searchCodeToolResultShouldPreferStructuredRagEvidencePayload() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            SymbolInvalidation invalidation = new SymbolInvalidation(
                    "CodeRetriever.java#method#CodeRetriever.search",
                    "src/main/java/com/devcli/rag/CodeRetriever.java",
                    "method",
                    "CodeRetriever.search",
                    "sv_old",
                    "sv_new",
                    "idx_old",
                    "idx_new",
                    "cp-1",
                    "Do not rely on CodeRetriever.search from symbolVersion sv_old.");
            VectorStore.SearchResult searchResult = new VectorStore.SearchResult(
                    "src/main/java/com/devcli/rag/CodeRetriever.java",
                    "method",
                    "CodeRetriever.search",
                    "content",
                    0.91,
                    "sv_new",
                    "cp-1",
                    "idx_new",
                    List.of(invalidation));
            RagEvidencePayload.Payload payload = RagEvidencePayload.fromSearchResults(
                    "CodeRetriever search", List.of(searchResult), List.of());
            memoryManager.addToolResult(
                    "search_code",
                    "{\"query\":\"ignored legacy query\"}",
                    "formatter text can change",
                    List.of(new RagEvidenceSideChannel(payload)));

            String section = memoryManager.buildSessionMemorySection();
            assertEquals(1, memoryManager.getSessionMemory().getRagEvidenceMemory().size());
            assertTrue(section.contains("symbolVersion=sv_new"));
            assertTrue(section.contains("indexEpoch=idx_new"));
            assertTrue(section.contains("query=CodeRetriever search"));
            assertTrue(section.contains("NegativeFact（负向事实）"));
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
            assertEquals(MemoryEvidence.Confidence.HIGH, entry.getEvidence().confidence());
            assertEquals(MemoryEvidence.ReviewState.REVIEWED, entry.getEvidence().reviewState());
            assertEquals("记住：我默认使用 Java 17 开发", entry.getEvidence().sourceQuote());
            assertEquals("EXPLICIT_STABLE_MEMORY", entry.getEvidence().reasoning());
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
            MemoryEntry entry = longTermMemory.getAll().get(0);
            assertEquals("explicit", entry.getMetadata().get("source"));
            assertEquals(MemoryEvidence.ReviewState.REVIEWED, entry.getEvidence().reviewState());
        }
    }

    @Test
    void sensitiveDebugMemoryShouldOfferASecretFreeSaveChoice() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            String rawToken = "tok-sensitive-123";

            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy(
                    "记住刚刚的调试过程：先运行 mvn test，再检查日志；token=" + rawToken, true);

            assertFalse(result.stored());
            assertEquals(LongTermMemoryPolicy.Action.CONFIRM, result.decision().action());
            assertTrue(result.message().contains("保存脱敏版"), result.message());
            assertTrue(result.message().contains("mvn test"), result.message());
            assertFalse(result.message().contains(rawToken), result.message());
            assertTrue(result.confirmationId().startsWith("memory-confirm-"));
            assertEquals(0, longTermMemory.size());
        }
    }

    @Test
    void sensitiveConfirmationReplayIsIdempotent() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            MemoryManager.StoreResult pending = memoryManager.storeFactWithPolicy(
                    "记住调试过程：先运行 mvn test，再检查日志；token=tok-once", true);

            MemoryManager.StoreResult confirmed = memoryManager.confirmSensitiveMemory(
                    pending.confirmationId(), "");
            MemoryManager.StoreResult replay = memoryManager.confirmSensitiveMemory(
                    pending.confirmationId(), "");

            assertTrue(confirmed.stored(), confirmed.message());
            assertTrue(replay.stored(), replay.message());
            assertEquals(confirmed.id(), replay.id());
            assertEquals(1, longTermMemory.size());
        }
    }

    @Test
    void clearShortTermKeepsDurableSensitiveConfirmation() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            MemoryManager.StoreResult pending = memoryManager.storeFactWithPolicy(
                    "记住调试过程：先运行 mvn test，再检查日志；token=tok-clear", true);

            memoryManager.clearShortTerm();
            MemoryManager.StoreResult result = memoryManager.confirmSensitiveMemory(
                    pending.confirmationId(), "");

            assertTrue(result.stored(), result.message());
            assertEquals(1, longTermMemory.size());
        }
    }

    @Test
    void pendingSensitiveConfirmationSurvivesProcessReconstruction() {
        String confirmationId;
        try (LongTermMemory firstMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager firstManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, firstMemory)) {
            MemoryManager.StoreResult pending = firstManager.storeFactWithPolicy(
                    "记住调试过程：先运行 mvn test，再检查日志；token=tok-restart", true);
            confirmationId = pending.confirmationId();
        }

        try (LongTermMemory restoredMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager restoredManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, restoredMemory)) {
            MemoryManager.StoreResult confirmed = restoredManager.confirmSensitiveMemory(confirmationId, "");

            assertTrue(confirmed.stored(), confirmed.message());
            assertEquals(1, restoredMemory.size());
        }
    }

    @Test
    void finalStoreBoundaryMustRedactPlaintextSecrets() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.storeFact("调试配置 token=tok-should-never-persist");

            assertEquals(1, longTermMemory.size());
            MemoryEntry entry = longTermMemory.getAll().getFirst();
            assertFalse(entry.getContent().contains("tok-should-never-persist"));
            assertTrue(entry.getMetadata().containsKey("redacted_types"));
        }
    }

    @Test
    void confirmedSensitiveMemoryStoresOnlyReusableRedactedKnowledge() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            MemoryManager.StoreResult result = memoryManager.storeRedactedFact(
                    "记住调试过程：先运行 mvn test，再检查日志，token=tok-confirmed-secret");

            assertTrue(result.stored(), result.message());
            MemoryEntry stored = longTermMemory.getAll().getFirst();
            assertTrue(stored.getContent().contains("先运行 mvn test，再检查日志"));
            assertFalse(stored.getContent().contains("tok-confirmed-secret"));
            assertEquals("SENSITIVE_REDACTED_CONFIRMED", stored.getMetadata().get("reason_code"));
            assertEquals("credential", stored.getMetadata().get("redacted_types"));
        }
    }

    @Test
    void accountOnlyMemoryRequiresSecretsVaultInsteadOfNormalMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            MemoryManager.StoreResult result = memoryManager.storeRedactedFact("记住账号：admin@example.com");

            assertFalse(result.stored());
            assertTrue(result.message().contains("secrets vault"), result.message());
            assertTrue(longTermMemory.getAll().isEmpty());
        }
    }

    @Test
    void feedbackPolicyMemoryShouldBeStoredAsFeedbackType() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy("不要再自动运行项目", true);

            assertTrue(result.stored(), result.message());
            MemoryEntry entry = longTermMemory.getAll().get(0);
            assertEquals(MemoryEntry.MemoryType.FEEDBACK, entry.getType());
            assertEquals("feedback", entry.getMetadata().get("memory_type"));
            assertEquals(1, longTermMemory.getByType(MemoryEntry.MemoryType.FEEDBACK).size());
        }
    }

    @Test
    void addUserMessageShouldEnableMemoryIgnoredForAllPromptMemoryViews() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {

            memoryManager.storeFact("用户默认使用 Java 17");
            memoryManager.addToolResult("read_file", "{\"path\":\"Secret.java\"}", "secret evidence");

            memoryManager.addUserMessage("这次别管记忆");

            assertTrue(memoryManager.isMemoryIgnored());
            assertTrue(memoryManager.buildContextForQuery("Java 17", 512).isBlank());
            assertTrue(memoryManager.buildSessionMemorySection().isBlank());
            assertTrue(memoryManager.buildSessionMemorySectionForAgent("worker").isBlank());
        }
    }

    @Test
    void buildContextForQueryShouldSuppressFactsAlreadyInWorkingMemory() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new StubGLMClient(List.of()), 32768, 128000, longTermMemory)) {
            String fact = "请记住：我默认使用 Java 17 开发";
            memoryManager.storeFactWithPolicy(fact);
            memoryManager.addVolatileFact("用户最新输入: " + fact);

            String context = memoryManager.buildContextForQuery("Java 17", 512);

            assertFalse(context.contains(fact), context);
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

            String section = memoryManager.buildSessionMemorySection();
            assertTrue(section.contains("plan_task"));
            assertTrue(section.contains("task_3"));
            assertTrue(section.contains("last_error"));
        }
    }

    @Test
    void buildSessionMemorySectionReturnsEmptyWhenAllSubStoresEmpty() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            assertTrue(memoryManager.buildSessionMemorySection().isBlank());
        }
    }

    @Test
    void buildPostCompactRestoreSectionUsesStructuredSections() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.setTaskState("plan_task", "task_2 running");
            memoryManager.addToolResult("read_file", "{\"path\":\"src/Main.java\"}", "class Main {}");
            memoryManager.addToolResult("write_file", "{\"path\":\"src/Main.java\"}", "写入文件成功: src/Main.java");
            memoryManager.addToolResult("search_code", "{\"query\":\"CodeRetriever search\"}", """
                    检索结果:
                    1. [method:CodeRetriever.search(String,int,String,int)] (相似度: 0.910) src/main/java/com/devcli/rag/CodeRetriever.java
                       evidence: symbolVersion=sv_test123, indexEpoch=idx-1, classpathEpoch=cp-1
                    """);

            String section = memoryManager.buildPostCompactRestoreSection();

            assertTrue(section.contains("### 最近读写文件"), section);
            assertTrue(section.contains("src/Main.java"), section);
            assertTrue(section.contains("### 未完成子任务状态"), section);
            assertTrue(section.contains("plan_task"), section);
            assertTrue(section.contains("### 关键工具结果引用"), section);
            assertTrue(section.contains("write_file"), section);
            assertTrue(section.contains("### RAG 证据 epoch"), section);
            assertTrue(section.contains("indexEpoch=idx-1"), section);
        }
    }

    @Test
    void buildPostCompactRestoreSectionShouldRenderOpenSubTasksOnly() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.setTaskLedgerPlan("plan-42", "实现阶段 3", Map.of(
                    "step_done", "已完成步骤",
                    "step_running", "运行中步骤",
                    "step_pending", "待执行步骤"));
            memoryManager.completeTaskStep("step_done");
            memoryManager.startTaskStep("step_running");

            String section = memoryManager.buildPostCompactRestoreSection();

            assertTrue(section.contains("### 未完成子任务状态"), section);
            assertTrue(section.contains("plan_id: plan-42"), section);
            assertTrue(section.contains("running: step_running"), section);
            assertTrue(section.contains("pending: step_pending"), section);
            assertTrue(section.contains("completed_count: 1"), section);
            assertFalse(section.contains("已完成步骤"), section);
        }
    }

    @Test
    void buildPostCompactRestoreSectionForAgentShouldApplyRoleScopedCropping() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.setTaskState("plan_task", "step_1");
            memoryManager.addToolResult("read_file", "{\"path\":\"Secret.java\"}", "secret tool evidence");

            String planner = memoryManager.buildPostCompactRestoreSectionForAgent("planner");
            assertTrue(planner.contains("plan_task"), planner);
            assertFalse(planner.contains("Secret.java"), planner);
            assertFalse(planner.contains("关键工具结果引用"), planner);

            String reviewer = memoryManager.buildPostCompactRestoreSectionForAgent("reviewer");
            assertTrue(reviewer.contains("plan_task"), reviewer);
            assertTrue(reviewer.contains("Secret.java"), reviewer);
            assertTrue(reviewer.contains("关键工具结果引用"), reviewer);
        }
    }

    @Test
    void buildPostCompactRestoreSectionShouldDeduplicateMicrocompactToolReferences() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            String boundary = """
                    <microcompact_boundary>
                    type=tool_result
                    toolCallId=call-1
                    originalChars=42000
                    storedPath=C:/tmp/tool-result/call-1.txt
                    </microcompact_boundary>
                    [完整工具结果已落盘；可用 read_file 读取 storedPath。]
                    """;
            memoryManager.addToolResult("read_file", "{\"path\":\"src/Main.java\"}", boundary);
            memoryManager.addToolResult("read_file", "{\"path\":\"src/Main.java\"}", boundary);

            String section = memoryManager.buildPostCompactRestoreSection();

            assertEquals(section.indexOf("storedPath=C:/tmp/tool-result/call-1.txt"),
                    section.lastIndexOf("storedPath=C:/tmp/tool-result/call-1.txt"), section);
            assertEquals(section.indexOf("toolCallId=call-1"),
                    section.lastIndexOf("toolCallId=call-1"), section);
        }
    }

    @Test
    void postCompactRestoreContextShouldDeduplicateAndRespectBudget() {
        String repeated = "- same line";
        String section = PostCompactRestoreContext.render(90,
                new PostCompactRestoreContext.Section("第一段", repeated + "\n- unique one"),
                new PostCompactRestoreContext.Section("第二段", repeated + "\n- unique two with long long long long long text"));

        assertEquals(section.indexOf(repeated), section.lastIndexOf(repeated), section);
        assertTrue(section.length() <= 120, section);
        assertTrue(section.contains("恢复上下文已截断") || section.contains("unique two"), section);
    }

    @Test
    void buildSessionMemorySectionForAgentShouldIsolateRoleSpecificViews() {
        try (LongTermMemory ltm = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(new StubGLMClient(List.of()), 4096, 128000, ltm)) {
            memoryManager.setTaskState("plan_task", "step_1");
            memoryManager.addVolatileFact("用户最新输入: 修复 agent 记忆隔离");
            memoryManager.addToolResult("read_file", "{\"path\":\"Secret.java\"}", "secret tool evidence");

            String planner = memoryManager.buildSessionMemorySectionForAgent("planner");
            assertTrue(planner.contains("plan_task"));
            assertTrue(planner.contains("用户最新输入"));
            assertFalse(planner.contains("secret tool evidence"), planner);
            assertFalse(planner.contains("最近工具调用证据"), planner);

            String worker = memoryManager.buildSessionMemorySectionForAgent("worker");
            assertTrue(worker.contains("plan_task"));
            assertTrue(worker.contains("用户最新输入"));
            assertTrue(worker.contains("secret tool evidence"));

            String reviewer = memoryManager.buildSessionMemorySectionForAgent("reviewer");
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
                            memoryManager.buildSessionMemorySection();
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

            assertEquals(SessionMemory.DEFAULT_MAX_TOOL_RESULTS,
                    memoryManager.getSessionMemory().getRecentToolResults().size());
            assertEquals(SessionMemory.DEFAULT_MAX_VOLATILE_FACTS,
                    memoryManager.getSessionMemory().getVolatileFacts().size());
            assertFalse(memoryManager.buildSessionMemorySection().isBlank());
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

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
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }

    private static final class BlockingStubGLMClient extends GLMClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final String summary;

        private BlockingStubGLMClient(String summary) {
            super("test-key");
            this.summary = summary;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("等待释放超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待释放被中断", e);
            }
            return new ChatResponse("assistant", summary, null, 10, 5);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static String longText(int chars) {
        return "x".repeat(chars);
    }
}
