package com.devcli.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元
 */
public class MemoryEntry {
    public static final int CURRENT_SCHEMA_VERSION = 5;

    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;
    /** 主题键：同主题的旧事实在写入新事实时被标记为失效（用于冲突消解）。空串表示不参与主题归并。 */
    private final String subject;
    /** 是否为当前有效事实；被同主题新事实取代后置 false（软删除，保留审计）。 */
    private final boolean active;
    /** 取代本条的新事实 id；active=false 时有意义，否则为空串。 */
    private final String supersededBy;
    /** 持久化结构版本，用于后续无损迁移。 */
    private final int schemaVersion;
    /** 同一主题内的递增修订号；普通记忆从 1 开始。 */
    private final int revision;
    /** 过期时间；null 表示不过期。 */
    private final Instant expiresAt;
    /** 结构化证据、置信度和审核状态。 */
    private final MemoryEvidence evidence;
    /** 真正进入模型 Turn Context 的次数；单纯检索、预览或排序不计数。 */
    private final long recallCount;
    /** 最近一次真正注入模型上下文的时间；null 表示从未使用。 */
    private final Instant lastRecalledAt;
    /** 长期记忆的语义分类；与存储用途 MemoryType 分离。 */
    private final MemoryKind kind;
    /** 被用户确认、重复显式保存或工具同值验证的次数。 */
    private final long validatedUseCount;
    /** 最近一次有效验证时间；普通 Prompt 召回不更新。 */
    private final Instant lastValidatedAt;

    public enum MemoryType {
        CONVERSATION,  // 对话记忆
        FACT,          // 事实记忆（用户偏好、项目信息等）
        SUMMARY,       // 摘要记忆
        TOOL_RESULT,    // 工具执行结果
        FEEDBACK       // 用户反馈（正面 / 负面确认）
    }

    public enum MemoryKind {
        FACT, PREFERENCE, PROCEDURE, LESSON, DECISION;

