package com.devcli.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元
 */
public class MemoryEntry {
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

    public enum MemoryType {
        CONVERSATION,  // 对话记忆
        FACT,          // 事实记忆（用户偏好、项目信息等）
        SUMMARY,       // 摘要记忆
        TOOL_RESULT    // 工具执行结果
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this(id, content, type, timestamp, metadata, tokenCount, "", true, "");
    }

    /**
     * 完整构造（含冲突消解字段）。旧构造默认 {@code subject="" / active=true / supersededBy=""}，
     * 保持对既有调用点的兼容。
     */
    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount,
                       String subject, boolean active, String supersededBy) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? metadata : Map.of();
        this.tokenCount = tokenCount;
        this.subject = subject == null ? "" : subject;
        this.active = active;
        this.supersededBy = supersededBy == null ? "" : supersededBy;
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
