package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.LlmClient;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.ConversationMemory;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryEntry;
import com.paicli.memory.MemoryRetriever;
import com.paicli.memory.TokenBudget;
import com.paicli.rag.CodeChunk;
import com.paicli.rag.VectorStore;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaiCliResumeBenchmarkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int TURN_COUNT = 24;
    private static final int RETAIN_RECENT_ROUNDS = 3;
    private static final int PARALLEL_TASKS = 4;
    private static final int TASK_SLEEP_MS = 300;
    private static final int RAG_CHUNKS = 1_000;
    private static final int RAG_DIMENSIONS = 128;

    @TempDir
    Path tempDir;

    @Test
    void measuresResumeMetricsForLocalBenchmark() throws Exception {
        CompressionMetrics compression = measureCompression();
        ParallelMetrics parallel = measureParallelTools();
        MemoryMetrics memory = measureLongTermMemoryReuse();
        RagMetrics rag = measureRagVectorSearch();

        assertTrue(compression.compacted());
        assertTrue(compression.tokenReductionPercent() > 70.0);
        assertTrue(compression.estimatedTurnsAfter() > compression.estimatedTurnsBefore());
        assertTrue(parallel.timeReductionPercent() > 50.0);
        assertEquals(memory.queryCount(), memory.hitCount());
        assertTrue(rag.searchMillis() < 100, "千级代码块向量检索应低于 100ms，实际 "
                + rag.searchMillis() + "ms");

        Path output = Path.of("target", "benchmark-results", "paicli-resume-benchmark.json");
        Files.createDirectories(output.getParent());
        MAPPER.writeValue(output.toFile(), toJson(compression, parallel, memory, rag));

        System.out.printf(
                "PaiCLI local benchmark: token %.1f%% (%d -> %d), turns %d -> %d, "
                        + "parallel %.1f%% (%dms -> %dms), memory %.1f%% (%d/%d), "
                        + "rag %d chunks in %dms%n",
                compression.tokenReductionPercent(),
                compression.tokensBefore(),
                compression.tokensAfter(),
                compression.estimatedTurnsBefore(),
                compression.estimatedTurnsAfter(),
                parallel.timeReductionPercent(),
                parallel.serialMillis(),
                parallel.parallelMillis(),
                memory.hitRatePercent(),
                memory.hitCount(),
                memory.queryCount(),
                rag.chunkCount(),
                rag.searchMillis());
    }

    private CompressionMetrics measureCompression() {
        List<LlmClient.Message> history = buildLongHistory();
        int tokensBefore = TokenBudget.estimateMessagesTokens(history);
        int averageTurnTokens = estimateAverageTurnTokens(history);
        int budget = tokensBefore;

        StubCompactor compactor = new StubCompactor("""
                用户持续分析 PaiCLI 项目，重点关注 ReAct Agent 主循环、工具注册与调用、
                上下文压缩、长期记忆、代码索引和 Planner 并行执行。已完成配置校验、
                embedding 连通性验证、Windows 控制台编码修复，并准备基于本地
                benchmark 量化简历指标。
                """);

        boolean compacted = compactor.compactIfNeeded(history, 1);
        int tokensAfter = TokenBudget.estimateMessagesTokens(history);
        int estimatedTurnsBefore = TURN_COUNT;
        int compressedBaseTokens = estimateBaseWithoutRetainedTail(history);
        int estimatedTurnsAfter = TURN_COUNT
                + Math.max(0, (budget - tokensAfter) / averageTurnTokens);

        return new CompressionMetrics(
                compacted,
                tokensBefore,
                tokensAfter,
                percentDecrease(tokensBefore, tokensAfter),
                estimatedTurnsBefore,
                estimatedTurnsAfter);
    }

    private ParallelMetrics measureParallelTools() {
        ToolRegistry registry = new SleepToolRegistry();
        List<ToolRegistry.ToolInvocation> invocations = new ArrayList<>();
        for (int i = 0; i < PARALLEL_TASKS; i++) {
            invocations.add(new ToolRegistry.ToolInvocation("call-" + i, "sleep_" + i, "{}"));
        }

        long serialStart = System.nanoTime();
        for (ToolRegistry.ToolInvocation invocation : invocations) {
            registry.executeToolOutput(invocation.name(), invocation.argumentsJson());
        }
        long serialMillis = elapsedMillis(serialStart);

        long parallelStart = System.nanoTime();
        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(invocations);
        long parallelMillis = elapsedMillis(parallelStart);

        assertEquals(PARALLEL_TASKS, results.size());
        assertTrue(results.stream().noneMatch(ToolRegistry.ToolExecutionResult::timedOut));

        return new ParallelMetrics(serialMillis, parallelMillis, percentDecrease(serialMillis, parallelMillis));
    }

    private MemoryMetrics measureLongTermMemoryReuse() {
        LongTermMemory firstSession = new LongTermMemory(tempDir.toFile());
        List<ExpectedQuery> expectedQueries = List.of(
                new ExpectedQuery("用户偏好", "中文交流", "用户偏好使用中文交流"),
                new ExpectedQuery("PaiCLI 构建工具", "Maven", "PaiCLI 项目使用 Maven 构建"),
                new ExpectedQuery("默认 embedding model", "Qwen3-Embedding-4B",
                        "默认 embedding model 是 Qwen/Qwen3-Embedding-4B"),
                new ExpectedQuery("代码索引位置", "codebase.db",
                        "代码索引默认保存在 ~/.paicli/rag/codebase.db"),
                new ExpectedQuery("日志路径", "paicli.log",
                        "调试日志默认写入 ~/.paicli/logs/paicli.log"),
                new ExpectedQuery("MCP 配置文件", "mcp.json",
                        "MCP 配置文件默认位于 ~/.paicli/mcp.json"),
                new ExpectedQuery("Plan Execute 并行", "DAG",
                        "Plan-and-Execute 通过 DAG 批次并行执行无依赖子任务"),
                new ExpectedQuery("上下文压缩阈值", "90%",
                        "上下文压缩在 90% 上下文占用时触发"));

        int i = 0;
        for (ExpectedQuery query : expectedQueries) {
            firstSession.store(new MemoryEntry(
                    "fact-" + i++,
                    query.fact(),
                    MemoryEntry.MemoryType.FACT,
                    Map.of("source", "resume-benchmark"),
                    MemoryEntry.estimateTokens(query.fact())));
        }

        LongTermMemory secondSession = new LongTermMemory(tempDir.toFile());
        MemoryRetriever retriever = new MemoryRetriever(new ConversationMemory(4096), secondSession);

        int hits = 0;
        for (ExpectedQuery expected : expectedQueries) {
            boolean hit = retriever.retrieveLongTerm(expected.query(), 3).stream()
                    .anyMatch(entry -> entry.getContent().contains(expected.expectedFragment()));
            if (hit) {
                hits++;
            }
        }

        return new MemoryMetrics(expectedQueries.size(), hits, hits * 100.0 / expectedQueries.size());
    }

    private RagMetrics measureRagVectorSearch() throws Exception {
        String previousRagDir = System.getProperty("paicli.rag.dir");
        Path ragDir = tempDir.resolve("rag-store");
        System.setProperty("paicli.rag.dir", ragDir.toString());
        try (VectorStore vectorStore = new VectorStore(tempDir.resolve("project").toString())) {
            vectorStore.clearProject();
            List<VectorStore.CodeChunkEntry> entries = new ArrayList<>();
            for (int i = 0; i < RAG_CHUNKS; i++) {
                CodeChunk chunk = CodeChunk.methodChunk(
                        "src/main/java/demo/File" + i + ".java",
                        "method" + i,
                        "void method" + i + "() { analyzeAgentToolCalls(); }",
                        i + 1,
                        i + 10);
                entries.add(new VectorStore.CodeChunkEntry(chunk, embeddingFor(i)));
            }
            vectorStore.insertChunks(entries);

            float[] queryEmbedding = embeddingFor(42);
            long start = System.nanoTime();
            List<VectorStore.SearchResult> results = vectorStore.search(queryEmbedding, 5);
            long searchMillis = elapsedMillis(start);

            assertEquals(5, results.size());
            return new RagMetrics(RAG_CHUNKS, RAG_DIMENSIONS, searchMillis);
        } finally {
            if (previousRagDir == null) {
                System.clearProperty("paicli.rag.dir");
            } else {
                System.setProperty("paicli.rag.dir", previousRagDir);
            }
        }
    }

    private static List<LlmClient.Message> buildLongHistory() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("""
                你是 PaiCLI 的 ReAct Agent，需要维护上下文、调用工具、记录长期记忆，
                并在 token 接近预算时压缩历史对话。
                """));

        for (int i = 1; i <= TURN_COUNT; i++) {
            history.add(LlmClient.Message.user("第 " + i + " 轮需求："
                    + repeatedBusinessText(i, 220)));
            history.add(LlmClient.Message.assistant("第 " + i + " 轮执行结果："
                    + repeatedBusinessText(i, 220)));
        }
        return history;
    }

    private static String repeatedBusinessText(int turn, int repeats) {
        String unit = "分析 PaiCLI Agent 的工具调用、上下文压缩、长期记忆、"
                + "代码索引和 Planner DAG 并行执行；保留任务目标、文件路径、"
                + "验证结果和下一步风险。turn=" + turn + " ";
        return unit.repeat(repeats);
    }

    private static int estimateAverageTurnTokens(List<LlmClient.Message> history) {
        int userTurns = 0;
        int tokens = 0;
        for (LlmClient.Message message : history) {
            if ("user".equals(message.role())) {
                userTurns++;
            }
            if (!"system".equals(message.role())) {
                tokens += TokenBudget.estimateMessagesTokens(List.of(message));
            }
        }
        return Math.max(1, tokens / Math.max(1, userTurns));
    }

    private static int estimateBaseWithoutRetainedTail(List<LlmClient.Message> compactedHistory) {
        int split = Math.min(compactedHistory.size(), 3);
        return TokenBudget.estimateMessagesTokens(compactedHistory.subList(0, split));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private static double percentDecrease(long before, long after) {
        return before == 0 ? 0.0 : (before - after) * 100.0 / before;
    }

    private static float[] embeddingFor(int seed) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float[] embedding = new float[RAG_DIMENSIONS];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = (float) (((seed * 31 + i * 17) % 101) / 100.0);
        }
        if (seed == 42) {
            embedding[0] = 1.0f;
        } else {
            embedding[0] = random.nextFloat();
        }
        return embedding;
    }

    private static ObjectNode toJson(
            CompressionMetrics compression,
            ParallelMetrics parallel,
            MemoryMetrics memory,
            RagMetrics rag) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("scope", "local micro-benchmark; no production traffic; no real LLM calls");

        ObjectNode compressionNode = root.putObject("context_compression");
        compressionNode.put("token_before", compression.tokensBefore());
        compressionNode.put("token_after", compression.tokensAfter());
        compressionNode.put("token_reduction_percent", round1(compression.tokenReductionPercent()));
        compressionNode.put("estimated_turns_before", compression.estimatedTurnsBefore());
        compressionNode.put("estimated_turns_after", compression.estimatedTurnsAfter());

        ObjectNode parallelNode = root.putObject("parallel_execution");
        parallelNode.put("task_count", PARALLEL_TASKS);
        parallelNode.put("task_sleep_ms", TASK_SLEEP_MS);
        parallelNode.put("serial_ms", parallel.serialMillis());
        parallelNode.put("parallel_ms", parallel.parallelMillis());
        parallelNode.put("time_reduction_percent", round1(parallel.timeReductionPercent()));

        ObjectNode memoryNode = root.putObject("long_term_memory");
        memoryNode.put("query_count", memory.queryCount());
        memoryNode.put("hit_count", memory.hitCount());
        memoryNode.put("hit_rate_percent", round1(memory.hitRatePercent()));

        ObjectNode ragNode = root.putObject("rag_vector_search");
        ragNode.put("chunk_count", rag.chunkCount());
        ragNode.put("embedding_dimensions", rag.embeddingDimensions());
        ragNode.put("search_ms", rag.searchMillis());
        return root;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class StubCompactor extends ConversationHistoryCompactor {
        private final String summary;

        private StubCompactor(String summary) {
            super(null, RETAIN_RECENT_ROUNDS);
            this.summary = summary;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) throws IOException {
            return summary;
        }
    }

    private static final class SleepToolRegistry extends ToolRegistry {
        @Override
        protected ToolOutput doExecuteTool(String name, String argumentsJson) {
            try {
                Thread.sleep(TASK_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolOutput.text("interrupted");
            }
            return ToolOutput.text("ok-" + name);
        }
    }

    private record CompressionMetrics(
            boolean compacted,
            int tokensBefore,
            int tokensAfter,
            double tokenReductionPercent,
            int estimatedTurnsBefore,
            int estimatedTurnsAfter) {
    }

    private record ParallelMetrics(long serialMillis, long parallelMillis, double timeReductionPercent) {
    }

    private record MemoryMetrics(int queryCount, int hitCount, double hitRatePercent) {
    }

    private record RagMetrics(int chunkCount, int embeddingDimensions, long searchMillis) {
    }

    private record ExpectedQuery(String query, String expectedFragment, String fact) {
    }
}
