package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryEntry;
import com.devcli.memory.MemoryManager;
import com.devcli.memory.MemoryRetriever;
import com.devcli.memory.MemoryVectorStore;
import com.devcli.rag.EmbeddingClient;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RealLlmMemoryBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("real LLM memory benchmark: write policy, semantic recall, prompt injection")
    void benchmarkMemoryWithRealEnvModel() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.memory"),
                "set -Ddevcli.benchmark.memory=true to run real memory benchmark");
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
            Map<QueryCase, RecallResult> recallByQuery = recallResults.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            RecallResult::query,
                            result -> result,
                            (left, right) -> left,
                            java.util.LinkedHashMap::new));
            for (QueryCase query : queries()) {
                String context = memoryManager.buildContextForQuery(query.query(), 2_000);
                boolean injected = containsAll(context, query.expectedTerms());
                RecallResult recall = recallByQuery.get(query);
                injectionResults.add(new InjectionResult(query, injected,
                        recall != null && recall.matched(),
                        recall == null ? Set.of() : recall.hitIds()));
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
            double retrievalToInjectionTransferRate = ratioOrZero(
                    injectionResults.stream().filter(InjectionResult::recalledBeforeInjection)
                            .filter(InjectionResult::injected).count(),
                    injectionResults.stream().filter(InjectionResult::recalledBeforeInjection).count());

            Path report = writeReport(llm, embeddingClient, policyResults, recallResults, injectionResults,
                    writeAccuracy, lowValueBlockRate, recallAt5, injectionHitRate,
                    retrievalToInjectionTransferRate);
            System.out.printf(Locale.ROOT,
                    "Real LLM memory benchmark: write_accuracy=%.1f%% low_value_block=%.1f%% recall@5=%.1f%% injection=%.1f%% transfer=%.1f%% scenarios=%d memories=%d vectors=%d report=%s%n",
                    writeAccuracy * 100, lowValueBlockRate * 100, recallAt5 * 100, injectionHitRate * 100,
                    retrievalToInjectionTransferRate * 100,
                    queries().size(),
                    longTermMemory.size(), vectorStore.count(), report);

            assertTrue(writeAccuracy >= 0.70, "write accuracy below threshold; report=" + report);
            assertTrue(lowValueBlockRate >= 0.70, "low-value block rate below threshold; report=" + report);
            assertTrue(recallAt5 >= 0.70, "recall@5 below threshold; report=" + report);
            assertTrue(injectionHitRate >= 0.70, "injection hit rate below threshold; report=" + report);
        }
    }

    private static LlmClient resolveRealLlmClientOrSkip() {
        DevCliConfig config = DevCliConfig.load();
        String preferred = System.getProperty("devcli.it.memory.provider", "openai");
        LlmClient client = LlmClientFactory.create(preferred, config);
        if (client == null) {
            for (String provider : List.of("anthropic", "kimi", "glm", "deepseek", "step")) {
                client = LlmClientFactory.create(provider, config);
                if (client != null) break;
            }
        }
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
        List<Candidate> candidates = new ArrayList<>();
        rememberedScenarios().forEach(scenario ->
                candidates.add(new Candidate("请记住：" + scenario.fact(), true, "SAVE")));
        candidates.addAll(List.of(
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
        ));
        return List.copyOf(candidates);
    }

    private static List<QueryCase> queries() {
        return rememberedScenarios().stream()
                .map(scenario -> new QueryCase(scenario.query(), scenario.expectedTerms()))
                .toList();
    }

    private static List<MemoryScenario> rememberedScenarios() {
        List<MemoryScenario> scenarios = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            scenarios.add(new MemoryScenario("preference-" + index,
                    "工作区 workspace-" + index + " 默认使用 zh-style-" + index + " 的回答风格",
                    "workspace-" + index + " 默认使用什么回答风格？",
                    List.of("workspace-" + index, "zh-style-" + index)));
            scenarios.add(new MemoryScenario("command-" + index,
                    "服务 service-" + index + " 的发布命令是 deploy-service-" + index + " --safe",
                    "service-" + index + " 应该使用什么发布命令？",
                    List.of("deploy-service-" + index, "--safe")));
            scenarios.add(new MemoryScenario("version-" + index,
                    "模块 module-" + index + " 的运行时版本固定为 runtime-v" + index + ".2",
                    "module-" + index + " 固定使用哪个运行时版本？",
                    List.of("runtime-v" + index + ".2")));
            scenarios.add(new MemoryScenario("path-" + index,
                    "模块 component-" + index + " 的配置路径是 Config/component-" + index + ".yaml",
                    "component-" + index + " 的配置文件放在哪里？",
                    List.of("Config/component-" + index + ".yaml")));
            scenarios.add(new MemoryScenario("constraint-" + index,
                    "任务 task-" + index + " 禁止修改 protected-module-" + index + " 目录",
                    "task-" + index + " 明确禁止修改哪个目录？",
                    List.of("protected-module-" + index)));
            scenarios.add(new MemoryScenario("default-" + index,
                    "租户 tenant-" + index + " 的默认超时时间是 " + (30 + index) + " 秒",
                    "tenant-" + index + " 的默认超时时间是多少？",
                    List.of(String.valueOf(30 + index), "秒")));
        }
        return List.copyOf(scenarios);
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
                             double injectionHitRate,
                             double retrievalToInjectionTransferRate) throws Exception {
        Path dir = Path.of(System.getProperty("devcli.benchmark.report.dir",
                Path.of("target", "benchmark-reports").toString()));
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
        metrics.put("retrieval_to_injection_transfer_rate", round4(retrievalToInjectionTransferRate));
        metrics.put("scenario_count", queries().size());
        metrics.put("minimum_resume_scenario_count", 50);
        root.put("injection_measurement_independent_from_recall", true);

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
            node.put("recalled_before_injection", result.recalledBeforeInjection());
            node.putPOJO("recalled_hit_ids", result.recalledHitIds().stream().toList());
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

    private static double ratioOrZero(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record Candidate(String fact, boolean explicit, String expectedAction) {}

    private record MemoryScenario(String id, String fact, String query, List<String> expectedTerms) {}

    private record QueryCase(String query, List<String> expectedTerms) {}

    private record PolicyResult(Candidate candidate, String actualAction, boolean stored) {}

    private record RecallResult(QueryCase query, boolean matched, Set<String> hitIds) {}

    private record InjectionResult(QueryCase query,
                                   boolean injected,
                                   boolean recalledBeforeInjection,
                                   Set<String> recalledHitIds) {
        private InjectionResult {
            recalledHitIds = Set.copyOf(recalledHitIds == null ? Set.of() : recalledHitIds);
        }
    }
}
