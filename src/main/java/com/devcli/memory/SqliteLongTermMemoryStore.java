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
import java.util.Optional;
import java.util.Set;

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
 *     subject TEXT NOT NULL DEFAULT '',        -- 主题键，冲突消解归并维度
 *     active INTEGER NOT NULL DEFAULT 1,       -- 1=当前有效，0=被同主题新事实取代
 *     superseded_by TEXT NOT NULL DEFAULT '',  -- 取代本条的新事实 id
 *     confidence TEXT NOT NULL DEFAULT 'UNSPECIFIED',
 *     source_quote TEXT NOT NULL DEFAULT '',
 *     evidence_reasoning TEXT NOT NULL DEFAULT '',
 *     review_state TEXT NOT NULL DEFAULT 'REVIEWED',
 *     conflicts_with_json TEXT NOT NULL DEFAULT '[]',
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *   )
 * </pre>
 *
 * <p>旧库升级：生命周期和结构化证据列通过 {@link #addColumnIfMissing} 幂等补齐，
 * 旧行默认 {@code subject='' / active=1 / confidence=UNSPECIFIED / review_state=REVIEWED}，
 * 保持既有记忆召回行为。
 *
 * <p>失败模式：构造器初始化 SQLite 失败时所有方法降级为 no-op（loadAll 返回空 list），
 * LongTermMemory 仍能在纯内存模式下工作，避免阻塞 DevCLI 启动。
 */
public class SqliteLongTermMemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteLongTermMemoryStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SELECT_COLUMNS = "id, content, type, timestamp_ms, metadata_json, token_count, "
            + "subject, active, superseded_by, schema_version, revision, expires_at_ms, "
            + "confidence, source_quote, evidence_reasoning, review_state, conflicts_with_json, "
            + "recall_count, last_recalled_at_ms, memory_kind, validated_use_count, last_validated_at_ms, "
            + "markdown_path, content_hash, body_hash, search_text, index_state";

    private final Connection connection;
    private final boolean usable;
    private final MemoryMarkdownRepository markdownRepository;

    public SqliteLongTermMemoryStore() {
        this(LongTermMemory.resolveMemoryDir());
    }

    public SqliteLongTermMemoryStore(Path memoryDir) {
        this.markdownRepository = new MemoryMarkdownRepository(memoryDir);
        Connection conn = null;
        boolean ready = false;
        try {
            Files.createDirectories(memoryDir);
            String dbPath = memoryDir.resolve("memory_vectors.db").toString();
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA busy_timeout=5000");
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS memory_facts (
                            id TEXT PRIMARY KEY,
                            content TEXT NOT NULL,
                            type TEXT NOT NULL,
                            timestamp_ms INTEGER NOT NULL,
                            metadata_json TEXT NOT NULL DEFAULT '{}',
                            token_count INTEGER NOT NULL,
                            subject TEXT NOT NULL DEFAULT '',
                            active INTEGER NOT NULL DEFAULT 1,
                            superseded_by TEXT NOT NULL DEFAULT '',
                            schema_version INTEGER NOT NULL DEFAULT 1,
                            revision INTEGER NOT NULL DEFAULT 1,
                            expires_at_ms INTEGER,
                            confidence TEXT NOT NULL DEFAULT 'UNSPECIFIED',
                            source_quote TEXT NOT NULL DEFAULT '',
                            evidence_reasoning TEXT NOT NULL DEFAULT '',
                            review_state TEXT NOT NULL DEFAULT 'REVIEWED',
                            conflicts_with_json TEXT NOT NULL DEFAULT '[]',
                            recall_count INTEGER NOT NULL DEFAULT 0,
                            last_recalled_at_ms INTEGER,
                            memory_kind TEXT NOT NULL DEFAULT 'FACT',
                            validated_use_count INTEGER NOT NULL DEFAULT 0,
                            last_validated_at_ms INTEGER,
                            markdown_path TEXT NOT NULL DEFAULT '',
                            content_hash TEXT NOT NULL DEFAULT '',
                            body_hash TEXT NOT NULL DEFAULT '',
                            search_text TEXT NOT NULL DEFAULT '',
                            index_state TEXT NOT NULL DEFAULT 'PENDING_INDEX',
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                // 旧库迁移：CREATE TABLE IF NOT EXISTS 不会给已存在的表补列，这里幂等补齐
                addColumnIfMissing(stmt, "subject", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "active", "INTEGER NOT NULL DEFAULT 1");
                addColumnIfMissing(stmt, "superseded_by", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "schema_version", "INTEGER NOT NULL DEFAULT 1");
                addColumnIfMissing(stmt, "revision", "INTEGER NOT NULL DEFAULT 1");
                addColumnIfMissing(stmt, "expires_at_ms", "INTEGER");
                addColumnIfMissing(stmt, "confidence", "TEXT NOT NULL DEFAULT 'UNSPECIFIED'");
                addColumnIfMissing(stmt, "source_quote", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "evidence_reasoning", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "review_state", "TEXT NOT NULL DEFAULT 'REVIEWED'");
                addColumnIfMissing(stmt, "conflicts_with_json", "TEXT NOT NULL DEFAULT '[]'");
                addColumnIfMissing(stmt, "recall_count", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(stmt, "last_recalled_at_ms", "INTEGER");
                addColumnIfMissing(stmt, "memory_kind", "TEXT NOT NULL DEFAULT 'FACT'");
                addColumnIfMissing(stmt, "validated_use_count", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(stmt, "last_validated_at_ms", "INTEGER");
                addColumnIfMissing(stmt, "markdown_path", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "content_hash", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "body_hash", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "search_text", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "index_state", "TEXT NOT NULL DEFAULT 'PENDING_INDEX'");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_facts_type ON memory_facts(type)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_facts_subject_active "
                        + "ON memory_facts(subject, active)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_facts_markdown_path "
                        + "ON memory_facts(markdown_path)");
            }
            migrateLegacyBodies(conn);
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
        return loadEntries("SELECT " + SELECT_COLUMNS
                + " FROM memory_facts ORDER BY timestamp_ms ASC", true);
    }

    @Override
    public List<MemoryEntry> loadCatalog() {
        return loadEntries("SELECT " + SELECT_COLUMNS
                + " FROM memory_facts ORDER BY timestamp_ms ASC", false);
    }

    @Override
    public synchronized Optional<MemoryEntry> loadById(String id) {
        if (!usable || id == null || id.isBlank()) return Optional.empty();
        String sql = "SELECT " + SELECT_COLUMNS + " FROM memory_facts WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.ofNullable(parseRow(rs, true));
            }
        } catch (SQLException e) {
            log.warn("loadById failed for {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized List<String> searchCandidateIds(String query, int limit) {
        if (!usable || query == null || query.isBlank() || limit <= 0) return List.of();
        Set<String> tokens = MemoryQueryTokenizer.tokenize(query);
        if (tokens.isEmpty()) return List.of();
        List<String> effectiveTokens = tokens.stream().limit(8).toList();
        String predicates = String.join(" OR ", java.util.Collections.nCopies(
                effectiveTokens.size(), "LOWER(search_text) LIKE ? ESCAPE '\\'"));
        String sql = "SELECT id FROM memory_facts WHERE active = 1 AND (" + predicates
                + ") ORDER BY recall_count DESC, last_recalled_at_ms DESC LIMIT ?";
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (String token : effectiveTokens) {
                ps.setString(parameter++, "%" + escapeLike(token.toLowerCase()) + "%");
            }
            ps.setInt(parameter, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("id"));
            }
        } catch (SQLException e) {
            log.warn("searchCandidateIds failed: {}", e.getMessage());
        }
        return List.copyOf(ids);
    }

    @Override
    public synchronized Map<String, String> loadContentDigests() {
        if (!usable) return Map.of();
        Map<String, String> digests = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, body_hash FROM memory_facts")) {
            while (rs.next()) {
                String digest = rs.getString("body_hash");
                if (digest != null && !digest.isBlank()) digests.put(rs.getString("id"), digest);
            }
        } catch (SQLException e) {
            log.warn("loadContentDigests failed: {}", e.getMessage());
        }
        return Map.copyOf(digests);
    }

    private List<MemoryEntry> loadEntries(String sql, boolean hydrate) {
        if (!usable) return List.of();
        List<MemoryEntry> entries = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                MemoryEntry entry = parseRow(rs, hydrate);
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
    public synchronized boolean upsert(MemoryEntry entry) {
        if (!usable || entry == null) return false;
        MemoryMarkdownRepository.PreparedWrite write = null;
        try {
            write = markdownRepository.prepare(entry);
            markdownRepository.apply(write);
            executeUpsert(entry, write);
            return true;
        } catch (Exception e) {
            if (write != null) markdownRepository.rollback(write);
            log.warn("upsert failed for {}: {}", entry.getId(), e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean upsertAll(List<MemoryEntry> entries) {
        if (!usable || entries == null) return false;
        if (entries.isEmpty()) return true;
        List<MemoryMarkdownRepository.PreparedWrite> writes = new ArrayList<>();
        try {
            for (MemoryEntry entry : entries) {
                MemoryMarkdownRepository.PreparedWrite write = markdownRepository.prepare(entry);
                markdownRepository.apply(write);
                writes.add(write);
            }
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (int i = 0; i < entries.size(); i++) {
                    executeUpsert(entries.get(i), writes.get(i));
                }
                connection.commit();
                return true;
            } catch (Exception e) {
                connection.rollback();
                rollbackWrites(writes);
                log.warn("atomic memory revision upsert failed: {}", e.getMessage());
                return false;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception e) {
            rollbackWrites(writes);
            log.warn("atomic memory revision transaction failed: {}", e.getMessage());
            return false;
        }
    }

    private void executeUpsert(MemoryEntry entry,
                               MemoryMarkdownRepository.PreparedWrite write)
            throws SQLException, JsonProcessingException {
        String sql = """
                INSERT INTO memory_facts(id, content, type, timestamp_ms, metadata_json, token_count,
                                         subject, active, superseded_by, schema_version, revision, expires_at_ms,
                                         confidence, source_quote, evidence_reasoning, review_state,
                                         conflicts_with_json, recall_count, last_recalled_at_ms,
                                         memory_kind, validated_use_count, last_validated_at_ms,
                                         markdown_path, content_hash, body_hash, search_text, index_state)
                VALUES (?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    content = '', type = excluded.type, timestamp_ms = excluded.timestamp_ms,
                    metadata_json = excluded.metadata_json, token_count = excluded.token_count,
                    subject = excluded.subject, active = excluded.active,
                    superseded_by = excluded.superseded_by, schema_version = excluded.schema_version,
                    revision = excluded.revision, expires_at_ms = excluded.expires_at_ms,
                    confidence = excluded.confidence, source_quote = '', evidence_reasoning = '',
                    review_state = excluded.review_state, conflicts_with_json = excluded.conflicts_with_json,
                    recall_count = excluded.recall_count,
                    last_recalled_at_ms = excluded.last_recalled_at_ms,
                    memory_kind = excluded.memory_kind,
                    validated_use_count = excluded.validated_use_count,
                    last_validated_at_ms = excluded.last_validated_at_ms,
                    markdown_path = excluded.markdown_path, content_hash = excluded.content_hash,
                    body_hash = excluded.body_hash, search_text = excluded.search_text,
                    index_state = excluded.index_state
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, entry.getId());
            ps.setString(i++, entry.getType().name());
            ps.setLong(i++, entry.getTimestamp().toEpochMilli());
            ps.setString(i++, metadataToJson(entry.getMetadata()));
            ps.setInt(i++, entry.getTokenCount());
            ps.setString(i++, entry.getSubject());
            ps.setInt(i++, entry.isActive() ? 1 : 0);
            ps.setString(i++, entry.getSupersededBy());
            ps.setInt(i++, entry.getSchemaVersion());
            ps.setInt(i++, entry.getRevision());
            if (entry.getExpiresAt() == null) ps.setObject(i++, null);
            else ps.setLong(i++, entry.getExpiresAt().toEpochMilli());
            MemoryEvidence evidence = entry.getEvidence();
            ps.setString(i++, evidence.confidence().name());
            ps.setString(i++, evidence.reviewState().name());
            ps.setString(i++, JSON.writeValueAsString(evidence.conflictsWith()));
            ps.setLong(i++, entry.getRecallCount());
            if (entry.getLastRecalledAt() == null) ps.setObject(i++, null);
            else ps.setLong(i++, entry.getLastRecalledAt().toEpochMilli());
            ps.setString(i++, entry.getKind().name());
            ps.setLong(i++, entry.getValidatedUseCount());
            if (entry.getLastValidatedAt() == null) ps.setObject(i++, null);
            else ps.setLong(i++, entry.getLastValidatedAt().toEpochMilli());
            ps.setString(i++, write.relativePathString());
            ps.setString(i++, write.documentHash());
            ps.setString(i++, write.contentHash());
            ps.setString(i++, MemorySemanticCard.from(entry));
            ps.setString(i, "PENDING_INDEX");
            ps.executeUpdate();
        }
    }

    private void rollbackWrites(List<MemoryMarkdownRepository.PreparedWrite> writes) {
        for (int i = writes.size() - 1; i >= 0; i--) {
            markdownRepository.rollback(writes.get(i));
        }
    }

    @Override
    public synchronized boolean recordRecall(List<MemoryEntry> recalledEntries) {
        if (!usable || recalledEntries == null) return false;
        if (recalledEntries.isEmpty()) return true;
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE memory_facts
                    SET recall_count = recall_count + 1,
                        last_recalled_at_ms = ?
                    WHERE id = ?
                    """)) {
                for (MemoryEntry entry : recalledEntries) {
                    Instant recalledAt = entry.getLastRecalledAt();
                    ps.setLong(1, (recalledAt == null ? Instant.now() : recalledAt).toEpochMilli());
                    ps.setString(2, entry.getId());
                    if (ps.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                log.warn("recordRecall failed: {}", error.getMessage());
                return false;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.warn("recordRecall failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isPersistent() {
        return usable;
    }

    @Override
    public synchronized void delete(String id) {
        if (!usable || id == null) return;
        String markdownPath = "";
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT markdown_path FROM memory_facts WHERE id = ?")) {
            lookup.setString(1, id);
            try (ResultSet rs = lookup.executeQuery()) {
                if (rs.next()) markdownPath = rs.getString("markdown_path");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM memory_facts WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            markdownRepository.delete(markdownPath);
        } catch (SQLException e) {
            log.warn("delete failed for {}: {}", id, e.getMessage());
        }
    }

    @Override
    public synchronized void clear() {
        if (!usable) return;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM memory_facts");
            markdownRepository.clear();
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

    /**
     * 幂等补列：SQLite 的 {@code CREATE TABLE IF NOT EXISTS} 不会给已存在的旧表补新列，
     * 这里先用 {@code PRAGMA table_info} 检查再 {@code ALTER TABLE}，保证旧库平滑升级。
     */
    private static void addColumnIfMissing(Statement stmt, String column, String definition) throws SQLException {
        boolean exists = false;
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(memory_facts)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            stmt.execute("ALTER TABLE memory_facts ADD COLUMN " + column + " " + definition);
        }
    }

    private MemoryEntry parseRow(ResultSet rs, boolean hydrate) throws SQLException {
        try {
            String id = rs.getString("id");
            String content = rs.getString("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf(rs.getString("type"));
            Instant timestamp = Instant.ofEpochMilli(rs.getLong("timestamp_ms"));
            Map<String, String> metadata = parseMetadata(rs.getString("metadata_json"));
            int tokenCount = rs.getInt("token_count");
            String subject = rs.getString("subject");
            boolean active = rs.getInt("active") != 0;
            String supersededBy = rs.getString("superseded_by");
            int schemaVersion = rs.getInt("schema_version");
            int revision = rs.getInt("revision");
            long expiresAtMillis = rs.getLong("expires_at_ms");
            Instant expiresAt = rs.wasNull() ? null : Instant.ofEpochMilli(expiresAtMillis);
            MemoryEvidence evidence = new MemoryEvidence(
                    MemoryEvidence.Confidence.parse(rs.getString("confidence")),
                    rs.getString("source_quote"),
                    rs.getString("evidence_reasoning"),
                    MemoryEvidence.ReviewState.parse(rs.getString("review_state"),
                            MemoryEvidence.ReviewState.REVIEWED),
                    parseStringList(rs.getString("conflicts_with_json")));
            long recallCount = rs.getLong("recall_count");
            long lastRecalledAtMillis = rs.getLong("last_recalled_at_ms");
            Instant lastRecalledAt = rs.wasNull() ? null : Instant.ofEpochMilli(lastRecalledAtMillis);
            MemoryEntry.MemoryKind kind = metadata.containsKey("memory_kind")
                    ? MemoryEntry.MemoryKind.from(metadata)
                    : MemoryEntry.MemoryKind.valueOf(rs.getString("memory_kind"));
            long validatedUseCount = rs.getLong("validated_use_count");
            long lastValidatedAtMillis = rs.getLong("last_validated_at_ms");
            Instant lastValidatedAt = rs.wasNull() ? null : Instant.ofEpochMilli(lastValidatedAtMillis);
            String markdownPath = rs.getString("markdown_path");
            String contentHash = rs.getString("content_hash");
            if (hydrate && markdownPath != null && !markdownPath.isBlank()) {
                Optional<MemoryMarkdownRepository.Document> document =
                        markdownRepository.read(markdownPath, contentHash);
                if (document.isEmpty() || !id.equals(document.get().id())) {
                    log.warn("Skip memory {} because its Markdown source is missing or inconsistent", id);
                    return null;
                }
                MemoryMarkdownRepository.Document source = document.get();
                content = source.content();
                evidence = new MemoryEvidence(
                        MemoryEvidence.Confidence.parse(source.confidence()),
                        source.sourceQuote(), source.reasoning(),
                        MemoryEvidence.ReviewState.parse(source.reviewState(),
                                MemoryEvidence.ReviewState.REVIEWED),
                        source.conflictsWith());
            } else if (!hydrate) {
                content = "";
                evidence = new MemoryEvidence(evidence.confidence(), "", "",
                        evidence.reviewState(), evidence.conflictsWith());
            }
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                    subject, active, supersededBy, schemaVersion, revision, expiresAt, evidence,
                    recallCount, lastRecalledAt, kind, validatedUseCount, lastValidatedAt);
        } catch (IllegalArgumentException e) {
            log.warn("Skip corrupted row in memory_facts: {}", e.getMessage());
            return null;
        }
    }

    private void migrateLegacyBodies(Connection conn) throws Exception {
        List<MemoryEntry> legacyEntries = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT " + SELECT_COLUMNS
                     + " FROM memory_facts WHERE markdown_path = '' OR markdown_path IS NULL")) {
            while (rs.next()) {
                MemoryEntry entry = parseRow(rs, true);
                if (entry != null) legacyEntries.add(entry);
            }
        }
        if (legacyEntries.isEmpty()) return;

        List<MemoryMarkdownRepository.PreparedWrite> writes = new ArrayList<>();
        boolean autoCommit = conn.getAutoCommit();
        try {
            for (MemoryEntry entry : legacyEntries) {
                MemoryMarkdownRepository.PreparedWrite write = markdownRepository.prepare(entry);
                markdownRepository.apply(write);
                writes.add(write);
            }
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE memory_facts
                    SET content = '', source_quote = '', evidence_reasoning = '',
                        markdown_path = ?, content_hash = ?, body_hash = ?,
                        search_text = ?, index_state = 'PENDING_INDEX'
                    WHERE id = ?
                    """)) {
                for (int i = 0; i < legacyEntries.size(); i++) {
                    MemoryEntry entry = legacyEntries.get(i);
                    MemoryMarkdownRepository.PreparedWrite write = writes.get(i);
                    ps.setString(1, write.relativePathString());
                    ps.setString(2, write.documentHash());
                    ps.setString(3, write.contentHash());
                    ps.setString(4, MemorySemanticCard.from(entry));
                    ps.setString(5, entry.getId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            log.info("Migrated {} SQLite memory bodies to Markdown source files", legacyEntries.size());
        } catch (Exception e) {
            conn.rollback();
            rollbackWrites(writes);
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            List<Object> raw = JSON.readValue(json, List.class);
            List<String> result = new ArrayList<>();
            for (Object value : raw) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    result.add(String.valueOf(value));
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            log.debug("Bad conflicts_with_json, falling back to empty: {}", e.getMessage());
            return List.of();
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
