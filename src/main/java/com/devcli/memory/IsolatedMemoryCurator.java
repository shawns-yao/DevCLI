package com.devcli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.llm.LlmClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 全隔离记忆整理器：单次 LLM 调用、空工具列表、无历史记忆、无 Skill/MCP/文件/命令入口。
 */
public final class IsolatedMemoryCurator implements MemoryCurator {
    private static final ObjectMapper JSON = MemoryJson.mapper();
    private static final String SYSTEM_PROMPT = """
            你是一次性长期记忆筛选器，只能依据本次用户消息、任务结果、任务状态和证据摘要判断。
            你看不到也不得假设任何旧记忆。不要执行工具，不要请求联网，不要补充外部事实。
            只输出一个 JSON 对象，不要 Markdown：
            {"action":"SAVE|CONFIRM|SKIP","kind":"FACT|PREFERENCE|PROCEDURE|LESSON|DECISION",
             "content":"可跨任务复用的最小事实","scope_type":"GLOBAL|USER|PROJECT|REPOSITORY|SYMBOL",
             "scope_key":"作用域键","confidence":"HIGH|MEDIUM|LOW",
             "source_refs":["request|result|state:key|evidence:index"],"reason":"简短原因"}
            临时进度、一次性输出、未验证猜测和秘密必须 SKIP；不确定或涉及敏感偏好时 CONFIRM。
            """;

    private final LlmClient llmClient;

    public IsolatedMemoryCurator(LlmClient llmClient) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
    }

    @Override
    public Decision curate(TaskMemorySnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        String payload = JSON.writeValueAsString(snapshot);
        LlmClient.ChatResponse response = llmClient.chat(List.of(
                LlmClient.Message.system(SYSTEM_PROMPT),
                LlmClient.Message.user(payload)), List.of());
        String content = response == null ? "" : response.content();
        if (content == null || content.isBlank()) {
            throw new IOException("memory curator returned empty response");
        }
        JsonNode root;
        try {
            root = JSON.readTree(content.trim());
        } catch (Exception error) {
            throw new IOException("memory curator returned invalid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("memory curator response must be one JSON object");
        }
        Action action = parseEnum(Action.class, root.path("action").asText(), null);
        if (action == null) throw new IOException("memory curator action is invalid");
        List<String> sourceRefs = new ArrayList<>();
        root.path("source_refs").forEach(node -> {
            if (node.isTextual() && !node.asText().isBlank()) sourceRefs.add(node.asText().trim());
        });
        Decision decision = new Decision(action,
                normalized(root, "kind"), normalized(root, "content"),
                normalized(root, "scope_type"), normalized(root, "scope_key"),
                normalized(root, "confidence"), sourceRefs, normalized(root, "reason"));
        if (action != Action.SKIP && (decision.content().isBlank() || decision.sourceRefs().isEmpty())) {
            throw new IOException("memory curator save/confirm decision lacks content or source_refs");
        }
        if (action != Action.SKIP) {
            validateRetainedDecision(snapshot, decision);
        }
        return decision;
    }

    private static void validateRetainedDecision(TaskMemorySnapshot snapshot,
                                                  Decision decision) throws IOException {
        if (!List.of("FACT", "PREFERENCE", "PROCEDURE", "LESSON", "DECISION")
                .contains(decision.kind())) {
            throw new IOException("memory curator kind is invalid");
        }
        if (!List.of("GLOBAL", "USER", "PROJECT", "REPOSITORY", "SYMBOL")
                .contains(decision.scopeType())) {
            throw new IOException("memory curator scope_type is invalid");
        }
        if (!List.of("HIGH", "MEDIUM", "LOW").contains(decision.confidence())) {
            throw new IOException("memory curator confidence is invalid");
        }
        if (decision.content().length() > 4_000) {
            throw new IOException("memory curator content exceeds durable limit");
        }
        if (decision.sourceRefs().stream().anyMatch(ref -> !validSourceRef(snapshot, ref))) {
            throw new IOException("memory curator source_refs contain unknown provenance");
        }
    }

    private static boolean validSourceRef(TaskMemorySnapshot snapshot, String reference) {
        if ("request".equals(reference) || "result".equals(reference)) return true;
        if (reference.startsWith("state:")) {
            return snapshot.workState().containsKey(reference.substring("state:".length()));
        }
        if (!reference.startsWith("evidence:")) return false;
        try {
            int index = Integer.parseInt(reference.substring("evidence:".length()));
            int size = snapshot.evidenceSummaries().size();
            return (index >= 0 && index < size) || (index >= 1 && index <= size);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String normalized(JsonNode root, String field) {
        return root.path(field).asText("").trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public enum Action { SAVE, CONFIRM, SKIP }

    public record Decision(Action action, String kind, String content,
                           String scopeType, String scopeKey, String confidence,
                           List<String> sourceRefs, String reason) {
        public Decision {
            action = action == null ? Action.SKIP : action;
            kind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
            content = content == null ? "" : content.trim();
            scopeType = scopeType == null ? "" : scopeType.trim().toUpperCase(Locale.ROOT);
            scopeKey = scopeKey == null ? "" : scopeKey.trim();
            confidence = confidence == null ? "" : confidence.trim().toUpperCase(Locale.ROOT);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
            reason = reason == null ? "" : reason.trim();
        }
    }
}
