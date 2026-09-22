package com.devcli.memory;

import java.util.List;
import java.util.Map;

/** Immutable pre-compaction facts used for exact post-compaction restoration. */
public record CompactBoundarySnapshot(
        String boundaryId,
        long historySequence,
        List<CompactionFactLedger.Fact> protectedFacts,
        List<String> modifiedFiles,
        List<String> unresolvedItems,
        List<String> activeConstraints,
        Map<String, String> latestDecisions,
        String checksum,
        Map<String, String> workState,
        String taskLedger,
        String projectId,
        String sessionId,
        long contextEpoch,
        String sourceHash,
        long sourceEventStart,
        long sourceEventEnd) {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().enable(
                    com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public CompactBoundarySnapshot(String boundaryId, long historySequence,
                                   List<CompactionFactLedger.Fact> protectedFacts,
                                   List<String> modifiedFiles, List<String> unresolvedItems,
                                   List<String> activeConstraints, Map<String, String> latestDecisions,
                                   String checksum) {
        this(boundaryId, historySequence, protectedFacts, modifiedFiles, unresolvedItems,
                activeConstraints, latestDecisions, checksum, Map.of(), "", "", "", 0,
                "", 0, 0);
    }

    public CompactBoundarySnapshot(String boundaryId, long historySequence,
                                   List<CompactionFactLedger.Fact> protectedFacts,
                                   List<String> modifiedFiles, List<String> unresolvedItems,
                                   List<String> activeConstraints, Map<String, String> latestDecisions,
                                   String checksum, Map<String, String> workState, String taskLedger) {
        this(boundaryId, historySequence, protectedFacts, modifiedFiles, unresolvedItems,
                activeConstraints, latestDecisions, checksum, workState, taskLedger,
                "", "", 0, "", 0, 0);
    }

    public CompactBoundarySnapshot {
        protectedFacts = protectedFacts == null ? List.of() : List.copyOf(protectedFacts);
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        unresolvedItems = unresolvedItems == null ? List.of() : List.copyOf(unresolvedItems);
        activeConstraints = activeConstraints == null ? List.of() : List.copyOf(activeConstraints);
        latestDecisions = latestDecisions == null ? Map.of() : Map.copyOf(latestDecisions);
        workState = workState == null ? Map.of() : Map.copyOf(workState);
        taskLedger = taskLedger == null ? "" : taskLedger;
        projectId = projectId == null ? "" : projectId.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
        contextEpoch = Math.max(0, contextEpoch);
        sourceHash = sourceHash == null ? "" : sourceHash.trim();
        sourceEventStart = Math.max(0, sourceEventStart);
        sourceEventEnd = Math.max(sourceEventStart, sourceEventEnd);
    }

    /** 校验涵盖完整载荷，任务状态不冒充用户决策。 */
    public CompactBoundarySnapshot sealed() {
        return new CompactBoundarySnapshot(boundaryId, historySequence, protectedFacts,
                modifiedFiles, unresolvedItems, activeConstraints, latestDecisions,
                payloadChecksum(), workState, taskLedger, projectId, sessionId,
                contextEpoch, sourceHash, sourceEventStart, sourceEventEnd);
    }

    public boolean checksumValid() {
        return checksum != null && checksum.equals(payloadChecksum());
    }

    /** Reject a snapshot from an older or unrelated runtime boundary. */
    public boolean matches(CompactionContext context) {
        if (context == null) return false;
        if (!context.projectId().isBlank()
                && !context.projectId().equals(projectId)) return false;
        if (!context.sessionId().isBlank()
                && !context.sessionId().equals(sessionId)) return false;
        if (context.contextEpoch() > 0 && context.contextEpoch() != contextEpoch) return false;
        if (context.historySequence() > 0 && context.historySequence() != historySequence) return false;
        if (context.sourceEventStart() > 0 && context.sourceEventStart() != sourceEventStart) return false;
        if (context.sourceEventEnd() > 0 && context.sourceEventEnd() != sourceEventEnd) return false;
        if (!context.sourceHash().isBlank()
                && !context.sourceHash().equals(sourceHash)) return false;
        return !context.projectId().isBlank() && !context.sessionId().isBlank();
    }

    private String payloadChecksum() {
        try {
            var unsigned = new CompactBoundarySnapshot(boundaryId, historySequence, protectedFacts,
                    modifiedFiles, unresolvedItems, activeConstraints, latestDecisions, "",
                    workState, taskLedger, projectId, sessionId, contextEpoch, sourceHash,
                    sourceEventStart, sourceEventEnd);
            byte[] bytes = JSON.writeValueAsBytes(unsigned);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash compaction boundary", exception);
        }
    }
}
