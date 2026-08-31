package com.devcli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PublicBenchmarkDatasets {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PublicBenchmarkDatasets() {
    }

    static List<SweBenchCase> parseSweBenchRows(JsonNode payload, int limit) {
        List<SweBenchCase> cases = new ArrayList<>();
        for (JsonNode wrapper : payload.path("rows")) {
            JsonNode row = wrapper.path("row");
            if (row.isMissingNode() || row.path("instance_id").asText().isBlank()) {
                continue;
            }
            cases.add(new SweBenchCase(
                    row.path("instance_id").asText(),
                    row.path("repo").asText(),
                    row.path("base_commit").asText(),
                    row.path("problem_statement").asText(),
                    row.path("hints_text").asText(),
                    textOrJson(row.path("FAIL_TO_PASS")),
                    textOrJson(row.path("PASS_TO_PASS"))));
            if (cases.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return List.copyOf(cases);
    }

    static List<LongMemEvalCase> loadLongMemEval(Path file, int limit) throws IOException {
        JsonNode root = JSON.readTree(file.toFile());
        if (!root.isArray()) {
            throw new IOException("LongMemEval dataset must be a JSON array");
        }
        List<LongMemEvalCase> cases = new ArrayList<>();
        for (JsonNode row : root) {
            String id = row.path("question_id").asText();
            String question = row.path("question").asText();
            String answer = row.path("answer").asText();
            if (id.isBlank() || question.isBlank() || answer.isBlank()) {
                continue;
            }
            List<String> answerSessionIds = strings(row.path("answer_session_ids"));
            cases.add(new LongMemEvalCase(
                    id,
                    row.path("question_type").asText(),
                    question,
                    answer,
                    buildLongMemPrompt(row),
                    answerSessionIds,
                    row.path("haystack_sessions").size()));
            if (cases.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return List.copyOf(cases);
    }

    static List<LongBenchCase> loadLongBench(Path dataRoot, Path promptConfig,
                                             List<String> datasetNames,
                                             int limitPerDataset) throws IOException {
        JsonNode prompts = JSON.readTree(promptConfig.toFile());
        List<LongBenchCase> cases = new ArrayList<>();
        for (String datasetName : datasetNames) {
            String template = prompts.path(datasetName).asText();
            if (template.isBlank()) {
                throw new IOException("missing LongBench prompt template: " + datasetName);
            }
            Path file = dataRoot.resolve(datasetName + ".jsonl").normalize();
            if (!file.startsWith(dataRoot.normalize()) || !Files.isRegularFile(file)) {
                throw new IOException("missing LongBench dataset: " + datasetName);
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < Math.max(1, limitPerDataset)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode row = JSON.readTree(line);
                    String context = row.path("context").asText();
                    String input = row.path("input").asText();
                    List<String> answers = strings(row.path("answers"));
                    if (answers.isEmpty()) {
                        continue;
                    }
                    String prompt = template.replace("{context}", context).replace("{input}", input);
                    cases.add(new LongBenchCase(
                            row.path("_id").asText(datasetName + "-" + count),
                            datasetName,
                            prompt,
                            answers,
                            row.path("length").asInt(),
                            metricForLongBench(datasetName)));
                    count++;
                }
            }
        }
        return List.copyOf(cases);
    }

    static List<RulerCase> loadRuler(Path jsonl, int limit) throws IOException {
        List<RulerCase> cases = new ArrayList<>();
        String defaultTask = jsonl.getParent() == null
                ? "unknown"
                : jsonl.getParent().getFileName().toString();
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null && cases.size() < Math.max(1, limit)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = JSON.readTree(line);
                String prompt = row.path("input").asText(row.path("prompt").asText());
                List<String> outputs = strings(row.path("outputs"));
                if (outputs.isEmpty() && !row.path("answer").asText().isBlank()) {
                    outputs = List.of(row.path("answer").asText());
                }
                if (!prompt.isBlank() && !outputs.isEmpty()) {
                    cases.add(new RulerCase(
                            row.path("index").asText("ruler-" + index),
                            row.path("task").asText(row.path("task_name").asText(defaultTask)),
                            prompt,
                            outputs,
                            row.path("length").asInt()));
                }
                index++;
            }
        }
        return List.copyOf(cases);
    }

    static Path findLongBenchPromptConfig(Path harnessRoot) throws IOException {
        try (var stream = Files.walk(harnessRoot, 4)) {
            return stream.filter(path -> path.getFileName().toString().equals("dataset2prompt.json"))
                    .filter(path -> path.toString().replace('\\', '/').contains("LongBench/config"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("LongBench dataset2prompt.json not found under " + harnessRoot));
        }
    }

    private static String buildLongMemPrompt(JsonNode row) {
        StringBuilder prompt = new StringBuilder("Use only the conversation history below. Answer the final question briefly and factually.\n\n");
        JsonNode dates = row.path("haystack_dates");
        JsonNode sessions = row.path("haystack_sessions");
        for (int i = 0; i < sessions.size(); i++) {
            String date = i < dates.size() ? dates.get(i).asText() : "";
            prompt.append("[Session ").append(i + 1);
            if (!date.isBlank()) {
                prompt.append(" | ").append(date);
            }
            prompt.append("]\n");
            for (JsonNode turn : sessions.get(i)) {
                prompt.append(turn.path("role").asText("unknown").toUpperCase(Locale.ROOT))
                        .append(": ")
                        .append(turn.path("content").asText())
                        .append('\n');
            }
            prompt.append('\n');
        }
        prompt.append("Question: ").append(row.path("question").asText()).append("\nAnswer:");
        return prompt.toString();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (value.isBlank()) {
                return List.of();
            }
            try {
                JsonNode parsed = JSON.readTree(value);
                if (parsed.isArray()) {
                    return strings(parsed);
                }
            } catch (Exception ignored) {
                // Plain string answer.
            }
            return List.of(value);
        }
        if (!node.isArray()) {
            return List.of(node.asText());
        }
        List<String> values = new ArrayList<>();
        Iterator<JsonNode> iterator = node.elements();
        while (iterator.hasNext()) {
            String value = iterator.next().asText();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String textOrJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String metricForLongBench(String datasetName) {
        return switch (datasetName) {
            case "passage_count", "passage_retrieval_en", "trec", "lsht" -> "exact-match";
            case "gov_report", "multi_news", "samsum", "vcsum" -> "rouge";
            case "lcc", "repobench-p" -> "code-similarity";
            default -> "qa-f1";
        };
    }

    record SweBenchCase(String instanceId, String repo, String baseCommit,
                        String problemStatement, String hintsText,
                        String failToPass, String passToPass) {
    }

    record LongMemEvalCase(String questionId, String questionType, String question,
                           String answer, String prompt, List<String> answerSessionIds,
                           int sessionCount) {
    }

    record LongBenchCase(String id, String dataset, String prompt,
                         List<String> answers, int length, String metric) {
    }

    record RulerCase(String id, String task, String prompt,
                     List<String> answers, int length) {
    }
}
