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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
            List<BenchmarkDataset> datasets = List.of(codeSearchNetJavaDataset(tempDir));
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

                Path report = writeReport(benchmarkReportRoot().resolve("rag").resolve(dataset.name()),
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

    private static BenchmarkDataset codeSearchNetJavaDataset(Path tempDir) throws Exception {
        int corpusLimit = Math.max(100, Integer.getInteger(
                "devcli.benchmark.rag.codesearchnet.corpus", 1_000));
        int queryLimit = Math.max(10, Integer.getInteger(
                "devcli.benchmark.rag.codesearchnet.queries", 200));
        long seed = Long.getLong("devcli.benchmark.rag.codesearchnet.seed", 20_260_809L);
        ObjectNode rows = loadCodeSearchNetRows(Math.max(corpusLimit * 2, queryLimit));
        List<CodeSearchNetJavaDatasetAdapter.SourceCase> sourceCases =
                CodeSearchNetJavaDatasetAdapter.fromHuggingFaceRows(rows, Integer.MAX_VALUE);
        if (sourceCases.isEmpty()) {
            throw new IOException("CodeSearchNet Java rows response did not contain usable Java functions");
        }
        CodeSearchNetJavaDatasetAdapter.EvaluationSet evaluation =
                CodeSearchNetJavaDatasetAdapter.selectEvaluationCases(
                        sourceCases, corpusLimit, queryLimit, seed);
        if (evaluation.corpus().size() < corpusLimit || evaluation.queries().size() < queryLimit) {
            throw new IOException("CodeSearchNet Java usable rows are insufficient: corpus="
                    + evaluation.corpus().size() + "/" + corpusLimit
                    + ", queries=" + evaluation.queries().size() + "/" + queryLimit);
        }
        List<String> leakedCaseIds = evaluation.corpus().stream()
                .filter(CodeSearchNetJavaDatasetAdapter::hasQueryTextLeak)
                .map(CodeSearchNetJavaDatasetAdapter.SourceCase::id)
                .toList();
        boolean queryTextExcludedFromSource = leakedCaseIds.isEmpty();
        if (!queryTextExcludedFromSource) {
            throw new IOException("CodeSearchNet evaluation rejected because query text appears in indexed source: "
                    + leakedCaseIds.stream().limit(10).toList());
        }
        Path project = tempDir.resolve("codesearchnet-java-public");
        CodeSearchNetJavaDatasetAdapter.writeSyntheticProject(project, evaluation.corpus());
        List<QueryCase> queries = evaluation.queries().stream()
                .map(sourceCase -> new QueryCase(
                        sourceCase.query(), "definition", 0, List.of(sourceCase.goldName()),
                        sourceCase.id(), sourceCase.repositoryName()))
                .toList();
        return new BenchmarkDataset(
                "codesearchnet-java-public", "public_codesearchnet_java", project, queries,
                evaluation.corpus().size(), seed, queryTextExcludedFromSource);
    }

    private static ObjectNode loadCodeSearchNetRows(int requestedRows) throws Exception {
        String configuredFile = System.getProperty("devcli.benchmark.rag.codesearchnet.file", "").trim();
        if (!configuredFile.isBlank()) {
            Path file = Path.of(configuredFile).toAbsolutePath().normalize();
            ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.path("rows").isArray()) {
                throw new IOException("CodeSearchNet rows file must contain a rows array: " + file);
            }
            return root;
        }

        ObjectNode root = JSON.createObjectNode();
        ArrayNode rows = root.putArray("rows");
        HttpClient client = HttpClient.newHttpClient();
        int offset = Math.max(0, Integer.getInteger("devcli.benchmark.rag.codesearchnet.offset", 0));
        int remaining = requestedRows;
        while (remaining > 0) {
            int pageSize = Math.min(100, remaining);
            String url = "https://datasets-server.huggingface.co/rows?dataset=code-search-net/code_search_net"
                    + "&config=java&split=test&offset=" + offset + "&length=" + pageSize;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("CodeSearchNet Java rows request failed: HTTP "
                        + response.statusCode() + " - " + response.body());
            }
            ArrayNode page = (ArrayNode) JSON.readTree(response.body()).path("rows");
            if (page.isEmpty()) {
                break;
            }
            page.forEach(rows::add);
            offset += page.size();
            remaining -= page.size();
            if (page.size() < pageSize) {
                break;
            }
        }
        return root;
    }

    private static Path benchmarkReportRoot() {
        String configured = System.getProperty("devcli.benchmark.report.dir", "target/benchmark-reports");
        return Path.of(configured).toAbsolutePath().normalize();
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
        root.put("corpus_case_count", dataset.corpusCaseCount());
        root.put("query_sampling_seed", dataset.samplingSeed());
        root.put("query_text_excluded_from_source", dataset.queryTextExcludedFromSource());
        root.put("ranking_strategy", "semantic baseline vs keyword + semantic + bounded graph + RRF + symbol-aware boost + optional cross-encoder rerank");
        root.put("rerank_strategy", rerankStrategy);
        root.put("chunk_count", indexResult.chunkCount());
        root.put("relation_count", indexResult.relationCount());

        ArrayNode queries = root.putArray("queries");
        for (QueryScore score : scores) {
            ObjectNode node = queries.addObject();
            node.put("query", score.queryCase().query());
            node.put("case_id", score.queryCase().caseId());
            node.put("repository", score.queryCase().repositoryName());
            node.put("mode", score.queryCase().mode());
            node.put("graph_depth", score.queryCase().graphDepth());
            node.putPOJO("gold_chain", score.queryCase().goldNames());
            List<String> baselineNames = resultNames(score.baseline());
            List<String> improvedNames = resultNames(score.improved());
            node.put("baseline_recall_at_5", recall(baselineNames, score.queryCase().goldNames()));
            node.put("improved_recall_at_5", recall(improvedNames, score.queryCase().goldNames()));
            node.put("baseline_mrr_at_5", reciprocalRank(baselineNames, score.queryCase().goldNames()));
            node.put("improved_mrr_at_5", reciprocalRank(improvedNames, score.queryCase().goldNames()));
            node.put("baseline_ndcg_at_5", ndcg(baselineNames, score.queryCase().goldNames()));
            node.put("improved_ndcg_at_5", ndcg(improvedNames, score.queryCase().goldNames()));
            node.put("baseline_chain_coverage", recall(baselineNames, score.queryCase().goldNames()));
            node.put("improved_chain_coverage", recall(improvedNames, score.queryCase().goldNames()));
            node.putPOJO("baseline_top5", baselineNames);
            node.putPOJO("improved_top5", improvedNames);
        }

        double baselineRecall = average(scores.stream()
                .mapToDouble(score -> recall(resultNames(score.baseline()), score.queryCase().goldNames()))
                .toArray());
        double improvedRecall = average(scores.stream()
                .mapToDouble(score -> recall(resultNames(score.improved()), score.queryCase().goldNames()))
                .toArray());
        double baselineMrr = average(scores.stream()
                .mapToDouble(score -> reciprocalRank(resultNames(score.baseline()), score.queryCase().goldNames()))
                .toArray());
        double improvedMrr = average(scores.stream()
                .mapToDouble(score -> reciprocalRank(resultNames(score.improved()), score.queryCase().goldNames()))
                .toArray());
        double baselineNdcg = average(scores.stream()
                .mapToDouble(score -> ndcg(resultNames(score.baseline()), score.queryCase().goldNames()))
                .toArray());
        double improvedNdcg = average(scores.stream()
                .mapToDouble(score -> ndcg(resultNames(score.improved()), score.queryCase().goldNames()))
                .toArray());
        ObjectNode aggregate = root.putObject("aggregate");
        aggregate.put("query_count", scores.size());
        aggregate.put("baseline_recall_at_5", baselineRecall);
        aggregate.put("improved_recall_at_5", improvedRecall);
        aggregate.put("recall_at_5_delta_pct_points", pctPoints(improvedRecall - baselineRecall));
        aggregate.put("baseline_mrr_at_5", baselineMrr);
        aggregate.put("improved_mrr_at_5", improvedMrr);
        aggregate.put("mrr_at_5_delta_pct_points", pctPoints(improvedMrr - baselineMrr));
        aggregate.put("baseline_ndcg_at_5", baselineNdcg);
        aggregate.put("improved_ndcg_at_5", improvedNdcg);
        aggregate.put("ndcg_at_5_delta_pct_points", pctPoints(improvedNdcg - baselineNdcg));
        aggregate.put("baseline_chain_coverage", baselineRecall);
        aggregate.put("improved_chain_coverage", improvedRecall);
        aggregate.put("chain_coverage_delta_pct_points", pctPoints(improvedRecall - baselineRecall));

        Path report = reportDir.resolve("rag-retrieval-benchmark.json");
        Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        return report;
    }

    private static double recall(List<String> rankedNames, List<String> goldNames) {
        return RetrievalMetrics.recallAtK(rankedNames, goldNames, TOP_K,
                RagRetrievalBenchmarkIT::matches);
    }

    private static double reciprocalRank(List<String> rankedNames, List<String> goldNames) {
        return RetrievalMetrics.reciprocalRankAtK(rankedNames, goldNames, TOP_K,
                RagRetrievalBenchmarkIT::matches);
    }

    private static double ndcg(List<String> rankedNames, List<String> goldNames) {
        return RetrievalMetrics.ndcgAtK(rankedNames, goldNames, TOP_K,
                RagRetrievalBenchmarkIT::matches);
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

    private record BenchmarkDataset(String name,
                                    String type,
                                    Path projectRoot,
                                    List<QueryCase> queryCases,
                                    int corpusCaseCount,
                                    long samplingSeed,
                                    boolean queryTextExcludedFromSource) {
    }

    private record QueryCase(String query,
                             String mode,
                             Integer graphDepth,
                             List<String> goldNames,
                             String caseId,
                             String repositoryName) {
        private QueryCase(String query, String mode, Integer graphDepth, List<String> goldNames) {
            this(query, mode, graphDepth, goldNames, "", "");
        }
    }

    private record QueryScore(QueryCase queryCase,
                              List<VectorStore.SearchResult> baseline,
                              List<VectorStore.SearchResult> improved) {
    }
}
