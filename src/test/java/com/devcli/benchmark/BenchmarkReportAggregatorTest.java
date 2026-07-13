package com.devcli.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkReportAggregatorTest {

    @Test
    void aggregatesReportsIntoJsonCsvAndManifest(@TempDir Path root) throws Exception {
        Path reports = root.resolve("target/benchmark-reports");
        Path rag = reports.resolve("rag/codesearchnet-java-public");
        Path agent = root.resolve("target/agent-benchmark/run-1");
        Files.createDirectories(rag);
        Files.createDirectories(agent);
        Files.writeString(rag.resolve("rag-retrieval-benchmark.json"), """
                {"dataset_name":"codesearchnet-java-public","dataset_type":"public_codesearchnet_java",
                 "embedding_provider":"openai","embedding_model":"embed-model",
                 "aggregate":{"query_count":50,"improved_recall_at_5":0.8,"improved_mrr_at_5":0.7,
                 "improved_ndcg_at_5":0.75,"recall_at_5_delta_pct_points":10.0,
                 "mrr_at_5_delta_pct_points":8.0,"ndcg_at_5_delta_pct_points":9.0}}
                """);
        Files.writeString(reports.resolve("real-llm-memory-benchmark.json"), """
                {"llm_provider":"anthropic","llm_model":"model-x",
                 "metrics":{"write_accuracy":0.9,"low_value_block_rate":0.8,"recall_at_5":0.85,"injection_hit_rate":0.9},
                 "policy_results":[{"expected":"SAVE"},{"expected":"SKIP"},{"expected":"CONFIRM"}],
                 "recall_results":[{},{}],"injection_results":[{},{},{}]}
                """);
        Files.writeString(reports.resolve("real-llm-compression-retention.json"), """
                {"provider":"anthropic","model":"model-x","retention_ratio":0.875,
                 "fact_count":8,"compaction_count":2}
                """);
        Files.writeString(agent.resolve("agent-collaboration-benchmark.json"), """
                {"provider":"anthropic","model":"model-x","sample_size_per_mode":2,
                 "aggregate":{"single_agent":{"task_success_rate":0.5,"avg_completion_rate":0.7,
                 "avg_hidden_failure_rate":0.3,"avg_unique_bug_rate":0.2},
                 "planner_worker_reviewer":{"task_success_rate":1.0,"avg_completion_rate":0.95,
                 "avg_hidden_failure_rate":0.05,"avg_unique_bug_rate":0.05},
                 "comparison":{"task_success_rate_delta_pct_points":50.0}}}
                """);

        String previous = System.getProperty("devcli.benchmark.report.dir");
        System.setProperty("devcli.benchmark.report.dir", reports.toString());
        try {
            BenchmarkReportAggregator.Result result =
                    BenchmarkReportAggregator.aggregate(root, "20260713_test");

            assertTrue(Files.readString(result.json()).contains("improved_ndcg_at_5"));
            String csv = Files.readString(result.csv());
            assertTrue(csv.contains("task_success_rate"));
            assertTrue(csv.contains("\"memory\",\"write_accuracy\",0.9,\"memory-cases\",\"anthropic\",\"model-x\",3"));
            assertTrue(csv.contains("\"memory\",\"low_value_block_rate\",0.8,\"memory-cases\",\"anthropic\",\"model-x\",2"));
            assertTrue(csv.contains("\"memory\",\"recall_at_5\",0.85,\"memory-cases\",\"anthropic\",\"model-x\",2"));
            assertTrue(csv.contains("\"memory\",\"injection_hit_rate\",0.9,\"memory-cases\",\"anthropic\",\"model-x\",3"));
            assertTrue(Files.readString(result.manifest()).contains("Compression"));
        } finally {
            if (previous == null) {
                System.clearProperty("devcli.benchmark.report.dir");
            } else {
                System.setProperty("devcli.benchmark.report.dir", previous);
            }
        }
    }
}
