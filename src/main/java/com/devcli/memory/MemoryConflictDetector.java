package com.devcli.memory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class MemoryConflictDetector {
    private MemoryConflictDetector() {
    }

    static Optional<Conflict> detect(MemoryEntry candidate, List<MemoryEntry> existingEntries) {
        if (candidate == null || existingEntries == null) return Optional.empty();
        Optional<StructuredClaim.Claim> candidateClaim = StructuredClaim.parse(candidate.getContent());
        String subject = candidate.getSubject();
        String inferred = subject.isBlank()
                ? candidateClaim.map(StructuredClaim.Claim::subject).orElse("")
                : subject;
        if (inferred.isBlank()) return Optional.empty();
        MemoryWriteProtocol.StableKey candidateKey = MemoryWriteProtocol.stableKey(candidate);

        for (MemoryEntry existing : existingEntries) {
            if (existing == null || !existing.isRecallable() || existing.isExpired(null)
                    || existing.getId().equals(candidate.getId())) continue;
            Optional<StructuredClaim.Claim> existingClaim = StructuredClaim.parse(existing.getContent());
            String existingSubject = existing.getSubject().isBlank()
                    ? existingClaim.map(StructuredClaim.Claim::subject).orElse("")
                    : existing.getSubject();
            MemoryWriteProtocol.StableKey existingKey = MemoryWriteProtocol.stableKey(existing);
            if (candidateKey != null && existingKey != null && !candidateKey.equals(existingKey)) continue;
            if (!inferred.equals(existingSubject)) continue;
            if (!equivalent(candidate, candidateClaim, existing, existingClaim)) {
                return Optional.of(new Conflict(inferred, existing.getId()));
            }
        }
        return Optional.empty();
    }

    static Optional<String> findEquivalent(MemoryEntry candidate, List<MemoryEntry> existingEntries) {
        if (candidate == null || existingEntries == null) return Optional.empty();
        Optional<StructuredClaim.Claim> candidateClaim = StructuredClaim.parse(candidate.getContent());
        String subject = candidate.getSubject().isBlank()
                ? candidateClaim.map(StructuredClaim.Claim::subject).orElse("")
                : candidate.getSubject();
        if (subject.isBlank()) return Optional.empty();
        MemoryWriteProtocol.StableKey candidateKey = MemoryWriteProtocol.stableKey(candidate);
        for (MemoryEntry existing : existingEntries) {
            if (existing == null || !existing.isRecallable() || existing.isExpired(null)
                    || existing.getId().equals(candidate.getId())) continue;
            Optional<StructuredClaim.Claim> existingClaim = StructuredClaim.parse(existing.getContent());
            String existingSubject = existing.getSubject().isBlank()
                    ? existingClaim.map(StructuredClaim.Claim::subject).orElse("")
                    : existing.getSubject();
            MemoryWriteProtocol.StableKey existingKey = MemoryWriteProtocol.stableKey(existing);
            if (candidateKey != null && existingKey != null && !candidateKey.equals(existingKey)) continue;
            if (subject.equals(existingSubject)
                    && equivalent(candidate, candidateClaim, existing, existingClaim)) {
                return Optional.of(existing.getId());
            }
        }
        return Optional.empty();
    }

    static String inferSubject(String content) {
        return StructuredClaim.parse(content)
                .map(StructuredClaim.Claim::subject)
                .orElse("");
    }

    private static boolean equivalent(MemoryEntry candidate,
                                      Optional<StructuredClaim.Claim> candidateClaim,
                                      MemoryEntry existing,
                                      Optional<StructuredClaim.Claim> existingClaim) {
        if (candidateClaim.isPresent() && existingClaim.isPresent()
                && candidateClaim.get().subject().equals(existingClaim.get().subject())) {
            return candidateClaim.get().value().equals(existingClaim.get().value());
        }
        return normalize(existing.getContent()).equals(normalize(candidate.getContent()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}._:/-]+", "");
    }

    record Conflict(String subject, String existingId) {
    }
}
