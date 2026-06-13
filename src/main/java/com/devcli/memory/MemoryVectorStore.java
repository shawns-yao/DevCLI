package com.devcli.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
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

/**
 * 长期记忆向量存储 —— PR-C 新增。
 *
 * <p>独立于 {@code rag/codebase.db}：记忆和代码索引职责不同，库分开放在
 * {@code ~/.devcli/memory/memory_vectors.db}，避免一个被 {@code /index} 清空时连累另一个。
 *
 * <p>存储模式：单表 {@code memory_vectors}（fact_id 主键，对应 {@link MemoryEntry#getId()}）：
 * <pre>
 *   fact_id        TEXT PRIMARY KEY    -- 与 LongTermMemory 的 entry id 对齐
 *   content        TEXT                -- 冗余存一份正文，便于直接返回搜索结果
 *   embedding_json TEXT                -- float[] 序列化为 JSON 数组
 *   created_at     TIMESTAMP
 * </pre>
 *
 * <p>检索：余弦相似度，在内存计算（向量数量级 < 几千，足够）。
 * 阈值 {@link #DEFAULT_SIMILARITY_THRESHOLD} = 0.5：低于此分不召回，避免噪声进 prompt。
 *
 * <p>失败模式：构造或 embed 失败时所有方法静默退化为 no-op，让上层走关键词 fallback。
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
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS memory_vectors (
                            fact_id TEXT PRIMARY KEY,
                            content TEXT NOT NULL,
                            embedding_json TEXT NOT NULL,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
            }
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

    /** 写入 / 更新一条 fact 的向量。fact_id 已存在时覆盖。 */
    public void upsert(String factId, String content, float[] embedding) {
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
                    "INSERT INTO memory_vectors(fact_id, content, embedding_json) VALUES (?, ?, ?)")) {
                ins.setString(1, factId);
                ins.setString(2, content == null ? "" : content);
                ins.setString(3, JSON.writeValueAsString(embedding));
                ins.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | JsonProcessingException e) {
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
    public void delete(String factId) {
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
    public void clear() {
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
    public List<SearchResult> search(float[] queryVector, int topK, double threshold) {
        if (!usable) { notifyDegradeOnce(); return List.of(); }
        if (queryVector == null || queryVector.length == 0) return List.of();
        List<SearchResult> results = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT fact_id, content, embedding_json FROM memory_vectors")) {
            while (rs.next()) {
                String factId = rs.getString("fact_id");
                String content = rs.getString("content");
                float[] vec = parseEmbedding(rs.getString("embedding_json"));
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

    public int count() {
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
    public void close() {
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

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public record SearchResult(String factId, String content, double similarity) {
    }
}
