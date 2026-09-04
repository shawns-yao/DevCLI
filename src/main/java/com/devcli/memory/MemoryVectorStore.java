package com.devcli.memory;

import com.devcli.util.VectorMath;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.Instant;

/**
 * 长期记忆向量存储 —— PR-C 新增。
 *
 * <p>独立于 {@code rag/codebase.db}：记忆和代码索引职责不同，库分开放在
 * {@code ~/.devcli/memory/memory_vectors.db}，避免一个被 {@code /index} 清空时连累另一个。
 *
 * <p>存储模式：单表 {@code memory_vectors}（fact_id 主键，对应 {@link MemoryEntry#getId()}）：
 * <pre>
 *   fact_id        TEXT PRIMARY KEY    -- 与 LongTermMemory 的 entry id 对齐
 *   semantic_text  TEXT                -- 由结构化记忆卡生成的派生语义文本
 *   embedding      BLOB                -- float32 little-endian
 *   dimensions     INTEGER
 *   embedding_model TEXT
 *   content_hash   TEXT
 *   indexed_at_ms  INTEGER
 * </pre>
 *
 * <p>检索：余弦相似度，在内存计算（向量数量级 < 几千，足够）。
 * 阈值 {@link #DEFAULT_SIMILARITY_THRESHOLD} = 0.5：低于此分不召回，避免噪声进 prompt。
 *
 * <p>旧版 content/embedding_json 列保留用于迁移读取，但新写入不再复制正文。
 * <p>失败模式：构造或 embed 失败时退化为 no-op，让上层走关键词 fallback。
 */
