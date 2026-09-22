package com.devcli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;

/** Strict, versioned representation for summaries before Markdown rendering. */
public record CompactionSummaryEnvelope(
        int schemaVersion,
        String requestIntent,
        List<SummaryItem> concepts,
        List<SummaryItem> files,
        List<SummaryItem> pitfalls,
        List<SummaryItem> resolutionSteps,
        List<SummaryItem> userMessages,
        List<CompactionFactLedger.Fact> protectedFacts) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("schema_version", "request_intent",
            "concepts", "files", "pitfalls", "resolution_steps", "user_messages", "protected_facts");

    public CompactionSummaryEnvelope {
        concepts = clean(concepts); files = clean(files); pitfalls = clean(pitfalls);
        resolutionSteps = clean(resolutionSteps); userMessages = clean(userMessages);
        protectedFacts = protectedFacts == null ? List.of() : List.copyOf(protectedFacts);
        requestIntent = requestIntent == null ? "" : requestIntent.trim();
    }

    public static CompactionSummaryEnvelope parse(String text) {
        try {
            if (text == null || text.length() > 100_000) return null;
            JsonNode n = JSON.readTree(text);
            if (n == null || !n.isObject() || n.size() != FIELDS.size()) return null;
            for (String field : FIELDS) if (!n.has(field)) return null;
            if (!n.get("schema_version").isIntegralNumber()
                    || !n.get("schema_version").canConvertToInt()) return null;
            int version = n.get("schema_version").intValue();
            if (version != 2 || !n.get("request_intent").isTextual()
                    || n.get("request_intent").asText().isBlank()) return null;
            JsonNode protectedNode = n.get("protected_facts");
            if (!protectedNode.isArray() || protectedNode.size() > 256) return null;
            List<CompactionFactLedger.Fact> facts = new ArrayList<>();
            Set<String> ids = new java.util.HashSet<>();
            for (JsonNode fact : protectedNode) {
                if (!fact.isObject()) return null;
                Set<String> legacyFields = Set.of("id", "type", "value", "sourceId");
                Set<String> currentFields = Set.of("id", "type", "value", "source_message_id",
                        "source_tool_call_id", "status", "sequence", "context_epoch");
                boolean legacy = fact.size() == legacyFields.size()
                        && fact.fieldNames().hasNext();
                Set<String> expected = legacy ? legacyFields : currentFields;
                if (fact.size() != expected.size()) return null;
                java.util.Iterator<String> names = fact.fieldNames();
                while (names.hasNext()) if (!expected.contains(names.next())) return null;
                for (String field : legacy ? List.of("id", "type", "value", "sourceId")
                        : List.of("id", "type", "value", "source_message_id",
                        "source_tool_call_id", "status")) {
                    if (!fact.has(field) || !fact.get(field).isTextual()
                            || (!field.equals("source_tool_call_id") && fact.get(field).asText().isBlank())
                            || fact.get(field).asText().length() > 8_000) return null;
                }
                if (!legacy && (!fact.get("sequence").isIntegralNumber()
                        || !fact.get("context_epoch").isIntegralNumber()
                        || !fact.get("sequence").canConvertToLong()
                        || !fact.get("context_epoch").canConvertToLong()
                        || fact.get("sequence").asLong() < 0
                        || fact.get("context_epoch").asLong() < 0)) return null;
                if (!ids.add(fact.get("id").asText())) return null;
                try {
                    if (legacy) {
                        facts.add(new CompactionFactLedger.Fact(fact.get("id").asText(),
                                CompactionFactLedger.Type.valueOf(fact.get("type").asText()),
                                fact.get("value").asText(), fact.get("sourceId").asText()));
                    } else {
                        facts.add(new CompactionFactLedger.Fact(fact.get("id").asText(),
                                CompactionFactLedger.Type.valueOf(fact.get("type").asText()),
                                fact.get("value").asText(), fact.get("source_message_id").asText(),
                                fact.get("source_tool_call_id").asText(),
                                CompactionFactLedger.FactStatus.valueOf(fact.get("status").asText()),
                                fact.get("sequence").asLong(), fact.get("context_epoch").asLong()));
                    }
                } catch (IllegalArgumentException invalidFact) {
                    return null;
                }
            }
            return new CompactionSummaryEnvelope(version, n.path("request_intent").asText(""),
                    items(n, "concepts", "关键技术概念"), items(n, "files", "文件和代码"),
                    items(n, "pitfalls", "踩过的坑和修复"), items(n, "resolution_steps", "问题解决过程"),
                    items(n, "user_messages", "逐条用户消息"), facts);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean isValid() {
        return schemaVersion == 2 && !requestIntent.isBlank();
    }

    /** Render the structured object only after it has passed strict parsing. */
    public String renderMarkdown() {
        RollingSummary summary = new RollingSummary();
        summary.set("主要请求与意图", requestIntent);
        concepts.forEach(summary::addItem);
        files.forEach(summary::addItem);
        pitfalls.forEach(summary::addItem);
        resolutionSteps.forEach(summary::addItem);
        userMessages.forEach(summary::addItem);
        return summary.render();
    }

    private static List<SummaryItem> items(JsonNode node, String name, String section) {
        List<SummaryItem> values = new ArrayList<>();
        JsonNode array = node.get(name);
        if (!array.isArray() || array.size() > 256) throw new IllegalArgumentException(name);
        int index = 0;
        for (JsonNode value : array) {
            if (value.isTextual()) {
                if (value.asText().isBlank() || value.asText().length() > 8_000)
                    throw new IllegalArgumentException(name);
                values.add(SummaryItem.create(section, "model:" + index++, value.asText(),
                        defaultLifecycle(section), defaultImportance(section),
                        List.of()));
                continue;
            }
            if (!value.isObject()) throw new IllegalArgumentException(name);
            values.add(parseItem(value, section, name));
        }
        return values;
    }

    private static SummaryItem parseItem(JsonNode node, String section, String name) {
        Set<String> fields = Set.of("id", "subject", "content", "lifecycle", "importance",
                "revision", "compaction_count", "superseded_by", "evidence_refs");
        if (node.size() != fields.size()) throw new IllegalArgumentException(name);
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw new IllegalArgumentException(name);
        for (String field : List.of("id", "subject", "content", "lifecycle", "superseded_by")) {
            if (!node.has(field) || !node.get(field).isTextual()) throw new IllegalArgumentException(name);
            if (node.get(field).asText().length() > 8_000
                    || (!field.equals("superseded_by") && node.get(field).asText().isBlank()))
                throw new IllegalArgumentException(name);
        }
        if (!node.has("importance") || !node.has("revision") || !node.has("compaction_count")
                || !node.get("importance").isIntegralNumber() || !node.get("revision").isIntegralNumber()
                || !node.get("compaction_count").isIntegralNumber()
                || !node.get("importance").canConvertToInt() || !node.get("revision").canConvertToInt()
                || !node.get("compaction_count").canConvertToInt()) throw new IllegalArgumentException(name);
        if (node.get("importance").asInt() < 0 || node.get("importance").asInt() > 100
                || node.get("revision").asInt() < 1 || node.get("compaction_count").asInt() < 0)
            throw new IllegalArgumentException(name);
        JsonNode refs = node.get("evidence_refs");
        if (!refs.isArray() || refs.size() > 32) throw new IllegalArgumentException(name);
        List<String> evidenceRefs = new ArrayList<>();
        for (JsonNode ref : refs) {
            if (!ref.isTextual() || ref.asText().isBlank() || ref.asText().length() > 2_000)
                throw new IllegalArgumentException(name);
            evidenceRefs.add(ref.asText());
        }
        try {
            return new SummaryItem(node.get("id").asText(), section, node.get("subject").asText(),
                    node.get("content").asText(), SummaryItem.Lifecycle.valueOf(node.get("lifecycle").asText()),
                    node.get("importance").asInt(), node.get("revision").asInt(),
                    node.get("compaction_count").asInt(), node.get("superseded_by").asText(), evidenceRefs);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name, e);
        }
    }

    private static int defaultImportance(String section) {
        return switch (section) {
            case "文件和代码", "踩过的坑和修复", "关键技术概念" -> 70;
            case "主要请求与意图" -> 90;
            default -> 50;
        };
    }

    private static SummaryItem.Lifecycle defaultLifecycle(String section) {
        return switch (section) {
            case "主要请求与意图", "关键技术概念" -> SummaryItem.Lifecycle.STABLE;
            case "逐条用户消息" -> SummaryItem.Lifecycle.ACTIVE;
            default -> SummaryItem.Lifecycle.ACTIVE;
        };
    }

    private static List<SummaryItem> clean(List<SummaryItem> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null).toList();
    }
}
