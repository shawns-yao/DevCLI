package com.devcli.benchmark;

import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryScope;
import com.devcli.rag.CodeChunk;
import com.devcli.rag.VectorStore;
import com.devcli.runtime.api.RunEventJsonCodec;
import com.devcli.event.RunEvent;
import com.devcli.workspace.ContextVersionLedger;
import com.devcli.workspace.WriteGateResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定 JSON 基线门禁，不依赖真实模型。 */
class ProtocolBaselineGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsEqualOrImprovedMetricsAndRejectsRegression(@TempDir Path tempDir) throws Exception {
        Path baseline = tempDir.resolve("baseline.json");
        Path current = tempDir.resolve("current.json");
        Files.writeString(baseline, """
                {"context":{"refresh_success_rate":1.0},"memory":{"single_active_rate":1.0}}
                """);
        Files.writeString(current, """
                {"context":{"refresh_success_rate":1.0},"memory":{"single_active_rate":1.0}}
                """);
        assertTrue(compare(baseline, current).isEmpty());

        Files.writeString(current, """
                {"context":{"refresh_success_rate":0.9},"memory":{"single_active_rate":1.0}}
                """);
        assertThrows(IllegalStateException.class, () -> requireNoRegression(baseline, current));
    }

    @Test
    void deterministicProtocolSimulatorMeetsRepositoryBaseline(@TempDir Path tempDir) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path baseline = root.resolve("Config/protocol-regression-baseline.json");
        Path report = root.resolve("target/benchmark-reports/protocol-regression.json");
        Files.createDirectories(report.getParent());

        JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), simulate(tempDir));

        requireNoRegression(baseline, report);
    }

    private static Map<String, Object> simulate(Path tempDir) throws Exception {
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("schema_version", 1);
        metrics.put("context", simulateContext(tempDir.resolve("context")));
        metrics.put("index", simulateIndex(tempDir.resolve("index")));
        metrics.put("memory", simulateMemory(tempDir.resolve("memory")));
        metrics.put("runtime", simulateRuntimeEvent());
        return metrics;
    }

    private static Map<String, Double> simulateContext(Path project) throws Exception {
        Files.createDirectories(project);
        Path service = project.resolve("Service.java");
        Path caller = project.resolve("Caller.java");
        Files.writeString(service, "class Service { int value() { return 1; } }\n");
        Files.writeString(caller, "class Caller {}\n");
        ContextVersionLedger ledger = new ContextVersionLedger();
        ledger.recordRead("worker-a", "Service.java", service, Files.readString(service));
        Files.writeString(service, "class Service { int value() { return 2; } }\n");
        ledger.publishWrite("worker-b", "Service.java", service, Files.readString(service));

        WriteGateResult stale = ledger.validateWrite("worker-a", "Caller.java", caller,
                Files.readString(caller), project);
        Map<String, String> refreshed = ledger.refreshPending("worker-a", project);
        return Map.of(
                "stale_detection_rate", stale.isAllowed() ? 0.0 : 1.0,
                "refresh_success_rate", refreshed.getOrDefault("Service.java", "")
                        .contains("return 2") ? 1.0 : 0.0);
    }

    private static Map<String, Double> simulateIndex(Path project) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("README.md"), "new index content\n");
        try (VectorStore store = new VectorStore(project.toString())) {
            CodeChunk oldChunk = CodeChunk.fileChunk("README.md", "old index content");
            CodeChunk newChunk = CodeChunk.fileChunk("README.md", "new index content");
            store.replaceProjectIndex(List.of(
                    new VectorStore.CodeChunkEntry(oldChunk, new float[]{1.0f})), List.of(), "idx-1");
            boolean staleRejected;
            boolean dirtyVisible;
            try (VectorStore.ShadowIndexSession shadow = store.beginShadowIndex(
                    "idx-2", List.of("README.md"), VectorStore.ShadowIndexMode.INCREMENTAL)) {
                dirtyVisible = store.searchByKeyword("old").getFirst().freshness()
                        == VectorStore.IndexFreshness.DIRTY;
                shadow.stageChunks(List.of(
                        new VectorStore.CodeChunkEntry(newChunk, new float[]{1.0f})));
                shadow.stageRelations(List.of());
                shadow.validate();
                store.markDirtyFiles(List.of("README.md"));
                staleRejected = !shadow.promote();
            }
            return Map.of(
                    "stale_cas_rejection_rate", staleRejected ? 1.0 : 0.0,
                    "freshness_visibility_rate", dirtyVisible ? 1.0 : 0.0);
        }
    }

    private static Map<String, Double> simulateMemory(Path storage) throws Exception {
        try (LongTermMemory memory = new LongTermMemory(storage.toFile())) {
            LongTermMemory.SaveResult original = memory.save(MemoryScope.GLOBAL,
                    "server-port", "server port", "reference",
                    "server.port=8080", null, true);
            LongTermMemory.SaveResult automatic = memory.save(MemoryScope.GLOBAL,
                    "server-port", "server port", "reference",
                    "server.port=8443", null, false);
            boolean isolated = original.stored()
                    && automatic.status() == LongTermMemory.SaveStatus.CONFLICT
                    && memory.retrieve(original.fileName()).orElseThrow().getContent()
                    .contains("8080");
            LongTermMemory.SaveResult confirmed = memory.save(MemoryScope.GLOBAL,
                    "server-port", "server port", "reference",
                    "server.port=8443", null, true);
            long active = memory.getAll().size();
            return Map.of(
                    "pending_isolation_rate", isolated ? 1.0 : 0.0,
                    "single_active_rate", confirmed.status() == LongTermMemory.SaveStatus.UPDATED
                            && active == 1 ? 1.0 : 0.0);
        }
    }

    private static Map<String, Double> simulateRuntimeEvent() throws Exception {
        RunEvent.ContextRefresh refresh = new RunEvent.ContextRefresh(
                "worker-a", RunEvent.ContextRefreshState.RUNNING,
                List.of("Service.java"), "refreshed");
        String encoded = RunEventJsonCodec.encode(refresh, "turn-1");
        JsonNode event = JSON.readTree(encoded);
        boolean typed = "context.refresh".equals(refresh.type())
                && "RUNNING".equals(event.path("state").asText())
                && "Service.java".equals(event.path("resources").get(0).asText());
        return Map.of("typed_refresh_event_rate", typed ? 1.0 : 0.0);
    }

    static void requireNoRegression(Path baseline, Path current) throws Exception {
        List<String> regressions = compare(baseline, current);
        if (!regressions.isEmpty()) throw new IllegalStateException(String.join("; ", regressions));
    }

    private static List<String> compare(Path baseline, Path current) throws Exception {
        Map<String, Double> expected = flatten(JSON.readTree(baseline.toFile()), "");
        Map<String, Double> actual = flatten(JSON.readTree(current.toFile()), "");
        List<String> regressions = new ArrayList<>();
        expected.forEach((metric, value) -> {
            Double observed = actual.get(metric);
            if (observed == null || observed + 1e-9 < value) {
                regressions.add(metric + ": expected >= " + value + ", actual=" + observed);
            }
        });
        return regressions;
    }

    private static Map<String, Double> flatten(JsonNode node, String prefix) {
        Map<String, Double> values = new java.util.LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = prefix.isBlank() ? field.getKey() : prefix + "." + field.getKey();
            if (field.getValue().isNumber()) values.put(key, field.getValue().asDouble());
            else if (field.getValue().isObject()) values.putAll(flatten(field.getValue(), key));
        }
        return values;
    }
}