        static MemoryKind from(Map<String, String> metadata) {
            String value = metadata == null ? "" : metadata.getOrDefault("memory_kind",
                    metadata.getOrDefault("memory_type", ""));
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return FACT;
            }
        }
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this(id, content, type, timestamp, metadata, tokenCount, "", true, "",
                CURRENT_SCHEMA_VERSION, 1, null, MemoryEvidence.legacy(metadata));
    }

    /**
     * 完整构造（含冲突消解字段）。旧构造默认当前 schema、revision=1、永不过期，
     * 保持对既有调用点的兼容。
     */
    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount,
                       String subject, boolean active, String supersededBy) {
        this(id, content, type, timestamp, metadata, tokenCount, subject, active, supersededBy,
                CURRENT_SCHEMA_VERSION, 1, null, MemoryEvidence.legacy(metadata));
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount,
                       String subject, boolean active, String supersededBy,
                       int schemaVersion, int revision, Instant expiresAt) {
        this(id, content, type, timestamp, metadata, tokenCount, subject, active, supersededBy,
                schemaVersion, revision, expiresAt, MemoryEvidence.legacy(metadata));
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount,
                       String subject, boolean active, String supersededBy,
                       int schemaVersion, int revision, Instant expiresAt,
                       MemoryEvidence evidence) {
        this(id, content, type, timestamp, metadata, tokenCount, subject, active, supersededBy,
                schemaVersion, revision, expiresAt, evidence, 0, null);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount,
                       String subject, boolean active, String supersededBy,
                       int schemaVersion, int revision, Instant expiresAt,
                       MemoryEvidence evidence, long recallCount, Instant lastRecalledAt) {
        this(id, content, type, timestamp, metadata, tokenCount, subject, active, supersededBy,
                schemaVersion, revision, expiresAt, evidence, recallCount, lastRecalledAt,
                null, 0, null);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount,
                       String subject, boolean active, String supersededBy,
                       int schemaVersion, int revision, Instant expiresAt,
                       MemoryEvidence evidence, long recallCount, Instant lastRecalledAt,
                       MemoryKind kind, long validatedUseCount, Instant lastValidatedAt) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(metadata));
        this.tokenCount = tokenCount;
        this.subject = subject == null ? "" : subject;
        this.active = active;
        this.supersededBy = supersededBy == null ? "" : supersededBy;
        this.schemaVersion = Math.max(1, schemaVersion);
        this.revision = Math.max(1, revision);
        this.expiresAt = expiresAt;
        this.evidence = evidence == null ? MemoryEvidence.legacy(this.metadata) : evidence;
        this.recallCount = Math.max(0, recallCount);
        this.lastRecalledAt = lastRecalledAt;
        this.kind = kind == null ? MemoryKind.from(this.metadata) : kind;
        this.validatedUseCount = Math.max(0, validatedUseCount);
        this.lastValidatedAt = lastValidatedAt;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public MemoryType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, String> getMetadata() { return metadata; }
    public int getTokenCount() { return tokenCount; }
    public String getSubject() { return subject; }
    public boolean isActive() { return active; }
    public String getSupersededBy() { return supersededBy; }
    public int getSchemaVersion() { return schemaVersion; }
    public int getRevision() { return revision; }
    public Instant getExpiresAt() { return expiresAt; }
    public MemoryEvidence getEvidence() { return evidence; }
    public long getRecallCount() { return recallCount; }
    public Instant getLastRecalledAt() { return lastRecalledAt; }
    public MemoryKind getKind() { return kind; }
    public long getValidatedUseCount() { return validatedUseCount; }
    public Instant getLastValidatedAt() { return lastValidatedAt; }

    public boolean isRecallable() {
        return active && evidence.isRecallable()
                && MemoryWriteProtocol.structureState(this)
                != MemoryWriteProtocol.StructureState.PENDING_CONFIRMATION;
    }

    public String getStableKey() {
        MemoryWriteProtocol.StableKey key = MemoryWriteProtocol.stableKey(this);
        return key == null ? "" : key.subject() + "|" + key.predicate() + "|" + key.scope();
    }

    public String getStructureState() {
        return MemoryWriteProtocol.structureState(this).name();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now == null ? Instant.now() : now);
    }

    public MemoryEntry withLifecycle(int nextRevision, Instant nextExpiresAt,
                                     Map<String, String> nextMetadata) {
        return copy(subject, active, supersededBy, nextRevision, nextExpiresAt, nextMetadata, evidence);
    }

    public MemoryEntry withEvidence(MemoryEvidence nextEvidence) {
        return copy(subject, active, supersededBy, revision, expiresAt, metadata, nextEvidence);
    }

    public MemoryEntry withRecallAt(Instant recalledAt) {
        return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                subject, active, supersededBy, CURRENT_SCHEMA_VERSION, revision, expiresAt,
                evidence, recallCount + 1, recalledAt == null ? Instant.now() : recalledAt,
                kind, validatedUseCount, lastValidatedAt);
    }

    public MemoryEntry withValidatedAt(Instant validatedAt) {
        Instant effectiveTime = validatedAt == null ? Instant.now() : validatedAt;
        return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                subject, active, supersededBy, CURRENT_SCHEMA_VERSION, revision, expiresAt,
                evidence, recallCount, lastRecalledAt, kind, validatedUseCount + 1, effectiveTime);
    }

    MemoryEntry copy(String nextSubject, boolean nextActive, String nextSupersededBy,
                     int nextRevision, Instant nextExpiresAt, Map<String, String> nextMetadata) {
        return copy(nextSubject, nextActive, nextSupersededBy, nextRevision, nextExpiresAt,
                nextMetadata, evidence);
    }

    MemoryEntry copy(String nextSubject, boolean nextActive, String nextSupersededBy,
                     int nextRevision, Instant nextExpiresAt, Map<String, String> nextMetadata,
                     MemoryEvidence nextEvidence) {
        return new MemoryEntry(id, content, type, timestamp,
                nextMetadata == null ? metadata : nextMetadata, tokenCount,
                nextSubject, nextActive, nextSupersededBy, CURRENT_SCHEMA_VERSION,
                nextRevision, nextExpiresAt, nextEvidence, recallCount, lastRecalledAt,
                kind, validatedUseCount, lastValidatedAt);
    }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）
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