public class MemoryVectorStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    private final Connection connection;
    private final boolean usable;
    /**
     * 标记是否已经记录过"语义检索降级到关键词"的提示。
     * 当 {@code usable == false} 时，每次写入/检索方法第一次被调用都会落静默 no-op，
     * 长期记忆退化到只关键词召回但用户不会有任何感知。这个标记保证整个进程生命周期
     * 至少打印一次 WARN 让用户意识到向量索引不可用。
     */
    private volatile boolean degradeNotified = false;

    public MemoryVectorStore() {
        this(LongTermMemory.resolveMemoryDir());
    }

    public MemoryVectorStore(Path memoryDir) {
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
                        CREATE TABLE IF NOT EXISTS memory_vectors (
                            fact_id TEXT PRIMARY KEY,
                            semantic_text TEXT NOT NULL DEFAULT '',
                            embedding BLOB,
                            dimensions INTEGER NOT NULL DEFAULT 0,
                            embedding_model TEXT NOT NULL DEFAULT '',
                            content_hash TEXT NOT NULL DEFAULT '',
                            indexed_at_ms INTEGER NOT NULL DEFAULT 0,
                            content TEXT,
                            embedding_json TEXT,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                addColumnIfMissing(stmt, "semantic_text", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "embedding", "BLOB");
                addColumnIfMissing(stmt, "dimensions", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(stmt, "embedding_model", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "content_hash", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(stmt, "indexed_at_ms", "INTEGER NOT NULL DEFAULT 0");
            }
            migrateLegacyRows(conn);
            ready = true;
        } catch (Exception e) {
            log.warn("MemoryVectorStore init failed; semantic recall disabled: {}", e.getMessage());
            // conn 可能已经打开但 init 失败，关掉避免泄漏
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

    public boolean isUsable() {
        return usable;
    }

    /**
     * 不可用时，整个进程生命周期内打印一次 WARN，避免静默降级让用户排查不到。
     * 之后所有调用照旧 no-op。
     */
    private void notifyDegradeOnce() {
        if (degradeNotified) return;
        synchronized (this) {
            if (degradeNotified) return;
            degradeNotified = true;
            log.warn("MemoryVectorStore unavailable; long-term memory falling back to keyword-only retrieval. "
                    + "Subsequent vector operations will silently no-op.");
        }
    }

    /** 写入 / 更新一条语义卡向量。fact_id 已存在时覆盖。 */
    public synchronized void upsert(String factId, String content, float[] embedding) {
        upsert(factId, content, embedding, "");
    }

    public synchronized void upsert(String factId, String semanticText, float[] embedding, String embeddingModel) {
        if (!usable) { notifyDegradeOnce(); return; }
        if (factId == null || embedding == null) return;
        if (embedding.length == 0) {
            // 防御零长度向量：调用方已经过 EmbeddingClient 的 fail-fast 检查仍传零向量
            // 通常是 mock 或测试场景；忽略以避免向量索引被脏数据污染。
            log.warn("MemoryVectorStore upsert skipped for {}: zero-length embedding", factId);
            return;
        }
        try {
            connection.setAutoCommit(false);
            // SQLite UPSERT 语法（3.24+）；保险起见先 delete 再 insert
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM memory_vectors WHERE fact_id = ?")) {
                del.setString(1, factId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO memory_vectors(fact_id, semantic_text, embedding, dimensions, embedding_model, content_hash, indexed_at_ms, content, embedding_json) VALUES (?, ?, ?, ?, ?, ?, ?, '', '')")) {
                ins.setString(1, factId);
                String text = semanticText == null ? "" : semanticText;
                ins.setString(2, text);
                ins.setBytes(3, toBlob(embedding));
                ins.setInt(4, embedding.length);
                ins.setString(5, embeddingModel == null ? "" : embeddingModel);
                ins.setString(6, MemorySemanticCard.contentHash(text));
                ins.setLong(7, Instant.now().toEpochMilli());
                ins.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            log.warn("MemoryVectorStore upsert failed for {}: {}", factId, e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    /** 删除一条 fact 的向量。 */
    public synchronized void delete(String factId) {
        if (!usable) { notifyDegradeOnce(); return; }
        if (factId == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM memory_vectors WHERE fact_id = ?")) {
            ps.setString(1, factId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("MemoryVectorStore delete failed for {}: {}", factId, e.getMessage());
        }
    }

    /** 清空所有向量（配合 {@code /memory clear}）。 */
    public synchronized void clear() {
        if (!usable) { notifyDegradeOnce(); return; }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM memory_vectors");
        } catch (SQLException e) {
            log.warn("MemoryVectorStore clear failed: {}", e.getMessage());
        }
    }

    /**
     * 按余弦相似度检索 top-k。
     *
     * @param queryVector 查询向量
     * @param topK        最多返回多少条
     * @param threshold   相似度阈值（< 此值不返回）；建议用 {@link #DEFAULT_SIMILARITY_THRESHOLD}
     * @return 按相似度倒序的搜索结果，可能为空
     */
    public synchronized List<SearchResult> search(float[] queryVector, int topK, double threshold) {
        if (!usable) { notifyDegradeOnce(); return List.of(); }
        if (queryVector == null || queryVector.length == 0) return List.of();
        List<SearchResult> results = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT fact_id, semantic_text, embedding, dimensions, embedding_json, content FROM memory_vectors")) {
                while (rs.next()) {
                    String factId = rs.getString("fact_id");
                String content = rs.getString("semantic_text");
                if (content == null || content.isBlank()) content = rs.getString("content");
                float[] vec = fromBlob(rs.getBytes("embedding"), rs.getInt("dimensions"));
                if (vec == null) vec = parseEmbedding(rs.getString("embedding_json"));
                if (vec == null || vec.length != queryVector.length) continue;
                double sim = cosineSimilarity(queryVector, vec);
                if (sim >= threshold) {
                    results.add(new SearchResult(factId, content, sim));
                }
            }
        } catch (SQLException e) {
            log.warn("MemoryVectorStore search failed: {}", e.getMessage());
            return List.of();
        }
        results.sort(Comparator.comparingDouble(SearchResult::similarity).reversed());
        if (results.size() > topK) {
            return new ArrayList<>(results.subList(0, topK));
        }
        return results;
    }

    public synchronized int count() {
        if (!usable) return 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memory_vectors")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            log.warn("MemoryVectorStore count failed: {}", e.getMessage());
        }
        return 0;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private static float[] parseEmbedding(String json) {
        try {
            return JSON.readValue(json, float[].class);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] toBlob(float[] vector) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    private static float[] fromBlob(byte[] blob, int dimensions) {
        if (blob == null || blob.length == 0 || dimensions <= 0 || blob.length != dimensions * Float.BYTES) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    private static void addColumnIfMissing(Statement stmt, String column, String definition) throws SQLException {
        boolean present = false;
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(memory_vectors)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) stmt.execute("ALTER TABLE memory_vectors ADD COLUMN " + column + " " + definition);
    }

    private static void migrateLegacyRows(Connection connection) throws SQLException {
        List<LegacyRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT fact_id, content, embedding_json
                     FROM memory_vectors
                     WHERE (embedding IS NULL OR dimensions = 0)
                       AND embedding_json IS NOT NULL
                       AND embedding_json <> ''
                     """)) {
            while (rs.next()) {
                float[] vector = parseEmbedding(rs.getString("embedding_json"));
                if (vector != null && vector.length > 0) {
                    rows.add(new LegacyRow(rs.getString("fact_id"), rs.getString("content"), vector));
                }
            }
        }
        if (rows.isEmpty()) return;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE memory_vectors
                SET semantic_text = ?, embedding = ?, dimensions = ?, embedding_model = 'legacy',
                    content_hash = ?, indexed_at_ms = ?, content = '', embedding_json = ''
                WHERE fact_id = ?
                """)) {
            for (LegacyRow row : rows) {
                String semanticText = MemorySemanticCard.fromLegacyText(row.content());
                update.setString(1, semanticText);
                update.setBytes(2, toBlob(row.embedding()));
                update.setInt(3, row.embedding().length);
                update.setString(4, MemorySemanticCard.contentHash(semanticText));
                update.setLong(5, Instant.now().toEpochMilli());
                update.setString(6, row.factId());
                update.addBatch();
            }
            update.executeBatch();
            connection.commit();
        } catch (SQLException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        return VectorMath.cosineSimilarity(a, b);
    }

    public record SearchResult(String factId, String content, double similarity) {
    }

    private record LegacyRow(String factId, String content, float[] embedding) {
    }
}
