package com.devcli.eval;

import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryEntry;
import com.devcli.memory.MemoryManager;
import com.devcli.policy.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Frozen session-import retrieval experiment, without gold labels or model calls. */
public final class MemoryEvidenceDriver {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int BUDGET = 16_384;
    static final int K = 5;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("MemoryEvidenceDriver <jobs.jsonl> <out>");
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode job = JSON.readTree(line);
                String id = job.required("id").asText();
                if (!id.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Unsafe id");
                Path dir = root.resolve(id);
                Path result = dir.resolve("retrieval.json");
                String fingerprint = PairedContextDriver.hash(line);
                if (Files.exists(result)) {
                    if (!JSON.readTree(result.toFile()).path("fingerprint").asText().equals(fingerprint)) {
                        throw new IllegalStateException("Changed memory input: " + id);
                    }
                    continue;
                }
                Files.createDirectories(dir);
                // A new isolated database per case. Never opens the user's default memory directory.
                Path storage = Files.createTempDirectory(dir, "memory-");
                ObjectNode record = retrieve(job, storage);
                record.put("fingerprint", fingerprint);
                Path pending = dir.resolve("retrieval.tmp");
                JSON.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(), record);
                Files.move(pending, result, StandardCopyOption.ATOMIC_MOVE);
                System.out.printf("[retrieved] %s candidates=%d selected=%d injected=%d%n", id,
                        record.path("candidate_sessions").asInt(), record.path("ranked_ids").size(), record.path("injected_ids").size());
            }
        }
    }

    static ObjectNode retrieve(JsonNode job, Path storage) throws Exception {
        long start = System.nanoTime();
        List<MemoryEntry> sessions = new ArrayList<>();
        try (MemoryManager manager = new MemoryManager(null, 4096, 128_000, new LongTermMemory(storage.toFile()))) {
            Instant anchor = Instant.parse(job.required("clock_anchor").asText());
            for (JsonNode session : job.required("sessions")) {
                String content = session.required("content").asText();
                String sessionId = session.required("id").asText();
                // Shift source dates uniformly relative to the query, preserving age/order.
                // The original date remains in the content presented to the reader.
                Instant timestamp = anchor.minusSeconds(session.required("age_seconds").asLong());
                MemoryEntry entry = new MemoryEntry(sessionId, content, MemoryEntry.MemoryType.FACT,
                        timestamp, Map.of("source", "benchmark_session_import"), MemoryEntry.estimateTokens(content));
                manager.getLongTermMemory().store(entry);
                sessions.add(entry);
            }
            long ingested = System.nanoTime();
            String query = job.required("question").asText();
            List<MemoryEntry> ranked = manager.retrieveRelevant(query, K);
            long rankedAt = System.nanoTime();
            String context = manager.buildContextForQuery(query, BUDGET);
            List<String> injected = manager.getLongTermMemory().getAll().stream()
                    .filter(entry -> entry.getRecallCount() > 0).map(MemoryEntry::getId).sorted().toList();
            List<MemoryEntry> recent = sessions.stream().sorted(Comparator.comparing(MemoryEntry::getTimestamp).reversed())
                    .limit(K).toList();
            List<String> recentInjected = new ArrayList<>();
            StringBuilder recentContext = new StringBuilder();
            int used = 0;
            for (MemoryEntry entry : recent) {
                String content = SensitiveDataRedactor.redact(entry.getContent());
                int tokens = MemoryEntry.estimateTokens(content);
                if (used + tokens > BUDGET) break;
                recentContext.append(content).append('\n');
                recentInjected.add(entry.getId());
                used += tokens;
            }
            ObjectNode result = JSON.createObjectNode();
            result.put("id", job.path("id").asText());
            result.put("mode", "production-keyword-session-import");
            result.put("candidate_sessions", sessions.size());
            result.put("k", K);
            result.put("budget_estimated_tokens", BUDGET);
            result.set("ranked_ids", JSON.valueToTree(ranked.stream().map(MemoryEntry::getId).toList()));
            result.set("injected_ids", JSON.valueToTree(injected));
            result.set("recency_ranked_ids", JSON.valueToTree(recent.stream().map(MemoryEntry::getId).toList()));
            result.set("recency_injected_ids", JSON.valueToTree(recentInjected));
            result.put("memory_context", context);
            result.put("recency_context", recentContext.toString());
            result.put("memory_context_estimated_tokens", MemoryEntry.estimateTokens(context));
            result.put("recency_context_estimated_tokens", used);
            result.put("ingest_ms", (ingested - start) / 1_000_000.0);
            result.put("retrieve_ms", (rankedAt - ingested) / 1_000_000.0);
            result.put("total_ms", (System.nanoTime() - start) / 1_000_000.0);
            return result;
        }
    }
}
