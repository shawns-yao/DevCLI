package com.devcli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 实现的长期记忆存储。
 *
 * <p>跟 {@link MemoryVectorStore} 共用同一个 {@code memory_vectors.db}（加表），
 * 而非新建独立 DB——目的是让事实主体和向量索引同库共生命周期，便于备份和清理。
 *
 * <p>表结构：
 * <pre>
 *   CREATE TABLE memory_facts (
 *     id TEXT PRIMARY KEY,
 *     content TEXT NOT NULL,
 *     type TEXT NOT NULL,
 *     timestamp_ms INTEGER NOT NULL,
 *     metadata_json TEXT NOT NULL,    -- 永远是合法 JSON，空 metadata 用 "{}"
 *     token_count INTEGER NOT NULL,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *   )
 * </pre>
 *
 * <p>失败模式：构造器初始化 SQLite 失败时所有方法降级为 no-op（loadAll 返回空 list），
 * LongTermMemory 仍能在纯内存模式下工作，避免阻塞 DevCLI 启动。
 */
public class SqliteLongTermMemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteLongTermMemoryStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Connection connection;
    private final boolean usable;

    public SqliteLongTermMemoryStore() {
        this(LongTermMemory.resolveMemoryDir());
    }

    public SqliteLongTermMemoryStore(Path memoryDir) {
        Connection conn = null;
        boolean ready = false;
        try {
            Files.createDirectories(memoryDir);
            String dbPath = memoryDir.resolve("memory_vectors.db").toString();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS memory_facts (
                            id TEXT PRIMARY KEY,
                            content TEXT NOT NULL,
                            type TEXT NOT NULL,
                            timestamp_ms INTEGER NOT NULL,
                            metadata_json TEXT NOT NULL DEFAULT '{}',
                            token_count INTEGER NOT NULL,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_facts_type ON memory_facts(type)");
            }
            ready = true;
        } catch (Exception e) {
            log.warn("SqliteLongTermMemoryStore init failed; long-term memory will run in-memory only: {}",
                    e.getMessage());
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                conn = null;
            }
        }
        this.connection = conn;
        this.usable = ready;
    }

    @Override
    public List<MemoryEntry> loadAll() {
        if (!usable) return List.of();
        List<MemoryEntry> entries = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, content, type, timestamp_ms, metadata_json, token_count "
                             + "FROM memory_facts ORDER BY timestamp_ms ASC")) {
            while (rs.next()) {
                MemoryEntry entry = parseRow(rs);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (SQLException e) {
            log.warn("loadAll failed: {}", e.getMessage());
        }
        return entries;
    }

    @Override
    public boolean upsert(MemoryEntry entry) {
        if (!usable || entry == null) return false;
        String sql = """
                INSERT INTO memory_facts(id, content, type, timestamp_ms, metadata_json, token_count)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    content = excluded.content,
                    type = excluded.type,
                    timestamp_ms = excluded.timestamp_ms,
                    metadata_json = excluded.metadata_json,
                    token_count = excluded.token_count
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entry.getId());
            ps.setString(2, entry.getContent());
            ps.setString(3, entry.getType().name());
            ps.setLong(4, entry.getTimestamp().toEpochMilli());
            ps.setString(5, metadataToJson(entry.getMetadata()));
            ps.setInt(6, entry.getTokenCount());
            ps.executeUpdate();
            return true;
        } catch (SQLException | JsonProcessingException e) {
            log.warn("upsert failed for {}: {}", entry.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isPersistent() {
        return usable;
    }

    @Override
    public void delete(String id) {
        if (!usable || id == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM memory_facts WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("delete failed for {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void clear() {
        if (!usable) return;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM memory_facts");
        } catch (SQLException e) {
            log.warn("clear failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private MemoryEntry parseRow(ResultSet rs) throws SQLException {
        try {
            String id = rs.getString("id");
            String content = rs.getString("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf(rs.getString("type"));
            Instant timestamp = Instant.ofEpochMilli(rs.getLong("timestamp_ms"));
            Map<String, String> metadata = parseMetadata(rs.getString("metadata_json"));
            int tokenCount = rs.getInt("token_count");
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount);
        } catch (IllegalArgumentException e) {
            log.warn("Skip corrupted row in memory_facts: {}", e.getMessage());
            return null;
        }
    }

    private static String metadataToJson(Map<String, String> metadata) throws JsonProcessingException {
        if (metadata == null || metadata.isEmpty()) return "{}";
        ObjectNode node = JSON.createObjectNode();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            node.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return JSON.writeValueAsString(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseMetadata(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return Map.of();
        try {
            Map<String, Object> raw = JSON.readValue(json, LinkedHashMap.class);
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
            return result;
        } catch (Exception e) {
            log.debug("Bad metadata_json, falling back to empty: {}", e.getMessage());
            return Map.of();
        }
    }
}
