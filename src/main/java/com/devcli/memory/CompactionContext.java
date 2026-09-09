package com.devcli.memory;

import java.util.List;
import java.util.Map;

/**
 * Immutable inputs that accompany a compaction request.  Keeping these values
 * outside the LLM prompt lets the deterministic layer bind a summary to the
 * correct session, project and event boundary.
 */
public record CompactionContext(
        int triggerTokens,
        String projectId,
        String sessionId,
        long contextEpoch,
        long historySequence,
        long sourceEventStart,
        long sourceEventEnd,
        String sourceHash,
        SessionMemory.SessionSnapshot sessionSnapshot,
        List<CompactionFactLedger.Fact> sourceFacts,
        Map<String, String> taskState) {

    public CompactionContext {
        triggerTokens = Math.max(1, triggerTokens);
        projectId = clean(projectId);
        sessionId = clean(sessionId);
        contextEpoch = Math.max(0, contextEpoch);
        historySequence = Math.max(0, historySequence);
        sourceEventStart = Math.max(0, sourceEventStart);
        sourceEventEnd = Math.max(sourceEventStart, sourceEventEnd);
        sourceHash = clean(sourceHash);
        sourceFacts = sourceFacts == null ? List.of() : List.copyOf(sourceFacts);
        taskState = taskState == null ? Map.of() : Map.copyOf(taskState);
    }

    public static CompactionContext forTrigger(int triggerTokens) {
        return new CompactionContext(triggerTokens, "", "", 0, 0, 0, 0,
                "", null, List.of(), Map.of());
    }

    public static CompactionContext empty() {
        return forTrigger(Integer.MAX_VALUE);
    }

    public CompactionContext withSessionSnapshot(SessionMemory.SessionSnapshot snapshot) {
        return new CompactionContext(triggerTokens, projectId, sessionId, contextEpoch,
                historySequence, sourceEventStart, sourceEventEnd, sourceHash,
                snapshot, sourceFacts, taskState);
    }

    public CompactionContext withTriggerTokens(int tokens) {
        return new CompactionContext(tokens, projectId, sessionId, contextEpoch,
                historySequence, sourceEventStart, sourceEventEnd, sourceHash,
                sessionSnapshot, sourceFacts, taskState);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
