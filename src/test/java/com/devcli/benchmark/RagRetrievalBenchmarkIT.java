package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.rag.CodeIndex;
import com.devcli.rag.CodeRetriever;
import com.devcli.rag.EmbeddingClient;
import com.devcli.rag.VectorStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOP_K = 5;

    @Test
    @DisplayName("real RAG retrieval benchmark: index project, retrieve, compute recall@5 and chain coverage")
    void benchmarkRealRagRetrieval(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.rag"),
                "set -Ddevcli.benchmark.rag=true to run real embedding RAG benchmark");

        String previousRagDir = System.getProperty("devcli.rag.dir");
        Path ragDir = tempDir.resolve("rag-db");
        System.setProperty("devcli.rag.dir", ragDir.toString());
        try {
            EmbeddingClient embeddingClient = new EmbeddingClient();
            List<BenchmarkDataset> datasets = new ArrayList<>();
            datasets.add(sampleProjectDataset(tempDir));
            if (Boolean.getBoolean("devcli.benchmark.rag.currentSource")) {
                datasets.add(devCliSourceDataset());
            }
            for (BenchmarkDataset dataset : datasets) {
                CodeIndex.IndexResult indexResult = new CodeIndex(embeddingClient).index(dataset.projectRoot().toString());
                assertTrue(indexResult.chunkCount() > 0, "index should create chunks for " + dataset.name());
                assertTrue(indexResult.relationCount() > 0, "index should create relations for " + dataset.name());

                List<QueryScore> scores = new ArrayList<>();
                String rerankStrategy;
                try (CodeRetriever retriever = new CodeRetriever(dataset.projectRoot().toString(), embeddingClient)) {
                    rerankStrategy = retriever.rerankStrategy();
                    for (QueryCase queryCase : dataset.queryCases()) {
                        List<VectorStore.SearchResult> baseline = retriever.semanticSearch(queryCase.query(), TOP_K);
                        List<VectorStore.SearchResult> improved = retriever.search(queryCase.query(), TOP_K,
                                queryCase.mode(), queryCase.graphDepth());
                        scores.add(new QueryScore(queryCase, baseline, improved));
                    }
                }

                Path report = writeReport(tempDir.resolve("rag-benchmark").resolve(dataset.name()),
                        embeddingClient, dataset, rerankStrategy, indexResult, scores);
                System.out.println("RAG retrieval benchmark report: " + report);
                System.out.println(Files.readString(report));
                assertTrue(Files.exists(report), "benchmark report should be written for " + dataset.name());
            }
        } finally {
            if (previousRagDir == null) {
                System.clearProperty("devcli.rag.dir");
            } else {
                System.setProperty("devcli.rag.dir", previousRagDir);
            }
        }
    }

    private static BenchmarkDataset sampleProjectDataset(Path tempDir) throws Exception {
        Path project = tempDir.resolve("sample-project");
        writeSampleProject(project);
        return new BenchmarkDataset("synthetic-java-call-chain", "synthetic_sample_project", project, List.of(
                new QueryCase("UserController detail 调用链", "call_chain", 3,
                        List.of("UserController.detail", "UserService.detail", "UserServiceImpl.detail", "UserMapper.selectById")),
                new QueryCase("用户详情审计链路", "call_chain", 3,
                        List.of("UserController.detail", "UserServiceImpl.detail", "AuditLogger.recordView")),
                new QueryCase("下单 checkout 支付库存调用链", "call_chain", 3,
                        List.of("OrderController.checkout", "OrderService.checkout", "PaymentGateway.charge", "InventoryService.reserve")),
                new QueryCase("PromptAssembler 如何组装 PromptContext", "call_chain", 3,
                        List.of("PromptAssembler.assemble", "PromptRepository.systemPrompt", "PromptContext")),
                new QueryCase("OrderService checkout 依赖哪些下游服务", "call_chain", 3,
                        List.of("OrderService.checkout", "PaymentGateway.charge", "InventoryService.reserve"))
        ));
    }

    private static BenchmarkDataset devCliSourceDataset() {
        Path project = Path.of("").toAbsolutePath().normalize();
        return new BenchmarkDataset("devcli-current-source-symbol-rag", "current_devcli_source", project, List.of(
                new QueryCase("CodeRetriever 如何融合 keyword semantic graph RRF", "call_chain", 3,
                        List.of("CodeRetriever.search", "RetrievalFusion.addChannel", "RetrievalFusion.rank")),
                new QueryCase("ResourceLeaseManager 文件资源租约如何拒绝并发写冲突", "definition", 0,
                        List.of("ResourceLeaseManager", "ResourceLeaseManager.acquire")),
                new QueryCase("SymbolVersionDiff 如何生成 NegativeFact 失效记忆", "definition", 0,
                        List.of("SymbolVersion", "SymbolInvalidation.from", "SymbolSnapshot")),
                new QueryCase("JavaParser SymbolSolver 解析方法调用关系并写入 classpathEpoch", "call_chain", 3,
                        List.of("CodeAnalyzer", "CodeAnalyzer.resolveCallee", "ClasspathEpoch")),
                new QueryCase("MemoryManager 如何把 RAG evidence symbolVersion negativeFact 写入记忆", "call_chain", 2,
                        List.of("MemoryManager", "MemoryManager.storeFactWithPolicy", "LongTermMemoryPolicy"))
        ));
    }

    private static void writeSampleProject(Path root) throws Exception {
        Path user = root.resolve("src/main/java/com/example/user");
        Path order = root.resolve("src/main/java/com/example/order");
        Path prompt = root.resolve("src/main/java/com/example/prompt");
        Files.createDirectories(user);
        Files.createDirectories(order);
        Files.createDirectories(prompt);

        Files.writeString(user.resolve("UserController.java"), """
                package com.example.user;

                public class UserController {
                    private UserService userService;

                    public UserVO detail(Long userId) {
                        return userService.detail(userId);
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("UserService.java"), """
                package com.example.user;

                public interface UserService {
                    UserVO detail(Long userId);
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("UserServiceImpl.java"), """
                package com.example.user;

                public class UserServiceImpl implements UserService {
                    private UserMapper userMapper;
                    private AuditLogger auditLogger;

                    public UserVO detail(Long userId) {
                        UserDO user = userMapper.selectById(userId);
                        auditLogger.recordView(user);
                        return new UserVO(user.name());
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("UserMapper.java"), """
                package com.example.user;

                public class UserMapper {
                    public UserDO selectById(Long userId) {
                        return new UserDO("alice");
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("AuditLogger.java"), """
                package com.example.user;

                public class AuditLogger {
                    public void recordView(UserDO user) {
                        System.out.println(user.name());
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("UserDO.java"), """
                package com.example.user;

                public record UserDO(String name) {}
                """, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("UserVO.java"), """
                package com.example.user;

                public record UserVO(String name) {}
                """, StandardCharsets.UTF_8);

        Files.writeString(order.resolve("OrderController.java"), """
                package com.example.order;

                public class OrderController {
                    private OrderService orderService;

                    public Receipt checkout(CheckoutCommand command) {
                        return orderService.checkout(command);
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(order.resolve("OrderService.java"), """
                package com.example.order;

                public class OrderService {
                    private PaymentGateway paymentGateway;
                    private InventoryService inventoryService;

                    public Receipt checkout(CheckoutCommand command) {
                        inventoryService.reserve(command.sku());
                        return paymentGateway.charge(command.amount());
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(order.resolve("PaymentGateway.java"), """
                package com.example.order;

                public class PaymentGateway {
                    public Receipt charge(int amount) {
                        return new Receipt("paid-" + amount);
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(order.resolve("InventoryService.java"), """
                package com.example.order;

                public class InventoryService {
                    public void reserve(String sku) {
                        System.out.println(sku);
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(order.resolve("CheckoutCommand.java"), """
                package com.example.order;

                public record CheckoutCommand(String sku, int amount) {}
                """, StandardCharsets.UTF_8);
        Files.writeString(order.resolve("Receipt.java"), """
                package com.example.order;

                public record Receipt(String id) {}
                """, StandardCharsets.UTF_8);

        Files.writeString(prompt.resolve("PromptAssembler.java"), """
                package com.example.prompt;

                public class PromptAssembler {
                    private PromptRepository promptRepository;

                    public PromptContext assemble(String mode) {
                        String system = promptRepository.systemPrompt(mode);
                        return new PromptContext(system);
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(prompt.resolve("PromptRepository.java"), """
                package com.example.prompt;

                public class PromptRepository {
                    public String systemPrompt(String mode) {
                        return "system prompt for " + mode;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(prompt.resolve("PromptContext.java"), """
                package com.example.prompt;

                public record PromptContext(String systemPrompt) {}
                """, StandardCharsets.UTF_8);
    }

    private static Path writeReport(Path reportDir, EmbeddingClient embeddingClient,
                                    BenchmarkDataset dataset,
                                    String rerankStrategy,
                                    CodeIndex.IndexResult indexResult,
                                    List<QueryScore> scores) throws Exception {
        Files.createDirectories(reportDir);
        ObjectNode root = JSON.createObjectNode();
        root.put("created_at", Instant.now().toString());
        root.put("dataset_name", dataset.name());
        root.put("dataset_type", dataset.type());
        root.put("project_root", dataset.projectRoot().toString());
        root.put("embedding_provider", embeddingClient.getProvider());
        root.put("embedding_model", embeddingClient.getModel());
        root.put("top_k", TOP_K);
        root.put("ranking_strategy", "semantic baseline vs keyword + semantic + bounded graph + RRF + symbol-aware boost + optional cross-encoder rerank");
        root.put("rerank_strategy", rerankStrategy);
        root.put("chunk_count", indexResult.chunkCount());
        root.put("relation_count", indexResult.relationCount());

        ArrayNode queries = root.putArray("queries");
        for (QueryScore score : scores) {
            ObjectNode node = queries.addObject();
            node.put("query", score.queryCase().query());
            node.put("mode", score.queryCase().mode());
            node.put("graph_depth", score.queryCase().graphDepth());
            node.putPOJO("gold_chain", score.queryCase().goldNames());
            node.put("baseline_recall_at_5", recall(score.baseline(), score.queryCase().goldNames()));
            node.put("improved_recall_at_5", recall(score.improved(), score.queryCase().goldNames()));
            node.put("baseline_chain_coverage", recall(score.baseline(), score.queryCase().goldNames()));
            node.put("improved_chain_coverage", recall(score.improved(), score.queryCase().goldNames()));
            node.putPOJO("baseline_top5", resultNames(score.baseline()));
            node.putPOJO("improved_top5", resultNames(score.improved()));
        }

        double baselineRecall = average(scores.stream()
                .mapToDouble(score -> recall(score.baseline(), score.queryCase().goldNames()))
                .toArray());
        double improvedRecall = average(scores.stream()
                .mapToDouble(score -> recall(score.improved(), score.queryCase().goldNames()))
                .toArray());
        ObjectNode aggregate = root.putObject("aggregate");
        aggregate.put("baseline_recall_at_5", baselineRecall);
        aggregate.put("improved_recall_at_5", improvedRecall);
        aggregate.put("recall_at_5_delta_pct_points", pctPoints(improvedRecall - baselineRecall));
        aggregate.put("baseline_chain_coverage", baselineRecall);
        aggregate.put("improved_chain_coverage", improvedRecall);
        aggregate.put("chain_coverage_delta_pct_points", pctPoints(improvedRecall - baselineRecall));

        Path report = reportDir.resolve("rag-retrieval-benchmark.json");
        Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        return report;
    }

    private static double recall(List<VectorStore.SearchResult> results, List<String> goldNames) {
        if (goldNames.isEmpty()) {
            return 0.0;
        }
        long hits = goldNames.stream()
                .filter(gold -> results.stream().anyMatch(result -> matches(result.name(), gold)))
                .count();
        return round4((double) hits / goldNames.size());
    }

    private static boolean matches(String resultName, String goldName) {
        String result = normalize(resultName);
        String gold = normalize(goldName);
        if (result.equals(gold) || result.startsWith(gold + "(")) {
            return true;
        }

        int dot = gold.lastIndexOf('.');
        if (dot > 0 && dot < gold.length() - 1) {
            String goldOwner = gold.substring(0, dot);
            String goldMethod = gold.substring(dot + 1);
            return result.startsWith(goldOwner + ".")
                    && (result.startsWith(goldOwner + "." + goldMethod + "(")
                    || result.contains(" " + goldMethod + "("));
        }

        String normalizedPath = result.replace('\\', '/');
        return normalizedPath.endsWith("/" + gold + ".java");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> resultNames(List<VectorStore.SearchResult> results) {
        return results.stream().limit(TOP_K).map(VectorStore.SearchResult::name).toList();
    }

    private static double average(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return round4(sum / values.length);
    }

    private static double pctPoints(double value) {
        return Math.round(value * 10_000.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record BenchmarkDataset(String name, String type, Path projectRoot, List<QueryCase> queryCases) {
    }

    private record QueryCase(String query, String mode, Integer graphDepth, List<String> goldNames) {
    }

    private record QueryScore(QueryCase queryCase,
                              List<VectorStore.SearchResult> baseline,
                              List<VectorStore.SearchResult> improved) {
    }
}
