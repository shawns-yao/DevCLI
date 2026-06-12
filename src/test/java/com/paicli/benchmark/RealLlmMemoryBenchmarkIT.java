package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.config.PaiCliConfig;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryEntry;
import com.paicli.memory.MemoryManager;
import com.paicli.memory.MemoryRetriever;
import com.paicli.memory.MemoryVectorStore;
import com.paicli.rag.EmbeddingClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RealLlmMemoryBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("real LLM memory benchmark: write policy, semantic recall, prompt injection")
    void benchmarkMemoryWithRealEnvModel() throws Exception {
        LlmClient llm = resolveRealLlmClientOrSkip();
        EmbeddingClient embeddingClient = resolveEmbeddingClientOrSkip();

        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(llm, 32768, llm.maxContextWindow(), longTermMemory);
             MemoryVectorStore vectorStore = new MemoryVectorStore(tempDir)) {

            memoryManager.getLongTermMemory().setVectorIndex(
                    entry -> {
                        try {
                            vectorStore.upsert(entry.getId(), entry.getContent(), embeddingClient.embed(entry.getContent()));
                        } catch (Exception ignored) {
                            // The benchmark still measures keyword fallback if embedding fails mid-run.
                        }
                    },
                    vectorStore::delete,
                    vectorStore::clear);
            memoryManager.getRetriever().setSemanticSearch((query, topK) -> {
                try {
                    return vectorStore.search(embeddingClient.embed(query), topK, MemoryVectorStore.DEFAULT_SIMILARITY_THRESHOLD)
                            .stream()
                            .map(hit -> new MemoryRetriever.SemanticHit(hit.factId(), hit.similarity()))
                            .toList();
                } catch (Exception e) {
                    return List.of();
                }
            });

            List<Candidate> candidates = candidates();
            List<PolicyResult> policyResults = new ArrayList<>();
            for (Candidate candidate : candidates) {
                MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy(candidate.fact(), candidate.explicit());
                policyResults.add(new PolicyResult(candidate, result.decision().action().name(), result.stored()));
            }

            List<RecallResult> recallResults = new ArrayList<>();
            for (QueryCase query : queries()) {
                List<MemoryEntry> hits = memoryManager.retrieveRelevant(query.query(), 5);
                Set<String> hitIds = new LinkedHashSet<>();
                for (MemoryEntry hit : hits) {
                    hitIds.add(hit.getId());
                }
                boolean matched = hits.stream().anyMatch(hit -> containsAll(hit.getContent(), query.expectedTerms()));
                recallResults.add(new RecallResult(query, matched, hitIds));
            }

            List<InjectionResult> injectionResults = new ArrayList<>();
            for (QueryCase query : queries()) {
                String context = memoryManager.buildContextForQuery(query.query(), 2_000);
                boolean injected = containsAll(context, query.expectedTerms());
                injectionResults.add(new InjectionResult(query, injected));
            }

            double writeAccuracy = ratio(policyResults.stream()
                    .filter(result -> result.actualAction().equals(result.candidate().expectedAction()))
                    .count(), policyResults.size());
            double lowValueBlockRate = ratio(policyResults.stream()
                    .filter(result -> !result.candidate().expectedAction().equals("SAVE"))
                    .filter(result -> result.actualAction().equals(result.candidate().expectedAction()))
                    .count(), policyResults.stream()
                    .filter(result -> !result.candidate().expectedAction().equals("SAVE"))
                    .count());
            double recallAt5 = ratio(recallResults.stream().filter(RecallResult::matched).count(), recallResults.size());
            double injectionHitRate = ratio(injectionResults.stream().filter(InjectionResult::injected).count(), injectionResults.size());

            Path report = writeReport(llm, embeddingClient, policyResults, recallResults, injectionResults,
                    writeAccuracy, lowValueBlockRate, recallAt5, injectionHitRate);
            System.out.printf(Locale.ROOT,
                    "Real LLM memory benchmark: write_accuracy=%.1f%% low_value_block=%.1f%% recall@5=%.1f%% injection=%.1f%% facts=%d vectors=%d report=%s%n",
                    writeAccuracy * 100, lowValueBlockRate * 100, recallAt5 * 100, injectionHitRate * 100,
                    longTermMemory.size(), vectorStore.count(), report);

            assertTrue(writeAccuracy >= 0.70, "write accuracy below threshold; report=" + report);
            assertTrue(lowValueBlockRate >= 0.70, "low-value block rate below threshold; report=" + report);
            assertTrue(recallAt5 >= 0.70, "recall@5 below threshold; report=" + report);
            assertTrue(injectionHitRate >= 0.70, "injection hit rate below threshold; report=" + report);
        }
    }

    private static LlmClient resolveRealLlmClientOrSkip() {
        LlmClient client = LlmClientFactory.create(System.getProperty("paicli.it.memory.provider", "kimi"), PaiCliConfig.load());
        Assumptions.assumeTrue(client != null, "no real LLM provider configured");
        try {
            LlmClient.ChatResponse response = client.chat(List.of(
                    LlmClient.Message.system("只回复 OK。"),
                    LlmClient.Message.user("ping")), null);
            Assumptions.assumeTrue(response != null && response.content() != null && !response.content().isBlank(),
                    "real LLM ping returned empty");
        } catch (Exception e) {
            Assumptions.abort("real LLM unavailable: " + e.getMessage());
        }
        return client;
    }

    private static EmbeddingClient resolveEmbeddingClientOrSkip() {
        try {
            EmbeddingClient client = new EmbeddingClient();
            float[] vector = client.embed("memory benchmark probe");
            Assumptions.assumeTrue(vector.length > 0, "embedding probe returned empty vector");
            return client;
        } catch (Exception e) {
            Assumptions.abort("embedding unavailable: " + e.getMessage());
            return null;
        }
    }

    private static List<Candidate> candidates() {
        return List.of(
                new Candidate("请记住：我默认使用简体中文短句回答", true, "SAVE"),
                new Candidate("记住：PaiCLI 项目默认测试命令是 mvn test -Pquick", true, "SAVE"),
                new Candidate("项目默认 Java 版本是 17", true, "SAVE"),
                new Candidate("用户偏好：代码解释先给结论再给依据", true, "SAVE"),
                new Candidate("请记住：PaiCLI 的代码 RAG 评测默认关注 recall@5 和 path coverage", true, "SAVE"),
                new Candidate("请记住：工具参数必须先经过 JSON Schema 校验再执行", true, "SAVE"),
                new Candidate("记住：多智能体 reviewer 评分关注功能正确性、集成完整性和代码质量", true, "SAVE"),
                new Candidate("记住：Runtime API 默认只绑定 127.0.0.1", true, "SAVE"),
                new Candidate("记住：MCP 启动超时后保持 STARTING 并后台继续初始化", true, "SAVE"),
                new Candidate("记住：默认不要使用 git add .，必须按具体文件 stage", true, "SAVE"),
                new Candidate("记住：简历润色默认只给最优一版，不列多个相似版本", true, "SAVE"),
                new Candidate("记住：长期记忆目录默认是 ~/.paicli/memory", true, "SAVE"),
                new Candidate("今天地铁很挤，天气也不错", false, "SKIP"),
                new Candidate("这次先临时把日志文件叫 temp-debug.log", false, "SKIP"),
                new Candidate("我朋友的孩子今天高考", false, "SKIP"),
                new Candidate("刚刚我喝了一杯冰美式，感觉还行", false, "SKIP"),
                new Candidate("这轮调试先把变量名随便叫 abc123", false, "SKIP"),
                new Candidate("今天先不用管 README 里的一个错别字", false, "SKIP"),
                new Candidate("刚才终端输出滚太快，看着有点乱", false, "SKIP"),
                new Candidate("临时把截图放到桌面，明天再删", false, "SKIP"),
                new Candidate("记住我的身份证号是 110101199003071234", true, "CONFIRM"),
                new Candidate("记住我的收货地址是 北京市海淀区测试路 1 号", true, "CONFIRM"),
                new Candidate("记住我的手机号是 13800138000", true, "CONFIRM"),
                new Candidate("记住我的银行卡号是 6222020202020202020", true, "CONFIRM"),
                new Candidate("记住 api_key: sk-real-memory-benchmark-placeholder", true, "CONFIRM")
        );
    }

    private static List<QueryCase> queries() {
        return List.of(
                new QueryCase("用户默认用什么语言和表达风格？", List.of("简体中文", "短句")),
                new QueryCase("这个项目默认跑什么测试命令？", List.of("mvn test -Pquick")),
                new QueryCase("项目使用哪个 Java 版本？", List.of("17")),
                new QueryCase("解释代码时应该怎么组织答案？", List.of("先给结论", "依据")),
                new QueryCase("代码检索评估需要看哪些核心指标？", List.of("recall@5", "path coverage")),
                new QueryCase("工具调用真正执行前要先做什么参数检查？", List.of("JSON Schema")),
                new QueryCase("多智能体审查主要看哪几个质量维度？", List.of("功能正确性", "集成完整性", "代码质量")),
                new QueryCase("本地 Runtime API 默认监听哪个地址？", List.of("127.0.0.1")),
                new QueryCase("MCP 服务启动超过等待时间后应该保持什么状态？", List.of("STARTING")),
                new QueryCase("提交代码时默认不能使用什么 stage 方式？", List.of("git add .")),
                new QueryCase("帮我润色简历时默认给几版？", List.of("最优一版")),
                new QueryCase("长期记忆默认落在哪个目录？", List.of(".paicli", "memory"))
        );
    }

    private static boolean containsAll(String text, List<String> terms) {
        if (text == null) {
            return false;
        }
        return terms.stream().allMatch(text::contains);
    }

    private Path writeReport(LlmClient llm,
                             EmbeddingClient embeddingClient,
                             List<PolicyResult> policyResults,
                             List<RecallResult> recallResults,
                             List<InjectionResult> injectionResults,
                             double writeAccuracy,
                             double lowValueBlockRate,
                             double recallAt5,
                             double injectionHitRate) throws Exception {
        Path dir = Path.of("target", "benchmark-reports");
        Files.createDirectories(dir);
        Path report = dir.resolve("real-llm-memory-benchmark.json");

        ObjectNode root = JSON.createObjectNode();
        root.put("created_at", Instant.now().toString());
        root.put("llm_provider", llm.getProviderName());
        root.put("llm_model", llm.getModelName());
        root.put("embedding_provider", embeddingClient.getProvider());
        root.put("embedding_model", embeddingClient.getModel());
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("write_accuracy", round4(writeAccuracy));
        metrics.put("low_value_block_rate", round4(lowValueBlockRate));
        metrics.put("recall_at_5", round4(recallAt5));
        metrics.put("injection_hit_rate", round4(injectionHitRate));

        ArrayNode policies = root.putArray("policy_results");
        for (PolicyResult result : policyResults) {
            ObjectNode node = policies.addObject();
            node.put("fact", result.candidate().fact());
            node.put("expected", result.candidate().expectedAction());
            node.put("actual", result.actualAction());
            node.put("stored", result.stored());
        }

        ArrayNode recalls = root.putArray("recall_results");
        for (RecallResult result : recallResults) {
            ObjectNode node = recalls.addObject();
            node.put("query", result.query().query());
            node.putPOJO("expected_terms", result.query().expectedTerms());
            node.put("matched", result.matched());
            node.putPOJO("hit_ids", result.hitIds().stream().toList());
        }

        ArrayNode injections = root.putArray("injection_results");
        for (InjectionResult result : injectionResults) {
            ObjectNode node = injections.addObject();
            node.put("query", result.query().query());
            node.put("injected", result.injected());
        }

        Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return report;
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 1.0;
        }
        return (double) numerator / denominator;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record Candidate(String fact, boolean explicit, String expectedAction) {}

    private record QueryCase(String query, List<String> expectedTerms) {}

    private record PolicyResult(Candidate candidate, String actualAction, boolean stored) {}

    private record RecallResult(QueryCase query, boolean matched, Set<String> hitIds) {}

    private record InjectionResult(QueryCase query, boolean injected) {}
}
