package com.devcli.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 记忆条目 —— 一条长期记忆在内存中的视图。
 *
 * <p>它只是**主题文件的一次投影**：{@code id} 是文件名，{@code content} 是正文，
 * {@code metadata} 携带 frontmatter 的 {@code name} / {@code description} / {@code scope}，
 * {@code timestamp} 是文件修改时间。权威始终是磁盘上的 Markdown，
 * 本类不持有任何磁盘上没有的状态。
 *
 * <p>原先挂在条目上的证据（confidence / reviewState）、生命周期（expiresAt / revision /
 * supersededBy）、计数（recallCount / validatedUseCount）字段已全部移除：
 * 它们没有对应的持久化字段，只存在于进程内存里，重启即失真，
 * 却让检索侧和写入侧都要围着它们做判断。
 *
 * <p>{@link #estimateTokens(String)} 是唯一被记忆之外的子系统（压缩、工作记忆、规则上下文）
 * 使用的成员，保留。
 */
public class MemoryEntry {

    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public enum MemoryType {
        CONVERSATION,
        FACT,
        SUMMARY,
        TOOL_RESULT,
        FEEDBACK
    }

    /** 元数据键：主题名。 */
    public static final String META_NAME = "name";
    /** 元数据键：主题描述。 */
    public static final String META_DESCRIPTION = "description";
    /** 元数据键：所属作用域（{@code global} / {@code project}）。 */
    public static final String META_SCOPE = "scope";
    public static final String META_MEMORY_TYPE = "memory_type";
    public static final String META_CREATED_AT = "created_at";
    public static final String META_UPDATED_AT = "updated_at";
    public static final String META_EXPIRES_AT = "expires_at";
    public static final String META_REVISION = "revision";
    public static final String META_EXPIRED = "expired";

    public MemoryEntry(String id, String content, MemoryType type,
                       Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this.id = id == null ? "" : id;
        this.content = content == null ? "" : content;
        this.type = type == null ? MemoryType.FACT : type;
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
        this.metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(metadata));
        this.tokenCount = Math.max(0, tokenCount);
    }

    public String getId() { return id; }

    public String getContent() { return content; }

    public MemoryType getType() { return type; }

    public Instant getTimestamp() { return timestamp; }

    public Map<String, String> getMetadata() { return metadata; }

    public int getTokenCount() { return tokenCount; }

    /** 主题名；缺失时回退为文件名（去掉扩展名）。 */
    public String getName() {
        String name = metadata.getOrDefault(META_NAME, "");
        if (!name.isBlank()) return name;
        int dot = id.lastIndexOf('.');
        return dot > 0 ? id.substring(0, dot) : id;
    }

    /** 主题描述；可能为空。 */
    public String getDescription() { return metadata.getOrDefault(META_DESCRIPTION, ""); }

    /** 作用域标记，仅用于展示。 */
    public String getScope() { return metadata.getOrDefault(META_SCOPE, ""); }

    /** CodeBuddy 风格的记忆类型：user / feedback / project / reference。 */
    public String getMemoryType() { return metadata.getOrDefault(META_MEMORY_TYPE, "reference"); }

    public int getRevision() {
        try {
            return Math.max(1, Integer.parseInt(metadata.getOrDefault(META_REVISION, "1")));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public java.util.Optional<Instant> getExpiresAt() {
        String value = metadata.getOrDefault(META_EXPIRES_AT, "");
        if (value.isBlank()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Instant.parse(value));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    /** 过期条目仍可审计，但不参与召回。 */
    public boolean isExpired() {
        if (Boolean.parseBoolean(metadata.getOrDefault(META_EXPIRED, "false"))) return true;
        return getExpiresAt().map(expires -> !expires.isAfter(Instant.now())).orElse(false);
    }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）。
     *
     * <p>被压缩、工作记忆与规则上下文复用，改动需同时核对这三处预算。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
