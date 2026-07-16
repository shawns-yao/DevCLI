package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.memory.MemoryEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicLongContextBenchmarkIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void runsPinnedLongMemEvalLongBenchAndRulerSamples() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.public.longcontext"),
                "set -Ddevcli.benchmark.public.longcontext=true to run real LLM public benchmark");
        LlmClient llm = LlmClientFactory.createFromConfig(DevCliConfig.load());
        Assumptions.assumeTrue(llm != null, "no configured LLM client");

        Path root = Path.of("").toAbsolutePath().normalize();
        PublicBenchmarkCatalog.Catalog catalog = PublicBenchmarkCatalog.load(root);
        int limit = Math.max(1, Integer.getInteger("devcli.benchmark.public.limit", 3));
        List<String> longBenchDatasets = selectedLongBenchDatasets();

        List<PublicBenchmarkDatasets.LongMemEvalCase> longMemCases =
                PublicBenchmarkDatasets.loadLongMemEval(
                        catalog.resolveArtifact(catalog.require("longmemeval-oracle")), limit);
        PublicBenchmarkCatalog.DatasetDescriptor longBenchDescriptor = catalog.require("longbench-v1");
        Path promptConfig = PublicBenchmarkDatasets.findLongBenchPromptConfig(
                catalog.resolveHarness(longBenchDescriptor));
        List<PublicBenchmarkDatasets.LongBenchCase> longBenchCases =
                PublicBenchmarkDatasets.loadLongBench(
                        catalog.resolveExtractedRoot(longBenchDescriptor), promptConfig,
                        longBenchDatasets, limit);
        Path rulerFile = root.resolve(
                "Data/raw/public-benchmarks/ruler/generated/niah_single_1/validation.jsonl");
        Assumptions.assumeTrue(Files.isRegularFile(rulerFile),
                "generate RULER niah_single_1 samples before running public long-context benchmark");
        List<PublicBenchmarkDatasets.RulerCase> rulerCases =
                PublicBenchmarkDatasets.loadRuler(rulerFile, limit);

        Path reportDir = root.resolve("target/benchmark-reports/public");
        Files.createDirectories(reportDir);
        Path hypotheses = reportDir.resolve("longmemeval-hypotheses.jsonl");
        ObjectNode report = JSON.createObjectNode();
        report.put("schema_version", 1);
        report.put("generated_at", Instant.now().toString());
        report.put("provider", llm.getProviderName());
        report.put("model", llm.getModelName());
        report.put("limit_per_dataset", limit);
        ArrayNode longMemResults = report.putArray("longmemeval_oracle");
        ArrayNode longBenchResults = report.putArray("longbench_v1");
        ArrayNode rulerResults = report.putArray("ruler_v1");
        Totals totals = new Totals();

        try (BufferedWriter hypothesisWriter = Files.newBufferedWriter(
                hypotheses, StandardCharsets.UTF_8)) {
            for (PublicBenchmarkDatasets.LongMemEvalCase benchmarkCase : longMemCases) {
                CallResult result = call(llm, benchmarkCase.prompt());
                boolean proxyHit = result.error().isBlank()
                        && PublicBenchmarkMetrics.normalizedAnswerHit(result.content(), benchmarkCase.answer());
                ObjectNode item = longMemResults.addObject();
                item.put("question_id", benchmarkCase.questionId());
                item.put("question_type", benchmarkCase.questionType());
                item.put("session_count", benchmarkCase.sessionCount());
                item.put("answer", benchmarkCase.answer());
                item.put("hypothesis", result.content());
                item.put("normalized_answer_hit", proxyHit);
                addCall(item, result);
                hypothesisWriter.write(JSON.writeValueAsString(
                        java.util.Map.of("question_id", benchmarkCase.questionId(),
                                "hypothesis", result.content())));
                hypothesisWriter.newLine();
                totals.add(result, proxyHit ? 1.0 : 0.0, "longmem");
            }
        }

        for (PublicBenchmarkDatasets.LongBenchCase benchmarkCase : longBenchCases) {
            CallResult result = call(llm, benchmarkCase.prompt());
            double score = scoreLongBench(benchmarkCase, result.content());
            ObjectNode item = longBenchResults.addObject();
            item.put("id", benchmarkCase.id());
            item.put("dataset", benchmarkCase.dataset());
            item.put("length", benchmarkCase.length());
            item.put("metric", benchmarkCase.metric());
            item.put("score", score);
            item.put("prediction", result.content());
            item.set("answers", JSON.valueToTree(benchmarkCase.answers()));
            addCall(item, result);
            totals.add(result, score, "longbench");
        }

        for (PublicBenchmarkDatasets.RulerCase benchmarkCase : rulerCases) {
            CallResult result = call(llm, benchmarkCase.prompt());
            double score = PublicBenchmarkMetrics.rulerStringMatchAll(
                    result.content(), benchmarkCase.answers());
            ObjectNode item = rulerResults.addObject();
            item.put("id", benchmarkCase.id());
            item.put("task", benchmarkCase.task());
            item.put("length", benchmarkCase.length());
            item.put("score", score);
            item.put("prediction", result.content());
            item.set("answers", JSON.valueToTree(benchmarkCase.answers()));
            addCall(item, result);
            totals.add(result, score, "ruler");
        }

        ObjectNode aggregate = report.putObject("aggregate");
        aggregate.put("longmemeval_normalized_answer_hit_rate", totals.average("longmem"));
        aggregate.put("longbench_official_metric_average", totals.average("longbench"));
        aggregate.put("ruler_official_string_match_average", totals.average("ruler"));
        aggregate.put("input_tokens", totals.inputTokens);
        aggregate.put("output_tokens", totals.outputTokens);
        aggregate.put("elapsed_ms", totals.elapsedMs);
        aggregate.put("failed_calls", totals.failedCalls);
        report.put("longmemeval_official_judge_status",
                "hypotheses generated; run the pinned LongMemEval evaluate_qa.py with a supported judge model");

        Path output = reportDir.resolve("public-long-context-benchmark.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(hypotheses));
    }

    private static CallResult call(LlmClient llm, String prompt) {
        long started = System.currentTimeMillis();
        try {
            String systemPrompt = "Answer the benchmark question directly. Do not use tools. Do not explain unless the prompt requires it.";
            LlmClient.ChatResponse response = llm.chat(List.of(
                    LlmClient.Message.system(systemPrompt),
                    LlmClient.Message.user(prompt)), null);
            boolean estimatedInput = response.inputTokens() <= 0;
            int inputTokens = estimatedInput
                    ? MemoryEntry.estimateTokens(systemPrompt + "\n" + prompt)
                    : response.inputTokens();
            return new CallResult(response.content() == null ? "" : response.content().trim(),
                    inputTokens, response.outputTokens(), estimatedInput,
                    System.currentTimeMillis() - started, "");
        } catch (Exception e) {
            return new CallResult("", MemoryEntry.estimateTokens(prompt), 0, true,
                    System.currentTimeMillis() - started,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static double scoreLongBench(PublicBenchmarkDatasets.LongBenchCase benchmarkCase,
                                         String prediction) {
        double best = 0.0;
        for (String answer : benchmarkCase.answers()) {
            double score = switch (benchmarkCase.dataset()) {
                case "passage_count" -> PublicBenchmarkMetrics.longBenchCountScore(prediction, answer);
                case "passage_retrieval_en" -> PublicBenchmarkMetrics.longBenchRetrievalScore(prediction, answer);
                default -> PublicBenchmarkMetrics.normalizedAnswerHit(prediction, answer) ? 1.0 : 0.0;
            };
            best = Math.max(best, score);
        }
        return best;
    }

    private static List<String> selectedLongBenchDatasets() {
        String configured = System.getProperty("devcli.benchmark.public.longbench.datasets",
                "passage_count,passage_retrieval_en");
        List<String> selected = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        return selected.isEmpty()
                ? List.of("passage_count", "passage_retrieval_en")
                : selected;
    }

    private static void addCall(ObjectNode item, CallResult result) {
        item.put("input_tokens", result.inputTokens());
        item.put("output_tokens", result.outputTokens());
        item.put("input_tokens_estimated", result.inputTokensEstimated());
        item.put("elapsed_ms", result.elapsedMs());
        item.put("error", result.error());
    }

    private record CallResult(String content, int inputTokens, int outputTokens,
                              boolean inputTokensEstimated, long elapsedMs, String error) {
    }

    private static final class Totals {
        private final List<Double> longMem = new ArrayList<>();
        private final List<Double> longBench = new ArrayList<>();
        private final List<Double> ruler = new ArrayList<>();
        private long inputTokens;
        private long outputTokens;
        private long elapsedMs;
        private int failedCalls;

        void add(CallResult result, double score, String dataset) {
            inputTokens += result.inputTokens();
            outputTokens += result.outputTokens();
            elapsedMs += result.elapsedMs();
            if (!result.error().isBlank()) {
                failedCalls++;
            }
            switch (dataset) {
                case "longmem" -> longMem.add(score);
                case "longbench" -> longBench.add(score);
                case "ruler" -> ruler.add(score);
                default -> throw new IllegalArgumentException("unknown public benchmark group: " + dataset);
            }
        }

        double average(String dataset) {
            List<Double> values = switch (dataset) {
                case "longmem" -> longMem;
                case "longbench" -> longBench;
                case "ruler" -> ruler;
                default -> List.of();
            };
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }
}
