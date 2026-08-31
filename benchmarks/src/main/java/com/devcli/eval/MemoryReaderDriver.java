package com.devcli.eval;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.OpenAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Reader ablation over contexts already produced by MemoryEvidenceDriver. */
public final class MemoryReaderDriver {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("MemoryReaderDriver <jobs> <results> <model>");
        String key = DevCliConfig.getEnvOrDotEnv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("OPENAI_API_KEY missing");
        var client = new PairedContextDriver.CountingClient(new OpenAiClient(key, args[2],
                DevCliConfig.getEnvOrDotEnv("OPENAI_BASE_URL")));
        Path root = Path.of(args[1]);
        int index = 0;
        try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var job = JSON.readTree(line);
                String id = job.required("id").asText();
                if (!id.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Unsafe id");
                Path dir = root.resolve(id);
                var retrieval = JSON.readTree(dir.resolve("retrieval.json").toFile());
                if (!retrieval.path("fingerprint").asText().equals(PairedContextDriver.hash(line))) {
                    throw new IllegalStateException("Memory retrieval input mismatch");
                }
                var order = index++ % 2 == 0 ? List.of("recency", "memory") : List.of("memory", "recency");
                for (String condition : order) {
                    Path output = dir.resolve(condition + "-answer.json");
                    String context = retrieval.required(condition + "_context").asText();
                    String fingerprint = PairedContextDriver.hash(line + context + args[2]);
                    if (Files.exists(output)) {
                        if (!JSON.readTree(output.toFile()).path("fingerprint").asText().equals(fingerprint)) {
                            throw new IllegalStateException("Reader input mismatch");
                        }
                        continue;
                    }
                    Path started = dir.resolve(condition + "-answer.started");
                    if (Files.exists(started)) {
                        System.out.println("[interrupted-no-retry] " + id + " " + condition);
                        continue;
                    }
                    Files.writeString(started, fingerprint, StandardOpenOption.CREATE_NEW);
                    ObjectNode result = JSON.createObjectNode();
                    result.put("id", id).put("condition", condition).put("model", args[2]).put("fingerprint", fingerprint);
                    List<LlmClient.Message> messages = List.of(
                            LlmClient.Message.system("Answer the question using the supplied dated conversation records. "
                                    + "Use the most recent applicable information. If the records do not contain enough "
                                    + "information, say so. Answer concisely; do not explain your reasoning."),
                            LlmClient.Message.user("Conversation records:\n" + context + "\n\nQuestion date: "
                                    + job.required("question_date").asText() + "\nQuestion: " + job.required("question").asText()));
                    long begin = System.nanoTime();
                    try {
                        var response = client.chat(messages, null);
                        result.put("hypothesis", response.content() == null ? "" : response.content());
                        result.put("input_tokens", response.inputTokens());
                        result.put("output_tokens", response.outputTokens());
                        result.put("cached_tokens", response.cachedInputTokens());
                        result.put("usage_complete", response.inputTokens() > 0);
                        result.put("status", response.content() == null || response.content().isBlank() ? "empty" : "ok");
                    } catch (Exception error) {
                        result.put("status", "error").put("error_type", error.getClass().getSimpleName());
                        result.put("hypothesis", "").put("usage_complete", false);
                    }
                    result.put("wall_ms", (System.nanoTime() - begin) / 1_000_000);
                    Path temporary = dir.resolve(condition + "-answer.tmp");
                    JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), result);
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
                    System.out.printf("[answered] %s %s status=%s%n", id, condition, result.path("status").asText());
                }
            }
        }
    }
}
