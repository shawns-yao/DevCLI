package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBenchmarkReadinessIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesPinnedPublicDatasetsAndOfficialHarnesses() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("devcli.benchmark.public.datasets"),
                "set -Ddevcli.benchmark.public.datasets=true after downloading public datasets");
        Path root = Path.of("").toAbsolutePath().normalize();
        PublicBenchmarkCatalog.Catalog catalog = PublicBenchmarkCatalog.load(root);
        int sampleLimit = Math.max(1, Integer.getInteger("devcli.benchmark.public.sample.limit", 5));

        ObjectNode report = JSON.createObjectNode();
        report.put("schema_version", 1);
        report.put("generated_at", Instant.now().toString());
        report.put("sample_limit", sampleLimit);
        ArrayNode datasets = report.putArray("datasets");

        for (PublicBenchmarkCatalog.DatasetDescriptor descriptor : catalog.datasets()) {
            PublicBenchmarkCatalog.DatasetValidation validation = catalog.validate(descriptor);
            ObjectNode item = datasets.addObject();
            item.put("id", descriptor.id());
            item.put("repository", descriptor.datasetRepository());
            item.put("revision", descriptor.datasetRevision());
            item.put("license", descriptor.license());
            item.put("evaluation_mode", descriptor.evaluationMode());
            item.put("artifact_bytes", validation.bytes());
            item.put("sha256_matches", validation.hashMatches());
            item.put("official_harness_present", validation.harnessPresent());
            item.put("ready", validation.ready());
            item.put("message", validation.message());
            assertTrue(validation.ready(), descriptor.id() + ": " + validation.message());
        }

        int sweCount = loadSweBenchSample(catalog.require("swebench-lite"), catalog, sampleLimit).size();
        int longMemCount = PublicBenchmarkDatasets.loadLongMemEval(
                catalog.resolveArtifact(catalog.require("longmemeval-oracle")), sampleLimit).size();
        PublicBenchmarkCatalog.DatasetDescriptor longBench = catalog.require("longbench-v1");
        Path promptConfig = PublicBenchmarkDatasets.findLongBenchPromptConfig(catalog.resolveHarness(longBench));
        int longBenchCount = PublicBenchmarkDatasets.loadLongBench(
                catalog.resolveExtractedRoot(longBench), promptConfig,
                List.of("passage_count", "passage_retrieval_en", "multifieldqa_en", "qasper"),
                sampleLimit).size();

        report.putObject("sample_validation")
                .put("swebench_lite", sweCount)
                .put("longmemeval_oracle", longMemCount)
                .put("longbench_v1", longBenchCount)
                .put("ruler_v1", "official NeMo-Skills harness pinned; generated data requires model tokenizer");

        assertEquals(sampleLimit, sweCount);
        assertEquals(sampleLimit, longMemCount);
        assertEquals(sampleLimit * 4, longBenchCount);

        Path output = root.resolve("target/benchmark-reports/public/public-dataset-readiness.json");
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static List<PublicBenchmarkDatasets.SweBenchCase> loadSweBenchSample(
            PublicBenchmarkCatalog.DatasetDescriptor descriptor,
            PublicBenchmarkCatalog.Catalog catalog,
            int limit) throws Exception {
        Path sample = catalog.projectRoot().resolve(
                "Data/raw/public-benchmarks/swebench-lite/sample-" + limit + ".json");
        if (!Files.isRegularFile(sample)) {
            String url = descriptor.sampleUrl().replace("{limit}", Integer.toString(limit));
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "DevCLI-benchmark")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("SWE-bench sample request failed: HTTP " + response.statusCode());
            }
            Files.createDirectories(sample.getParent());
            Files.writeString(sample, response.body(), StandardCharsets.UTF_8);
        }
        JsonNode payload = JSON.readTree(sample.toFile());
        return PublicBenchmarkDatasets.parseSweBenchRows(payload, limit);
    }
}
