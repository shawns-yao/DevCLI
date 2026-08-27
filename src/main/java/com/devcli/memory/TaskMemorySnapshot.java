package com.devcli.memory;

import com.devcli.policy.SensitiveDataRedactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 可重放的任务完成快照；只包含 Curator 判断所需的必要子集。 */
public record TaskMemorySnapshot(String taskId, String scopeKey, String userRequest,
                                 String finalResult, Map<String, String> workState,
                                 List<String> evidenceSummaries, Instant capturedAt) {
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
        userRequest = sanitize(userRequest, REQUEST_CHARS);
        finalResult = sanitize(finalResult, RESULT_CHARS);
        workState = boundedState(workState);
        evidenceSummaries = evidenceSummaries == null ? List.of() : evidenceSummaries.stream()
                .limit(MAX_EVIDENCE_ITEMS)
                .map(value -> sanitize(value, EVIDENCE_CHARS))
                .toList();
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    public static TaskMemorySnapshot capture(String taskId, String scopeKey,
                                             String userRequest, String finalResult,
                                             SessionMemory.SessionSnapshot session) {
        Map<String, String> state = session == null ? Map.of() : session.workState().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> SensitiveDataRedactor.redact(entry.getValue())));
        List<String> evidence = new ArrayList<>();
        if (session != null) {
            for (SessionMemory.EvidenceSnapshot item : session.evidenceJournal()) {
                evidence.add(SensitiveDataRedactor.redact(
                        item.kind() + ":" + item.toolName() + ":" + item.reference()));
            }
            for (SessionMemory.AttemptDigestSnapshot attempt : session.attemptDigests()) {
                evidence.add(SensitiveDataRedactor.redact(
                        "FAILURE:" + attempt.reference() + ":" + attempt.digest()));
            }
        }
        return new TaskMemorySnapshot(taskId, scopeKey,
                SensitiveDataRedactor.redact(userRequest), SensitiveDataRedactor.redact(finalResult),
                state, evidence, Instant.now());
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
}
