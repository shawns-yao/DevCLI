package com.devcli.memory;

import java.util.HashMap;
import java.text.Normalizer;
import java.util.Map;
import java.util.Optional;

/** 长期记忆唯一写入协议：解析稳定键，并明确结构化与待确认状态。 */
final class MemoryWriteProtocol {
    static final String META_PREDICATE = "claim_predicate";
    static final String META_SCOPE = "claim_scope";
    static final String META_STRUCTURE_STATE = "structure_state";
    static final String META_SUBJECT_SOURCE = "claim_subject_source";
    static final String SUBJECT_SOURCE_EXPLICIT = "explicit";
    static final String SUBJECT_SOURCE_DETERMINISTIC = "deterministic";
    static final String DEFAULT_PREDICATE = "value";
    static final String DEFAULT_SCOPE = "global";

    private MemoryWriteProtocol() {
    }

    static Prepared prepare(MemoryEntry entry) {
        Optional<StructuredClaim.Claim> claim = StructuredClaim.parse(entry.getContent());
        Map<String, String> metadata = new HashMap<>(entry.getMetadata());
        String subject = entry.getSubject();
        String predicate = normalizePredicate(metadata.getOrDefault(META_PREDICATE, DEFAULT_PREDICATE));
        String scope = normalizeScope(metadata.getOrDefault(META_SCOPE, DEFAULT_SCOPE));
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
        } else if (subject != null && !subject.isBlank() && hasTrustedSubjectSource(metadata)) {
            value = normalize(entry.getContent());
            state = StructureState.STRUCTURED;
        } else {
            state = StructureState.UNSTRUCTURED;
        }
        if (subject != null && !subject.isBlank()) subject = normalizeSubject(subject);
        metadata.put(META_PREDICATE, predicate);
        metadata.put(META_SCOPE, scope);
        metadata.put(META_STRUCTURE_STATE, state.name());
        StableKey stableKey = state != StructureState.STRUCTURED
                || subject == null || subject.isBlank()
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
        String storedState = entry.getMetadata().get(META_STRUCTURE_STATE);
        if (storedState != null) {
            try {
                if (StructureState.valueOf(storedState) != StructureState.STRUCTURED) return null;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        } else if (StructuredClaim.parse(entry.getContent()).isEmpty()) {
            String inferred = MemorySubjectExtractor.extract(entry.getContent(), entry.getMetadata());
            if (!normalizeSubject(inferred).equals(normalizeSubject(entry.getSubject()))) return null;
        }
        return new StableKey(entry.getSubject(),
                entry.getMetadata().getOrDefault(META_PREDICATE, DEFAULT_PREDICATE),
                entry.getMetadata().getOrDefault(META_SCOPE, DEFAULT_SCOPE));
    }

    static String scopeOf(MemoryEntry entry) {
        if (entry == null) return DEFAULT_SCOPE;
        return normalizeScope(entry.getMetadata().getOrDefault(META_SCOPE, DEFAULT_SCOPE));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "default";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeSubject(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().toLowerCase(java.util.Locale.ROOT);
        int end = normalized.length();
        while (end > 0) {
            char trailing = normalized.charAt(end - 1);
            if (trailing != '/' && trailing != '\\') break;
            end--;
        }
        return normalized.substring(0, end);
    }

    private static String normalizeScope(String value) {
        String normalized = normalizeSubject(value);
        return normalized.isBlank() ? DEFAULT_SCOPE : normalized;
    }

    private static String normalizePredicate(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "is", "uses", "use", "equals", "setting" -> DEFAULT_PREDICATE;
            default -> normalized;
        };
    }

    private static boolean hasTrustedSubjectSource(Map<String, String> metadata) {
        String source = normalize(metadata.get(META_SUBJECT_SOURCE));
        return SUBJECT_SOURCE_EXPLICIT.equals(source)
                || SUBJECT_SOURCE_DETERMINISTIC.equals(source);
    }

    enum StructureState {
        STRUCTURED,
        UNSTRUCTURED,
        PENDING_CONFIRMATION
    }

    record StableKey(String subject, String predicate, String scope) {
        StableKey {
            subject = normalizeSubject(subject);
            predicate = normalizePredicate(predicate);
            scope = normalizeScope(scope);
        }
    }

    record Prepared(MemoryEntry entry, StableKey stableKey,
                    StructureState state, String value) {
    }
}
