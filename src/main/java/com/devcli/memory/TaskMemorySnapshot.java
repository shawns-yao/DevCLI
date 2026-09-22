package com.devcli.memory;

import com.devcli.policy.SensitiveDataRedactor;
import com.devcli.tool.ToolStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 可重放的任务完成快照；只包含 Curator 判断所需的必要子集。 */
public record TaskMemorySnapshot(String taskId, String scopeKey, String userRequest,
                                 String finalResult, Map<String, String> workState,
                                 List<String> evidenceSummaries, Instant capturedAt,
                                 Map<String, String> toolSourceExcerpts) {
    private static final int TASK_ID_CHARS = 256;
    private static final int SCOPE_CHARS = 2_048;
    private static final int REQUEST_CHARS = 12_000;
    private static final int RESULT_CHARS = 12_000;
    private static final int STATE_VALUE_CHARS = 2_000;
    private static final int EVIDENCE_CHARS = 2_000;
    private static final int MAX_STATE_ITEMS = 64;
    private static final int MAX_EVIDENCE_ITEMS = 32;

    public TaskMemorySnapshot {
        taskId = sanitize(taskId, TASK_ID_CHARS);
        scopeKey = sanitize(scopeKey, SCOPE_CHARS);
        userRequest = boundedSource(userRequest, REQUEST_CHARS);
        finalResult = sanitize(finalResult, RESULT_CHARS);
        workState = boundedState(workState);
        evidenceSummaries = evidenceSummaries == null ? List.of() : evidenceSummaries.stream()
                .limit(MAX_EVIDENCE_ITEMS)
                .map(value -> sanitize(value, EVIDENCE_CHARS))
                .toList();
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        Map<String, String> excerpts = new java.util.LinkedHashMap<>();
        if (toolSourceExcerpts != null) {
            for (int i = 1; i <= evidenceSummaries.size(); i++) {
                String ref = "evidence:" + i;
                String text = toolSourceExcerpts.get(ref);
                if (text != null && !text.isBlank()) excerpts.put(ref, boundedSource(text, EVIDENCE_CHARS));
            }
        }
        toolSourceExcerpts = Map.copyOf(excerpts);
    }

    public TaskMemorySnapshot(String taskId, String scopeKey, String userRequest,
                              String finalResult, Map<String, String> workState,
                              List<String> evidenceSummaries, Instant capturedAt) {
        this(taskId, scopeKey, userRequest, finalResult, workState,
                evidenceSummaries, capturedAt, Map.of());
    }

    public static TaskMemorySnapshot capture(String taskId, String scopeKey,
                                             String userRequest, String finalResult,
                                             SessionMemory.SessionSnapshot session) {
        Map<String, String> state = session == null ? Map.of() : session.workState().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> SensitiveDataRedactor.redact(entry.getValue())));
        List<String> evidence = new ArrayList<>();
        Map<String, String> excerpts = new java.util.LinkedHashMap<>();
        if (session != null) {
            for (String constraint : session.protectedConstraints()) {
                evidence.add(SensitiveDataRedactor.redact(
                        "USER_CONSTRAINT:" + constraint));
            }
            if (!session.taskLedger().isBlank()) {
                evidence.add(SensitiveDataRedactor.redact(
                        "TASK_LEDGER:" + session.taskLedger()));
            }
            for (String modifiedFile : session.modifiedFiles()) {
                evidence.add(SensitiveDataRedactor.redact(
                        "MODIFIED_FILE:" + modifiedFile));
            }
            for (SessionMemory.EvidenceSnapshot item : session.evidenceJournal()) {
                evidence.add(SensitiveDataRedactor.redact(
                        item.kind() + ":" + item.toolName() + ":" + item.reference()));
                if (List.of("read_file", "grep_code", "execute_command").contains(item.toolName())
                        && item.status() == ToolStatus.SUCCESS && item.kind() != SessionMemory.EvidenceKind.FAILURE
                        && item.observedText() != null && !item.observedText().isBlank()) {
                    excerpts.put("evidence:" + evidence.size(), item.observedText());
                }
            }
            for (SessionMemory.AttemptDigestSnapshot attempt : session.attemptDigests()) {
                evidence.add(SensitiveDataRedactor.redact(
                        "FAILURE:" + attempt.reference() + ":" + attempt.digest()));
            }
        }
        return new TaskMemorySnapshot(taskId, scopeKey,
                SensitiveDataRedactor.redact(userRequest), SensitiveDataRedactor.redact(finalResult),
                state, evidence, Instant.now(), excerpts);
    }

    /**
     * 解析 Curator 声明的来源引用并返回快照内的真实脱敏摘录。
     * 摘录随晋升作业持久化，不依赖原会话继续存在。
     */
    public Optional<String> sourceExcerpt(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        if ("request".equals(reference)) return nonBlank(userRequest);
        if ("result".equals(reference)) return nonBlank(finalResult);
        if (reference.startsWith("state:")) {
            return nonBlank(workState.get(reference.substring("state:".length())));
        }
        if (!reference.startsWith("evidence:")) return Optional.empty();
        try {
            int declared = Integer.parseInt(reference.substring("evidence:".length()));
            int index = declared == 0 ? 0 : declared - 1;
            if (index < 0 || index >= evidenceSummaries.size()) return Optional.empty();
            String observed = toolSourceExcerpts.get("evidence:" + (index + 1));
            if (observed != null) return nonBlank(observed);
            return nonBlank(evidenceSummaries.get(index));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /** 自动晋升只接受可逐句对照的用户陈述或成功工具原文，不使用模型结论和状态自述。 */
    boolean supportsAutoSave(String reference, String content) {
        if (!"request".equals(reference) && !toolSourceExcerpts.containsKey(reference)) return false;
        String expected = normalizedStatement(content);
        if (expected.isBlank()) return false;
        return sourceExcerpt(reference).stream()
                .flatMap(text -> java.util.Arrays.stream(text.split("\\R|(?<=[。！？；;])")))
                .map(TaskMemorySnapshot::normalizedStatement)
                .filter(text -> !text.contains("?") && !text.contains("？"))
                .filter(text -> !"request".equals(reference) || !text.stripLeading().matches(
                        "(?is)^(请(?!记住)|帮我|检查|查询|确认|是否|能否|假设|如果|please |check |verify |if |what |how ).*"))
                .anyMatch(expected::equals);
    }

    private static String normalizedStatement(String value) {
        return normalize(value).replaceAll("\\s+", " ")
                .replaceFirst("^(?:请记住[：:]|记住[：:]|(?i:remember:))\\s*", "")
                .replaceAll("[。.;；]+$", "").trim();
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> boundedState(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, String> result = new java.util.LinkedHashMap<>();
        source.entrySet().stream().limit(MAX_STATE_ITEMS).forEach(entry ->
                result.put(normalize(entry.getKey()), sanitize(entry.getValue(), STATE_VALUE_CHARS)));
        return Map.copyOf(result);
    }

    private static String sanitize(String value, int maxChars) {
        String redacted = SensitiveDataRedactor.redact(normalize(value));
        return redacted.length() <= maxChars ? redacted : redacted.substring(0, maxChars);
    }

    private static String boundedSource(String value, int maxChars) {
        String redacted = SensitiveDataRedactor.redact(normalize(value));
        if (redacted.length() <= maxChars) return redacted;
        String prefix = redacted.substring(0, maxChars);
        int boundary = Math.max(prefix.lastIndexOf('\n'), prefix.lastIndexOf('。'));
        return boundary < 0 ? "" : prefix.substring(0, boundary + 1);
    }
}
