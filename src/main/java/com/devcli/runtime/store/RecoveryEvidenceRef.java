package com.devcli.runtime.store;

import java.time.Instant;
import java.util.Locale;

/**
 * 指向本地恢复产物的元数据引用。此记录不包含 checkpoint、补丁或快照内容。
 */
public record RecoveryEvidenceRef(
        String runId,
        String threadId,
        String branchId,
        Kind kind,
        String logicalKey,
        String normalizedRef,
        String sha256,
        State state,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public enum Kind {
        CHECKPOINT,
        PATCH_JOURNAL,
        SIDE_GIT
    }

    public enum State {
        ACTIVE,
        PREPARED,
        PRESENT,
        COMPLETED,
        ROLLED_BACK,
        FAILED,
        DELETED
    }

    public RecoveryEvidenceRef {
        runId = required(runId, "runId");
        threadId = normalize(threadId);
        branchId = branchId == null || branchId.isBlank() ? "main" : branchId.trim();
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        logicalKey = required(logicalKey, "logicalKey");
        normalizedRef = required(normalizedRef, "normalizedRef");
        sha256 = normalizeSha256(sha256);
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        version = Math.max(0, version);
    }

    public RecoveryEvidenceRef withState(State nextState) {
        return new RecoveryEvidenceRef(runId, threadId, branchId, kind, logicalKey,
                normalizedRef, sha256, nextState, createdAt, updatedAt, version);
    }

    public RecoveryEvidenceRef withReference(String reference, String digest) {
        return new RecoveryEvidenceRef(runId, threadId, branchId, kind, logicalKey,
                reference, digest, state, createdAt, updatedAt, version);
    }

    /** 兼容调用方对“normalized reference”的完整命名。 */
    public String normalizedReference() {
        return normalizedRef;
    }

    public static String normalizeReference(String value) {
        return normalize(value);
    }

    private static String required(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeSha256(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        if (!normalized.isBlank() && !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-character hexadecimal digest");
        }
        return normalized;
    }
}
