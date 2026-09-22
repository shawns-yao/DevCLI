package com.devcli.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 一条主题记忆 = 一个 Markdown 文件。
 *
 * <p>frontmatter 是持久化元数据的唯一来源：除 CodeBuddy 风格的
 * {@code name}/{@code description}/{@code type} 外，保留可审计的创建时间、
 * 更新时间、修订号与可选有效期。过期只影响召回，不删除文件。
 */
public final class TopicMemory {

    public static final int HEAD_BYTES = 8_192;
    public static final int MAX_DESCRIPTION_CHARS = 240;
    public static final Set<String> SUPPORTED_TYPES =
            Set.of("user", "feedback", "project", "reference");

    private final Path file;
    private final String name;
    private final String description;
    private final String type;
    private final String body;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant expiresAt;
    private final int revision;

    private TopicMemory(Path file, String name, String description, String type,
                        String body, Instant createdAt, Instant updatedAt,
                        Instant expiresAt, int revision) {
        this.file = file;
        this.name = name == null ? "" : name.trim();
        String normalizedDescription = description == null ? "" : description.trim();
        this.description = normalizedDescription.length() > MAX_DESCRIPTION_CHARS
                ? normalizedDescription.substring(0, MAX_DESCRIPTION_CHARS)
                : normalizedDescription;
        this.type = normalizeType(type);
        this.body = body == null ? "" : body.strip();
        this.createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
        this.expiresAt = expiresAt;
        this.revision = Math.max(1, revision);
    }

    public static TopicMemory read(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            return fromParsed(file, parse(raw));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    public static TopicMemory readHead(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return fromParsed(file, parse(readHeadText(file))).withoutBody();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static TopicMemory fromParsed(Path file, Parsed parsed) {
        Instant modified = modifiedAt(file);
        Instant created = parseInstant(parsed.createdAt(), modified);
        Instant updated = parseInstant(parsed.updatedAt(), modified);
        Instant expires = parseInstant(parsed.expiresAt(), null);
        return new TopicMemory(file, parsed.name(), parsed.description(), parsed.type(),
                parsed.body(), created, updated, expires, parseRevision(parsed.revision()));
    }

    private TopicMemory withoutBody() {
        return new TopicMemory(file, name, description, type, "", createdAt, updatedAt,
                expiresAt, revision);
    }

    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("---\n");
        builder.append("name: ").append(escape(name)).append('\n');
        builder.append("description: ").append(escape(description)).append('\n');
        builder.append("type: ").append(type).append('\n');
        builder.append("created_at: ").append(createdAt).append('\n');
        builder.append("updated_at: ").append(updatedAt).append('\n');
        if (expiresAt != null) {
            builder.append("expires_at: ").append(expiresAt).append('\n');
        }
        builder.append("revision: ").append(revision).append('\n');
        builder.append("---\n\n");
        builder.append(body).append('\n');
        return builder.toString();
    }

    public String manifestLine() {
        String described = description.length() > MAX_DESCRIPTION_CHARS
                ? description.substring(0, MAX_DESCRIPTION_CHARS) + "…"
                : description;
        String label = name.isBlank() ? fileName() : name;
        String expiry = expiresAt == null ? "" : ", 有效至 " + expiresAt;
        return "- [" + type + "] " + fileName() + " (更新 " + updatedAt
                + ", r" + revision + expiry + "): " + label
                + (described.isBlank() ? "" : ": " + described);
    }

    public Path file() { return file; }

    public String fileName() { return file == null ? "" : file.getFileName().toString(); }

    public String name() { return name; }

    public String description() { return description; }

    public String type() { return type; }

    public String body() { return body; }

    public Instant createdAt() { return createdAt; }

    public Instant updatedAt() { return updatedAt; }

    public Instant expiresAt() { return expiresAt; }

    public int revision() { return revision; }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    public static TopicMemory of(Path file, String name, String description, String type,
                                 String body, Instant now, Instant expiresAt,
                                 int revision, Instant createdAt) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        return new TopicMemory(file, name, description, type, body,
                createdAt == null ? effectiveNow : createdAt,
                effectiveNow, expiresAt, revision);
    }

    public static boolean isSupportedType(String type) {
        return type != null && SUPPORTED_TYPES.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    public static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_TYPES.contains(normalized) ? normalized : "reference";
    }

    private static Instant modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private static String readHeadText(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            return new String(stream.readNBytes(HEAD_BYTES), StandardCharsets.UTF_8);
        }
    }

    record Parsed(String name, String description, String type, String body,
                  String createdAt, String updatedAt, String expiresAt, String revision) {
    }

    static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        Map<String, String> fields = new LinkedHashMap<>();
        String body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                String block = text.substring(4, end);
                for (String line : block.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = unescape(line.substring(colon + 1).trim());
                    if (!key.isEmpty()) fields.put(key, value);
                }
                int bodyStart = text.indexOf('\n', end + 1);
                body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
            }
        }
        body = body.strip();
        String name = fields.getOrDefault("name", "");
        String description = fields.getOrDefault("description", "");
        if (name.isBlank()) name = firstHeading(body);
        if (description.isBlank()) description = firstContentLine(body, name);
        return new Parsed(name, description, fields.getOrDefault("type", "reference"), body,
                fields.getOrDefault("created_at", ""),
                fields.getOrDefault("updated_at", ""),
                fields.getOrDefault("expires_at", ""),
                fields.getOrDefault("revision", "1"));
    }

    private static String firstHeading(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceAll("^#+\\s*", "").strip();
                if (!heading.isEmpty()) return heading;
            }
        }
        return "";
    }

    private static String firstContentLine(String body, String name) {
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals(name)) continue;
            return trimmed;
        }
        return "";
    }

    private static String escape(String value) {
        String text = value == null ? "" : value.replace("\n", " ").strip();
        if (text.contains(":") || text.startsWith("-") || text.startsWith("\"")) {
            return "\"" + text.replace("\"", "'") + "\"";
        }
        return text;
    }

    private static String unescape(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.strip();
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int parseRevision(String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }
}
