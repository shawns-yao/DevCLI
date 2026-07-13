package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BenchmarkReportAggregator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BenchmarkReportAggregator() {
    }

    static Result aggregate(Path projectRoot, String version) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path reportRoot = Path.of(System.getProperty("devcli.benchmark.report.dir",
                root.resolve("target/benchmark-reports").toString())).toAbsolutePath().normalize();
        JsonNode memory = readRequired(reportRoot.resolve("real-llm-memory-benchmark.json"));
        JsonNode compression = readRequired(reportRoot.resolve("real-llm-compression-retention.json"));
        Path agentReport = latest(root.resolve("target/agent-benchmark"),
                "agent-collaboration-benchmark.json");
        JsonNode agent = readRequired(agentReport);
        List<Path> ragReports = all(reportRoot.resolve("rag"), "rag-retrieval-benchmark.json");
        if (ragReports.isEmpty()) {
            throw new IOException("missing RAG benchmark reports under " + reportRoot.resolve("rag"));
        }

        String safeVersion = version == null || version.isBlank() ? "latest" : version.trim();
        Path processed = root.resolve("Data/processed");
        Path manifestDir = root.resolve("Data/manifest");
        Files.createDirectories(processed);
        Files.createDirectories(manifestDir);
        Path jsonFile = processed.resolve("devcli_benchmark_summary_" + safeVersion + ".json");
        Path csvFile = processed.resolve("devcli_benchmark_summary_" + safeVersion + ".csv");
        Path manifestFile = manifestDir.resolve("devcli_benchmark_manifest_" + safeVersion + ".md");

        ObjectNode summary = JSON.createObjectNode();
        summary.put("created_at", Instant.now().toString());
        summary.put("report_version", safeVersion);
        ArrayNode sources = summary.putArray("sources");
        sources.add(relative(root, agentReport));
        sources.add(relative(root, reportRoot.resolve("real-llm-memory-benchmark.json")));
        sources.add(relative(root, reportRoot.resolve("real-llm-compression-retention.json")));
        ragReports.forEach(path -> sources.add(relative(root, path)));

        ArrayNode rag = summary.putArray("rag");
        List<CsvMetric> csvMetrics = new ArrayList<>();
        for (Path report : ragReports) {
            JsonNode source = readRequired(report);
            ObjectNode item = rag.addObject();
            String dataset = source.path("dataset_name").asText();
            String provider = source.path("embedding_provider").asText();
            String model = source.path("embedding_model").asText();
            JsonNode aggregate = source.path("aggregate");
            int sampleSize = aggregate.path("query_count").asInt(source.path("queries").size());
            item.put("dataset_name", dataset);
            item.put("dataset_type", source.path("dataset_type").asText());
            item.put("embedding_provider", provider);
            item.put("embedding_model", model);
            item.put("query_count", sampleSize);
            copyMetric(aggregate, item, csvMetrics, "rag", dataset, provider, model, sampleSize,
                    "improved_recall_at_5");
            copyMetric(aggregate, item, csvMetrics, "rag", dataset, provider, model, sampleSize,
                    "improved_mrr_at_5");
            copyMetric(aggregate, item, csvMetrics, "rag", dataset, provider, model, sampleSize,
                    "improved_ndcg_at_5");
            copyMetric(aggregate, item, csvMetrics, "rag", dataset, provider, model, sampleSize,
                    "recall_at_5_delta_pct_points");
            copyMetric(aggregate, item, csvMetrics, "rag", dataset, provider, model, sampleSize,
                    "mrr_at_5_delta_pct_points");
            copyMetric(aggregate, item, csvMetrics, "rag", dataset, provider, model, sampleSize,
                    "ndcg_at_5_delta_pct_points");
        }

        ObjectNode agentNode = summary.putObject("agent_collaboration");
        agentNode.put("provider", agent.path("provider").asText());
        agentNode.put("model", agent.path("model").asText());
        agentNode.put("task_count", agent.path("sample_size_per_mode").asInt());
        agentNode.set("single_agent", agent.path("aggregate").path("single_agent"));
        agentNode.set("planner_worker_reviewer", agent.path("aggregate").path("planner_worker_reviewer"));
        agentNode.set("comparison", agent.path("aggregate").path("comparison"));
        addAgentCsv(csvMetrics, agentNode);

        ObjectNode memoryNode = summary.putObject("memory");
        memoryNode.put("provider", memory.path("llm_provider").asText());
        memoryNode.put("model", memory.path("llm_model").asText());
        memoryNode.set("metrics", memory.path("metrics"));
        addMemoryCsv(csvMetrics, memory);

        ObjectNode compressionNode = summary.putObject("compression");
        compressionNode.put("provider", compression.path("provider").asText());
        compressionNode.put("model", compression.path("model").asText());
        compressionNode.put("retention_ratio", compression.path("retention_ratio").asDouble());
        compressionNode.put("fact_count", compression.path("fact_count").asInt());
        compressionNode.put("compaction_count", compression.path("compaction_count").asInt());
        csvMetrics.add(new CsvMetric("compression", "retention_ratio",
                compression.path("retention_ratio").asDouble(), "compression-facts",
                compression.path("provider").asText(), compression.path("model").asText(),
                compression.path("fact_count").asInt()));

        Files.writeString(jsonFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8);
        Files.writeString(csvFile, renderCsv(csvMetrics), StandardCharsets.UTF_8);
        Files.writeString(manifestFile, renderManifest(safeVersion, summary, csvMetrics),
                StandardCharsets.UTF_8);
        return new Result(jsonFile, csvFile, manifestFile);
    }

    private static void addMemoryCsv(List<CsvMetric> metrics, JsonNode memory) {
        String provider = memory.path("llm_provider").asText();
        String model = memory.path("llm_model").asText();
        JsonNode values = memory.path("metrics");
        int policyCount = memory.path("policy_results").size();
        int lowValueCount = 0;
        for (JsonNode result : memory.path("policy_results")) {
            if (!"SAVE".equals(result.path("expected").asText())) {
                lowValueCount++;
            }
        }
        metrics.add(new CsvMetric("memory", "write_accuracy", values.path("write_accuracy").asDouble(),
                "memory-cases", provider, model, policyCount));
        metrics.add(new CsvMetric("memory", "low_value_block_rate",
                values.path("low_value_block_rate").asDouble(), "memory-cases", provider, model, lowValueCount));
        metrics.add(new CsvMetric("memory", "recall_at_5", values.path("recall_at_5").asDouble(),
                "memory-cases", provider, model, memory.path("recall_results").size()));
        metrics.add(new CsvMetric("memory", "injection_hit_rate",
                values.path("injection_hit_rate").asDouble(), "memory-cases", provider, model,
                memory.path("injection_results").size()));
    }

    private static void addAgentCsv(List<CsvMetric> metrics, ObjectNode agent) {
        String provider = agent.path("provider").asText();
        String model = agent.path("model").asText();
        int count = agent.path("task_count").asInt();
        for (String mode : List.of("single_agent", "planner_worker_reviewer")) {
            JsonNode node = agent.path(mode);
            for (String metric : List.of("task_success_rate", "avg_completion_rate",
                    "avg_hidden_failure_rate", "avg_unique_bug_rate")) {
                metrics.add(new CsvMetric("agent_" + mode, metric, node.path(metric).asDouble(),
                        "hidden-cli-tasks", provider, model, count));
            }
        }
    }

    private static void copyMetric(JsonNode source, ObjectNode target, List<CsvMetric> csv,
                                   String category, String dataset, String provider, String model,
                                   int sampleSize, String name) {
        double value = source.path(name).asDouble();
        target.put(name, value);
        csv.add(new CsvMetric(category, name, value, dataset, provider, model, sampleSize));
    }

    private static String renderCsv(List<CsvMetric> metrics) {
        StringBuilder csv = new StringBuilder("category,metric,value,dataset,provider,model,sample_size\n");
        for (CsvMetric metric : metrics) {
            csv.append(escape(metric.category())).append(',')
                    .append(escape(metric.metric())).append(',')
                    .append(metric.value()).append(',')
                    .append(escape(metric.dataset())).append(',')
                    .append(escape(metric.provider())).append(',')
                    .append(escape(metric.model())).append(',')
                    .append(metric.sampleSize()).append('\n');
        }
        return csv.toString();
    }

    private static String renderManifest(String version, JsonNode summary, List<CsvMetric> metrics) {
        return "# DevCLI Benchmark Manifest " + version + "\n\n"
                + "- 生成时间：" + summary.path("created_at").asText() + "\n"
                + "- 指标条目：" + metrics.size() + "\n"
                + "- RAG：Recall@5、MRR@5、nDCG@5\n"
                + "- Agent：任务成功率、隐藏检查完成率、隐藏失败率、去重缺陷率\n"
                + "- Memory：写入准确率、低价值拦截率、Recall@5、注入命中率\n"
                + "- Compression：事实保真率\n\n"
                + "## 数据来源\n\n"
                + summary.path("sources").toPrettyString() + "\n";
    }

    private static JsonNode readRequired(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("missing benchmark report: " + path);
        }
        return JSON.readTree(path.toFile());
    }

    private static Path latest(Path root, String fileName) throws IOException {
        List<Path> files = all(root, fileName);
        if (files.isEmpty()) {
            throw new IOException("missing benchmark report under " + root + ": " + fileName);
        }
        return files.stream().max(Comparator.comparingLong(BenchmarkReportAggregator::modifiedAt)).orElseThrow();
    }

    private static List<Path> all(Path root, String fileName) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().equals(fileName))
                    .sorted()
                    .toList();
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String relative(Path root, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : absolute.toString();
    }

    private static String escape(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    record Result(Path json, Path csv, Path manifest) {
    }

    private record CsvMetric(String category, String metric, double value,
                             String dataset, String provider, String model, int sampleSize) {
    }
}
