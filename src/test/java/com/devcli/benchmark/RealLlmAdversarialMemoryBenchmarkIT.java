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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RealLlmAdversarialMemoryBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void evaluatesAdversarialCrossSessionMemory() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.memory.adversarial"),
                "set -Ddevcli.benchmark.memory.adversarial=true to run real adversarial memory benchmark");
        LlmClient llm = resolveLlmOrSkip();
        EmbeddingClient embeddings = resolveEmbeddingOrSkip();
        List<Scenario> scenarios = scenarios();
        List<WriteResult> writes = new ArrayList<>();

        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryManager manager = new MemoryManager(llm, 32768, llm.maxContextWindow(), memory);
             MemoryVectorStore vectors = new MemoryVectorStore(tempDir)) {
            configure(manager, vectors, embeddings);
            for (Scenario scenario : scenarios) {
                for (String fact : scenario.writes()) {
                    MemoryManager.StoreResult result = manager.storeFactWithPolicy("请记住：" + fact, true);
                    writes.add(new WriteResult(scenario.id(), "SAVE",
                            result.decision().action().name(), result.stored()));
                }
            }
            for (int index = 1; index <= 10; index++) {
                String temporary = "本轮临时把调试输出写到 Temp/session-" + index + ".log，结束后删除";
                MemoryManager.StoreResult result = manager.storeFactWithPolicy(temporary, false);
                writes.add(new WriteResult("temporary-" + index, "SKIP",
                        result.decision().action().name(), result.stored()));
            }
            for (int index = 1; index <= 10; index++) {
                String content = "过期租户的历史路由为 legacy-zone-" + index;
                memory.store(new MemoryEntry(
                        "expired-" + index, content, MemoryEntry.MemoryType.FACT,
                        Instant.now().minusSeconds(7200), Map.of("scenario", "expired"),
                        MemoryEntry.estimateTokens(content), "expired-route-" + index,
                        true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1,
                        Instant.now().minusSeconds(3600)));
            }
            for (int turn = 1; turn <= 25; turn++) {
                manager.addUserMessage("第 " + turn + " 轮临时排查日志，只讨论本轮输出，不需要跨会话记忆");
            }
        }

        List<QueryResult> queries = new ArrayList<>();
        List<ExpiredResult> expired = new ArrayList<>();
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile());
             MemoryManager manager = new MemoryManager(llm, 32768, llm.maxContextWindow(), memory);
             MemoryVectorStore vectors = new MemoryVectorStore(tempDir)) {
            configure(manager, vectors, embeddings);
            for (Scenario scenario : scenarios) {
                List<MemoryEntry> hits = manager.retrieveRelevant(scenario.query(), 5);
                boolean recalled = hits.stream().anyMatch(entry -> accepted(entry.getContent(), scenario));
                String context = manager.buildContextForQuery(scenario.query(), 2_000);
                boolean injected = accepted(context, scenario);
                queries.add(new QueryResult(scenario, recalled, injected,
                        hits.stream().map(MemoryEntry::getContent).toList()));
            }
            for (int index = 1; index <= 10; index++) {
                String forbidden = "legacy-zone-" + index;
                String query = "过期租户第 " + index + " 个历史路由是什么？";
                List<MemoryEntry> hits = manager.retrieveRelevant(query, 5);
                String context = manager.buildContextForQuery(query, 2_000);
                expired.add(new ExpiredResult(index,
                        hits.stream().noneMatch(entry -> containsIgnoreCase(entry.getContent(), forbidden)),
                        !containsIgnoreCase(context, forbidden)));
            }
        }

        double writeAccuracy = ratio(writes.stream()
                .filter(result -> result.expectedAction().equals(result.actualAction())).count(), writes.size());
        double recallAt5 = ratio(queries.stream().filter(QueryResult::recalled).count(), queries.size());
        double injectionRate = ratio(queries.stream().filter(QueryResult::injected).count(), queries.size());
        double transferRate = ratio(queries.stream().filter(QueryResult::recalled)
                        .filter(QueryResult::injected).count(),
                queries.stream().filter(QueryResult::recalled).count());
        double expiryFilterRate = ratio(expired.stream()
                        .filter(result -> result.retrievalFiltered() && result.injectionFiltered()).count(),
                expired.size());

        Path report = writeReport(llm, embeddings, writes, queries, expired,
                writeAccuracy, recallAt5, injectionRate, transferRate, expiryFilterRate);
        System.out.printf(Locale.ROOT,
                "Adversarial memory benchmark: writes=%.1f%% recall@5=%.1f%% injection=%.1f%% transfer=%.1f%% expiry=%.1f%% scenarios=%d report=%s%n",
                writeAccuracy * 100, recallAt5 * 100, injectionRate * 100,
                transferRate * 100, expiryFilterRate * 100, scenarios.size(), report);

        assertTrue(writeAccuracy >= 0.70, "write accuracy below threshold; report=" + report);
        assertTrue(recallAt5 >= 0.70, "recall@5 below threshold; report=" + report);
        assertTrue(injectionRate >= 0.70, "injection rate below threshold; report=" + report);
        assertTrue(expiryFilterRate >= 0.90, "expiry filtering below threshold; report=" + report);
    }

    private static void configure(MemoryManager manager, MemoryVectorStore vectors,
                                  EmbeddingClient embeddings) {
        manager.getLongTermMemory().setVectorIndex(
                entry -> {
                    try {
                        vectors.upsert(entry.getId(), entry.getContent(), embeddings.embed(entry.getContent()));
                    } catch (Exception ignored) {
                    }
                },
                vectors::delete,
                vectors::clear);
        manager.getRetriever().setSemanticSearch((query, topK) -> {
            try {
                return vectors.search(embeddings.embed(query), topK,
                                MemoryVectorStore.DEFAULT_SIMILARITY_THRESHOLD).stream()
                        .map(hit -> new MemoryRetriever.SemanticHit(hit.factId(), hit.similarity()))
                        .toList();
            } catch (Exception error) {
                return List.of();
            }
        });
    }

    private static List<Scenario> scenarios() {
        List<Scenario> result = new ArrayList<>();
        String[] projects = {"苍穹", "远航", "星河", "云杉", "赤霄", "青岚", "北斗", "海棠", "玄武", "白泽"};
        String[] styles = {"先结论后依据", "只给一版方案", "使用短句", "先列风险再执行", "避免英文缩写",
                "保留命令原文", "不输出表情符号", "先说明失败原因", "按影响范围排序", "仅使用简体中文"};
        for (int index = 0; index < 10; index++) {
            result.add(new Scenario("stable-" + (index + 1), "stable",
                    List.of(projects[index] + "项目的答复规范是" + styles[index]),
                    "处理" + projects[index] + "项目问题时，回答应遵循什么规范？",
                    List.of(styles[index]), List.of()));
        }

        String[] services = {"结算", "库存", "会员", "审计", "通知", "搜索", "导出", "风控", "路由", "归档"};
        for (int index = 0; index < 10; index++) {
            int oldValue = 20 + index;
            int newValue = 70 + index;
            result.add(new Scenario("update-" + (index + 1), "same_subject_update",
                    List.of(services[index] + "服务 timeout=" + oldValue,
                            services[index] + "服务 timeout=" + newValue),
                    services[index] + "服务当前允许等待多长时间？",
                    List.of(String.valueOf(newValue)), List.of(String.valueOf(oldValue))));
        }

        String[] modules = {"账单", "订单", "登录", "报表", "文件", "消息", "监控", "权限", "支付", "调度"};
        String[] replacements = {"本地缓存", "顺序队列", "口令认证", "离线快照", "对象存储",
                "事务消息", "采样指标", "最小权限", "延迟扣款", "固定线程池"};
        for (int index = 0; index < 10; index++) {
            result.add(new Scenario("conflict-" + (index + 1), "positive_negative_conflict",
                    List.of(modules[index] + "模块默认使用 Redis",
                            modules[index] + "模块禁止使用 Redis，必须改用" + replacements[index]),
                    modules[index] + "模块最终允许采用哪种方案？",
                    List.of(replacements[index]), List.of("默认使用 Redis")));
        }

        String[] targetRegions = {"华北一", "华东二", "华南三", "西南一", "东北二",
                "西北三", "华中一", "华北四", "华东五", "华南六"};
        for (int index = 0; index < 10; index++) {
            List<String> writes = new ArrayList<>();
            writes.add("租户" + projects[index] + "的生产数据驻留区域为" + targetRegions[index]);
            for (int distractor = 0; distractor < 4; distractor++) {
                writes.add("租户" + projects[(index + distractor + 1) % projects.length]
                        + "的生产数据驻留区域为测试区" + (distractor + 1));
            }
            result.add(new Scenario("similar-" + (index + 1), "high_similarity_distractors",
                    List.copyOf(writes),
                    projects[index] + "租户上线时，生产数据应部署在哪个区域？",
                    List.of(targetRegions[index]), List.of("测试区")));
        }

        String[] paths = {"Config/atlas-prod.yaml", "Config/orion-prod.yaml", "Config/nebula-prod.yaml",
                "Config/cedar-prod.yaml", "Config/phoenix-prod.yaml", "Config/mist-prod.yaml",
                "Config/polaris-prod.yaml", "Config/begonia-prod.yaml", "Config/turtle-prod.yaml",
                "Config/unicorn-prod.yaml"};
        for (int index = 0; index < 10; index++) {
            result.add(new Scenario("restart-" + (index + 1), "cross_session_reload",
                    List.of(projects[index] + "项目生产配置位于" + paths[index]),
                    "重新启动会话后，" + projects[index] + "项目应读取哪个生产配置？",
                    List.of(paths[index]), List.of("测试配置")));
        }
        return List.copyOf(result);
    }

    private static boolean accepted(String text, Scenario scenario) {
        if (text == null) return false;
        boolean expected = scenario.expectedTerms().stream()
                .allMatch(term -> containsIgnoreCase(text, term));
        boolean forbidden = scenario.forbiddenTerms().stream()
                .anyMatch(term -> containsIgnoreCase(text, term));
        return expected && !forbidden;
    }

    private static boolean containsIgnoreCase(String text, String term) {
        return text != null && term != null
                && text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private Path writeReport(LlmClient llm, EmbeddingClient embeddings,
                             List<WriteResult> writes, List<QueryResult> queries,
                             List<ExpiredResult> expired, double writeAccuracy,
                             double recallAt5, double injectionRate,
                             double transferRate, double expiryFilterRate) throws Exception {
        Path dir = Path.of(System.getProperty("devcli.benchmark.report.dir",
                Path.of("target", "benchmark-reports").toString()));
        Files.createDirectories(dir);
        Path report = dir.resolve("real-llm-adversarial-memory-benchmark.json");
        ObjectNode root = JSON.createObjectNode();
        root.put("created_at", Instant.now().toString());
        root.put("llm_provider", llm.getProviderName());
        root.put("llm_model", llm.getModelName());
        root.put("embedding_provider", embeddings.getProvider());
        root.put("embedding_model", embeddings.getModel());
        root.put("scenario_count", queries.size() + expired.size() + 10);
        root.put("write_decision_count", writes.size());
        root.put("retrieval_scenario_count", queries.size());
        root.put("expired_scenario_count", expired.size());
        root.put("temporary_scenario_count", 10);
        root.put("noise_turn_count", 25);
        root.put("cross_session_reload", true);
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("write_accuracy", writeAccuracy);
        metrics.put("recall_at_5", recallAt5);
        metrics.put("injection_hit_rate", injectionRate);
        metrics.put("retrieval_to_injection_transfer_rate", transferRate);
        metrics.put("expiry_filter_rate", expiryFilterRate);
        ArrayNode queryNodes = root.putArray("queries");
        for (QueryResult result : queries) {
            ObjectNode node = queryNodes.addObject();
            node.put("id", result.scenario().id());
            node.put("category", result.scenario().category());
            node.put("query", result.scenario().query());
            node.put("recalled", result.recalled());
            node.put("injected", result.injected());
            node.putPOJO("expected_terms", result.scenario().expectedTerms());
            node.putPOJO("forbidden_terms", result.scenario().forbiddenTerms());
            node.putPOJO("top5", result.top5());
        }
        Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        return report;
    }

    private static LlmClient resolveLlmOrSkip() {
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
        return client;
    }

    private static EmbeddingClient resolveEmbeddingOrSkip() {
        try {
            EmbeddingClient client = new EmbeddingClient();
            Assumptions.assumeTrue(client.embed("adversarial memory probe").length > 0,
                    "embedding probe returned empty vector");
            return client;
        } catch (Exception error) {
            Assumptions.abort("embedding unavailable: " + error.getMessage());
            return null;
        }
    }

    private record Scenario(String id, String category, List<String> writes,
                            String query, List<String> expectedTerms,
                            List<String> forbiddenTerms) {
    }

    private record WriteResult(String scenarioId, String expectedAction,
                               String actualAction, boolean stored) {
    }

    private record QueryResult(Scenario scenario, boolean recalled,
                               boolean injected, List<String> top5) {
    }

    private record ExpiredResult(int index, boolean retrievalFiltered,
                                 boolean injectionFiltered) {
    }
}
