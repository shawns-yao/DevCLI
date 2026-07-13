package com.devcli.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MemoryConflictDetector {
    private static final Pattern CLAIM = Pattern.compile(
            "(?i)^(.{2,100}?)(?:默认|当前|现在)?\\s*(?:是|为|使用|采用|设置为|[:=])\\s*(.{1,160})$");

    private MemoryConflictDetector() {
    }

    static Optional<Conflict> detect(MemoryEntry candidate, List<MemoryEntry> existingEntries) {
        if (candidate == null || existingEntries == null) return Optional.empty();
        String subject = candidate.getSubject();
        String inferred = subject.isBlank() ? inferSubject(candidate.getContent()) : subject;
        if (inferred.isBlank()) return Optional.empty();
        for (MemoryEntry existing : existingEntries) {
            if (existing == null || !existing.isActive() || existing.isExpired(null)
                    || existing.getId().equals(candidate.getId())) continue;
            String existingSubject = existing.getSubject().isBlank()
                    ? inferSubject(existing.getContent()) : existing.getSubject();
            if (!inferred.equals(existingSubject)) continue;
            if (!normalize(existing.getContent()).equals(normalize(candidate.getContent()))) {
                return Optional.of(new Conflict(inferred, existing.getId()));
            }
        }
        return Optional.empty();
    }

    static String inferSubject(String content) {
        if (content == null || content.isBlank()) return "";
        Matcher matcher = CLAIM.matcher(content.replaceAll("\\s+", " ").trim());
        if (!matcher.matches()) return "";
        String key = normalize(matcher.group(1));
        if (key.length() < 2) return "";
        return "claim." + shortHash(key);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}._:/-]+", "");
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    record Conflict(String subject, String existingId) {
    }
}
