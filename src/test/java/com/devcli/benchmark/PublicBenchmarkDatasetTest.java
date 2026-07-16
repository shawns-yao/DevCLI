package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBenchmarkDatasetTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesSweBenchRowsAndLongContextFixtures(@TempDir Path root) throws Exception {
        var sweCases = PublicBenchmarkDatasets.parseSweBenchRows(JSON.readTree("""
                {"rows":[{"row":{"instance_id":"repo__issue-1","repo":"owner/repo",
                "base_commit":"abc","problem_statement":"fix bug","hints_text":"hint",
                "FAIL_TO_PASS":"[\\"test_a\\"]","PASS_TO_PASS":["test_b"]}}]}
                """), 5);
        assertEquals(1, sweCases.size());
        assertEquals("repo__issue-1", sweCases.get(0).instanceId());
        assertEquals("[\"test_a\"]", sweCases.get(0).failToPass());

        Path longMem = root.resolve("longmem.json");
        Files.writeString(longMem, """
                [{"question_id":"q1","question_type":"single-session-user",
                "question":"What color?","answer":"blue","question_date":"2026-01-01",
                "haystack_dates":["2025-01-01"],"haystack_session_ids":["s1"],
                "answer_session_ids":["s1"],"haystack_sessions":[[
                {"role":"user","content":"My color is blue","has_answer":true},
                {"role":"assistant","content":"Noted"}]]}]
                """, StandardCharsets.UTF_8);
        var memoryCases = PublicBenchmarkDatasets.loadLongMemEval(longMem, 1);
        assertEquals(1, memoryCases.size());
        assertTrue(memoryCases.get(0).prompt().contains("My color is blue"));
        assertEquals(List.of("s1"), memoryCases.get(0).answerSessionIds());

        Path dataRoot = root.resolve("longbench");
        Files.createDirectories(dataRoot);
        Files.writeString(dataRoot.resolve("passage_count.jsonl"), JSON.writeValueAsString(Map.of(
                "input", "",
                "context", "Paragraph 1. A\nParagraph 2. B",
                "answers", List.of("2"),
                "length", 12,
                "dataset", "passage_count",
                "language", "en",
                "_id", "lb1")), StandardCharsets.UTF_8);
        Path prompts = root.resolve("dataset2prompt.json");
        Files.writeString(prompts, JSON.writeValueAsString(
                Map.of("passage_count", "{context}\nCount: {input}")),
                StandardCharsets.UTF_8);
        var longBenchCases = PublicBenchmarkDatasets.loadLongBench(
                dataRoot, prompts, List.of("passage_count"), 1);
        assertEquals(1, longBenchCases.size());
        assertTrue(longBenchCases.get(0).prompt().contains("Paragraph 2"));

        Path ruler = root.resolve("ruler.jsonl");
        Files.writeString(ruler, """
                {"index":7,"input":"remember 123","outputs":["123"],"length":4096}
                """, StandardCharsets.UTF_8);
        var rulerCases = PublicBenchmarkDatasets.loadRuler(ruler, 1);
        assertEquals(1, rulerCases.size());
        assertEquals(List.of("123"), rulerCases.get(0).answers());
    }

    @Test
    void matchesOfficialExactMetrics() {
        assertEquals(1.0, PublicBenchmarkMetrics.longBenchCountScore("There are 8 passages.", "8"));
        assertEquals(0.5, PublicBenchmarkMetrics.longBenchCountScore("Maybe 8 or 9", "8"));
        assertEquals(1.0, PublicBenchmarkMetrics.longBenchRetrievalScore("Paragraph 15", "Paragraph 15"));
        assertEquals(1.0, PublicBenchmarkMetrics.rulerStringMatchAll(
                "The values are 123 and 456", List.of("123", "456")));
        assertTrue(PublicBenchmarkMetrics.normalizedAnswerHit(
                "The GPS system was not functioning correctly.",
                "GPS system not functioning correctly"));
    }

    @Test
    void writesSweBenchPredictionsAndBuildsOfficialHarnessCommand(@TempDir Path root) throws Exception {
        Path predictions = root.resolve("predictions.jsonl");
        SweBenchOfficialHarness.writePredictions(predictions, List.of(
                new SweBenchOfficialHarness.Prediction(
                        "astropy__astropy-12907", "devcli/model", "diff --git a/a.py b/a.py")));
        String line = Files.readString(predictions);
        assertTrue(line.contains("astropy__astropy-12907"));
        assertTrue(line.contains("model_patch"));

        List<String> command = SweBenchOfficialHarness.evaluationCommand(
                Path.of("python"), predictions, root.resolve("reports"),
                "devcli-run", List.of("astropy__astropy-12907"), 2);
        assertTrue(command.contains("swebench.harness.run_evaluation"));
        assertTrue(command.contains("SWE-bench/SWE-bench_Lite"));
        assertTrue(command.contains("astropy__astropy-12907"));
        assertFalse(command.contains("gold"));

        List<String> dockerCommand = SweBenchOfficialHarness.dockerEvaluationCommand(
                "devcli/swebench-harness:f7bbbb2", predictions,
                root.resolve("reports"), root.resolve("run"),
                "devcli-run", List.of("astropy__astropy-12907"), 1);
        assertEquals("docker", dockerCommand.get(0));
        assertTrue(dockerCommand.contains("devcli/swebench-harness:f7bbbb2"));
        assertTrue(dockerCommand.stream().anyMatch(value -> value.contains("docker.sock")));
    }

    @Test
    void validatesPinnedArtifactAndRejectsPathEscape(@TempDir Path root) throws Exception {
        Path configDir = root.resolve("Config");
        Path artifact = root.resolve("Data/raw/sample.json");
        Path harness = root.resolve("Data/raw/harness");
        Files.createDirectories(configDir);
        Files.createDirectories(artifact.getParent());
        Files.createDirectories(harness);
        Files.writeString(artifact, "sample", StandardCharsets.UTF_8);
        String hash = PublicBenchmarkCatalog.sha256(artifact);
        Files.writeString(configDir.resolve("public-benchmarks.json"), """
                {"schemaVersion":1,"updatedAt":"2026-07-16","datasets":[{
                "id":"sample","displayName":"Sample","category":"test",
                "datasetRepository":"owner/repo","datasetRevision":"abc","license":"MIT",
                "artifact":"Data/raw/sample.json","artifactSha256":"%s",
                "officialHarness":"Data/raw/harness","evaluationMode":"test"}]}
                """.formatted(hash), StandardCharsets.UTF_8);

        PublicBenchmarkCatalog.Catalog catalog = PublicBenchmarkCatalog.load(root);
        assertTrue(catalog.validate(catalog.require("sample")).ready());
        assertThrows(IllegalArgumentException.class,
                () -> catalog.resolveArtifact(new PublicBenchmarkCatalog.DatasetDescriptor(
                        "bad", "Bad", "test", "owner/repo", "abc", "MIT",
                        "../outside", null, hash, null, null, "Data/raw/harness", "test")));
    }
}
