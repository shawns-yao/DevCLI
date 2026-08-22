package com.devcli.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 长期记忆唯一写入协议：解析稳定键，并明确结构化与待确认状态。 */
final class MemoryWriteProtocol {
    static final String META_PREDICATE = "claim_predicate";
    static final String META_SCOPE = "claim_scope";
    static final String META_STRUCTURE_STATE = "structure_state";
    static final String DEFAULT_PREDICATE = "value";
    static final String DEFAULT_SCOPE = "global";

    private MemoryWriteProtocol() {
    }

    static Prepared prepare(MemoryEntry entry) {
        Optional<StructuredClaim.Claim> claim = StructuredClaim.parse(entry.getContent());
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        String subject = entry.getSubject();
        String predicate = normalize(metadata.getOrDefault(META_PREDICATE, DEFAULT_PREDICATE));
        String scope = normalize(metadata.getOrDefault(META_SCOPE, DEFAULT_SCOPE));
        StructureState state;
        String value = "";
        if (entry.getEvidence().reviewState() == MemoryEvidence.ReviewState.UNREVIEWED) {
            state = StructureState.PENDING_CONFIRMATION;
            if (claim.isPresent()) {
                StructuredClaim.Claim parsed = claim.get();
                if (subject == null || subject.isBlank()) subject = parsed.subject();
                value = parsed.value();
            }
        } else if (claim.isPresent()) {
            StructuredClaim.Claim parsed = claim.get();
            if (subject == null || subject.isBlank()) subject = parsed.subject();
            value = parsed.value();
            state = StructureState.STRUCTURED;
        } else {
            state = StructureState.UNSTRUCTURED;
        }
        metadata.put(META_PREDICATE, predicate);
        metadata.put(META_SCOPE, scope);
        metadata.put(META_STRUCTURE_STATE, state.name());
        StableKey stableKey = subject == null || subject.isBlank()
                ? null : new StableKey(subject, predicate, scope);
        return new Prepared(entry.copy(subject, entry.isActive(), entry.getSupersededBy(),
                entry.getRevision(), entry.getExpiresAt(), Map.copyOf(metadata), entry.getEvidence()),
                stableKey, state, value);
    }

    static StructureState structureState(MemoryEntry entry) {
        if (entry == null) return StructureState.UNSTRUCTURED;
        try {
            return StructureState.valueOf(entry.getMetadata()
                    .getOrDefault(META_STRUCTURE_STATE, StructureState.UNSTRUCTURED.name()));
        } catch (IllegalArgumentException ignored) {
            return StructureState.UNSTRUCTURED;
        }
    }

    static StableKey stableKey(MemoryEntry entry) {
        if (entry == null || entry.getSubject().isBlank()) return null;
        return new StableKey(entry.getSubject(),
                entry.getMetadata().getOrDefault(META_PREDICATE, DEFAULT_PREDICATE),
                entry.getMetadata().getOrDefault(META_SCOPE, DEFAULT_SCOPE));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "default" : value.trim().toLowerCase();
    }

    enum StructureState {
        STRUCTURED,
        UNSTRUCTURED,
        PENDING_CONFIRMATION
    }

    record StableKey(String subject, String predicate, String scope) {
        StableKey {
            subject = subject == null ? "" : subject.trim();
            predicate = normalize(predicate);
            scope = normalize(scope);
        }
    }

    record Prepared(MemoryEntry entry, StableKey stableKey,
                    StructureState state, String value) {
    }
}
