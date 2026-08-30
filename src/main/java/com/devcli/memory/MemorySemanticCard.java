package com.devcli.memory;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 为语义检索生成稳定、短小的记忆卡文本。
 *
 * <p>向量索引只保存这份派生文本，不复制长期记忆正文；正文仍由
 * {@link LongTermMemory} 保存并在命中后按 id 回读。</p>
 */
public final class MemorySemanticCard {
    private static final int MAX_FIELD_CHARS = 384;

    private MemorySemanticCard() {
    }

    public static String from(MemoryEntry entry) {
        if (entry == null) return "";
        StringBuilder card = new StringBuilder(256);
        append(card, "type", entry.getKind().name());
        append(card, "subject", entry.getSubject());
        append(card, "scope", scope(entry.getMetadata()));
        append(card, "topic", firstNonBlank(entry.getMetadata(), "topic", "title"));
        append(card, "scenario", firstNonBlank(entry.getMetadata(), "scenario", "context"));
        append(card, "cause", firstNonBlank(entry.getMetadata(), "cause", "root_cause"));
        append(card, "conclusion", firstNonBlank(entry.getMetadata(), "conclusion", "decision"));
        append(card, "entities", firstNonBlank(entry.getMetadata(), "entities", "keywords"));
        append(card, "evidence", entry.getEvidence().sourceQuote());
        String summary = firstNonBlank(entry.getMetadata(), "summary", "description");
        if (summary.isBlank()) summary = abbreviate(entry.getContent());
        append(card, "summary", summary);
        return card.toString().trim();
    }

    public static String contentHash(String semanticText) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((semanticText == null ? "" : semanticText).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String fromLegacyText(String content) {
        return "summary=" + abbreviate(content);
    }

    private static String scope(Map<String, String> metadata) {
        if (metadata == null) return "";
        String type = metadata.getOrDefault("scope_type", "").trim();
        String key = metadata.getOrDefault("scope_key", "").trim();
        if (!type.isEmpty() && !key.isEmpty()) return type + ":" + key;
        return metadata.getOrDefault(MemoryWriteProtocol.META_SCOPE, "");
    }

    private static String firstNonBlank(Map<String, String> metadata, String... keys) {
        if (metadata == null) return "";
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static void append(StringBuilder out, String key, String value) {
        if (value == null || value.isBlank()) return;
        if (out.length() > 0) out.append(' ');
        out.append(key).append('=').append(abbreviate(value));
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_FIELD_CHARS) return normalized;
        return normalized.substring(0, MAX_FIELD_CHARS) + "...";
    }
}
