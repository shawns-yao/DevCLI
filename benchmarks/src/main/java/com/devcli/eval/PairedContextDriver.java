package com.devcli.eval;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.OpenAiClient;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.TokenBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Single-attempt paired reader benchmark using the production history compactor. */
public final class PairedContextDriver {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int CHUNK_CHARS = 8_000;
    static final int RETAIN_TOKENS = 2_048;
    static final int TRIGGER_TOKENS = 8_192;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("PairedContextDriver <jobs.jsonl> <output-dir> <model>");
        }
        Path jobs = Path.of(args[0]);
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        String key = DevCliConfig.getEnvOrDotEnv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("OPENAI_API_KEY missing");
        LlmClient client = new OpenAiClient(key, args[2], DevCliConfig.getEnvOrDotEnv("OPENAI_BASE_URL"));
        try (var reader = Files.newBufferedReader(jobs, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode job = JSON.readTree(line);
                String id = job.required("id").asText();
                if (!id.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Unsafe job id");
                String fingerprint = hash(line + "\n" + args[2]);
                List<String> conditions = job.path("order").get(0).asText().equals("raw")
                        ? List.of("raw", "compact") : List.of("compact", "raw");
                for (String condition : conditions) {
                    Path dir = root.resolve(id).resolve(condition);
                    Files.createDirectories(dir);
                    Path result = dir.resolve("result.json");
                    if (Files.exists(result)) {
                        if (!JSON.readTree(result.toFile()).path("fingerprint").asText().equals(fingerprint)) {
                            throw new IllegalStateException("Run configuration changed: " + id);
                        }
                        continue;
                    }
                    Path started = dir.resolve("started.json");
                    if (Files.exists(started)) {
                        System.out.println("[interrupted-no-retry] " + id + " " + condition);
                        continue;
                    }
                    Files.writeString(started, JSON.writeValueAsString(java.util.Map.of(
                            "fingerprint", fingerprint, "started_at", Instant.now().toString())),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    ObjectNode record = run(job, condition, client, dir);
                    record.put("id", id);
                    record.put("condition", condition);
                    record.put("model", client.getModelName());
                    record.put("provider", client.getProviderName());
                    record.put("fingerprint", fingerprint);
                    record.put("finished_at", Instant.now().toString());
                    Path temporary = dir.resolve("result.tmp");
                    JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
                    Files.move(temporary, result, StandardCopyOption.ATOMIC_MOVE);
                    System.out.printf("[finished] %s %s status=%s input=%d summaryInput=%d%n",
                            id, condition, record.path("status").asText(),
                            record.path("answer_input_tokens").asLong(),
                            record.path("summary_input_tokens").asLong());
                }
            }
        }
    }

    static ObjectNode run(JsonNode job, String condition, LlmClient client, Path dir) throws IOException {
        ObjectNode record = JSON.createObjectNode();
        List<LlmClient.Message> history = history(job.required("prefix").asText(),
                job.required("context").asText(), job.required("suffix").asText());
        CountingClient counted = new CountingClient(client);
        long start = System.nanoTime();
        record.put("before_estimated_tokens", TokenBudget.estimateMessagesTokens(history));
        boolean changed = false;
        try {
            if (condition.equals("compact")) {
                ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(counted, RETAIN_TOKENS, true);
                compactor.setMicrocompactOutputRoot(dir);
                List<LlmClient.Message> before = List.copyOf(history);
                boolean summarized = compactor.compactIfNeeded(history, TRIGGER_TOKENS);
                changed = !before.equals(history);
                record.put("history_summarized", summarized);
                record.put("compactor_failures", compactor.getConsecutiveFailures());
            }
            record.put("summary_input_tokens", counted.input);
            record.put("summary_output_tokens", counted.output);
            record.put("summary_cached_tokens", counted.cached);
            record.put("summary_calls", counted.calls);
            record.put("summary_errors", counted.errors);
            record.put("summary_usage_complete", counted.usageComplete);
            record.put("compression_ms", (System.nanoTime() - start) / 1_000_000);
            record.put("after_estimated_tokens", TokenBudget.estimateMessagesTokens(history));
            record.put("context_changed", changed);
            JSON.writeValue(dir.resolve("messages.json").toFile(), history);
            long answerStart = System.nanoTime();
            LlmClient.ChatResponse answer = counted.chat(history, null);
            record.put("answer_ms", (System.nanoTime() - answerStart) / 1_000_000);
            record.put("prediction", answer.content() == null ? "" : answer.content());
            record.put("answer_input_tokens", answer.inputTokens());
            record.put("answer_output_tokens", answer.outputTokens());
            record.put("answer_cached_tokens", answer.cachedInputTokens());
            record.put("status", answer.content() == null || answer.content().isBlank() ? "empty" : "ok");
        } catch (Exception error) {
            // Exception text may contain a provider URL or request body. Persist only its type.
            record.put("status", "error");
            record.put("error_type", error.getClass().getSimpleName());
            record.put("prediction", "");
        }
        record.put("total_input_tokens", counted.input);
        record.put("total_output_tokens", counted.output);
        record.put("total_cached_tokens", counted.cached);
        record.put("calls", counted.calls);
        record.put("call_errors", counted.errors);
        record.put("usage_complete", counted.usageComplete);
        record.put("wall_ms", (System.nanoTime() - start) / 1_000_000);
        return record;
    }

    static List<LlmClient.Message> history(String prefix, String context, String suffix) {
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system("Read the supplied material and follow the final instruction. Do not use tools."));
        // Both conditions receive identical chunks. Only the treatment invokes the compactor.
        for (int offset = 0; offset < context.length();) {
            int end = Math.min(context.length(), offset + CHUNK_CHARS);
            if (end < context.length() && Character.isHighSurrogate(context.charAt(end - 1))) end--;
            messages.add(LlmClient.Message.user((offset == 0 ? prefix : "") + context.substring(offset, end)));
            messages.add(LlmClient.Message.assistant("OK."));
            offset = end;
        }
        if (context.isEmpty()) messages.add(LlmClient.Message.user(prefix));
        messages.add(LlmClient.Message.user(suffix));
        return messages;
    }

    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static final class CountingClient implements LlmClient {
        private final LlmClient delegate;
        long input, output, cached, calls, errors;
        boolean usageComplete = true;
        private long lastRequest;

        CountingClient(LlmClient delegate) { this.delegate = delegate; }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, null);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            long wait = 2_000 - (System.currentTimeMillis() - lastRequest);
            if (wait > 0) {
                try { Thread.sleep(wait); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", e); }
            }
            lastRequest = System.currentTimeMillis();
            calls++;
            try {
                ChatResponse response = delegate.chat(messages, tools, listener);
                input += response.inputTokens();
                output += response.outputTokens();
                cached += response.cachedInputTokens();
                usageComplete &= response.inputTokens() > 0;
                return response;
            } catch (IOException | RuntimeException e) {
                errors++;
                usageComplete = false;
                throw e;
            }
        }

        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
        @Override public int maxOutputTokens() { return delegate.maxOutputTokens(); }
    }
}
