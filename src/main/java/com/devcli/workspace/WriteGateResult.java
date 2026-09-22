package com.devcli.workspace;

import java.util.List;

/** Deterministic result of the write-path consistency gate. */
public record WriteGateResult(Status status, String reason, List<String> affectedSymbols,
                              String changedBy, List<String> affectedResources) {
    public enum Status {
        ALLOWED,
        STALE
    }

    public WriteGateResult {
        status = status == null ? Status.ALLOWED : status;
        reason = reason == null ? "" : reason;
        affectedSymbols = affectedSymbols == null ? List.of() : List.copyOf(affectedSymbols);
        changedBy = changedBy == null ? "" : changedBy;
        affectedResources = affectedResources == null ? List.of() : List.copyOf(affectedResources);
    }

    public WriteGateResult(Status status, String reason, List<String> affectedSymbols, String changedBy) {
        this(status, reason, affectedSymbols, changedBy, List.of());
    }

    public static WriteGateResult allowed() {
        return new WriteGateResult(Status.ALLOWED, "", List.of(), "", List.of());
    }

    public static WriteGateResult stale(String reason, List<String> affectedSymbols, String changedBy) {
        return new WriteGateResult(Status.STALE, reason, affectedSymbols, changedBy, List.of());
    }

    public static WriteGateResult stale(String reason, List<String> affectedSymbols,
                                        String changedBy, List<String> affectedResources) {
        return new WriteGateResult(Status.STALE, reason, affectedSymbols, changedBy, affectedResources);
    }

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }
}
