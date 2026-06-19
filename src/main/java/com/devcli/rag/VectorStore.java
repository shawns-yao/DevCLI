package com.devcli.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 向量存储 + 代码关系图谱持久化
 * <p>
 * 向量以 JSON 数组形式存储在 SQLite 中，检索时在内存计算余弦相似度。
 * 对于代码库规模（通常几百到几千个块），此方案足够；规模再大可换 FAISS / pgvector 等。
 */
public class VectorStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final Connection connection;
    private final String projectPath;
    private final String classpathEpoch;

    public VectorStore(String projectPath) throws SQLException {
        this.projectPath = projectPath;
        this.classpathEpoch = ClasspathEpoch.detect(Path.of(projectPath)).value();
        String dbDir = System.getProperty("devcli.rag.dir",
                System.getProperty("user.home") + "/.devcli/rag");
        java.io.File dir = new java.io.File(dbDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String dbPath = dir.getAbsolutePath() + "/codebase.db";
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
    }

    private void initTables() throws SQLException {
        // 代码块表：存储分块内容和向量
        String createChunks = """
                CREATE TABLE IF NOT EXISTS code_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    chunk_type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding_json TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

        // 代码关系表：存储类/方法间的依赖关系
        String createRelations = """
                CREATE TABLE IF NOT EXISTS code_relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    from_file TEXT NOT NULL,
                    from_name TEXT NOT NULL,
                    to_file TEXT,
                    to_name TEXT,
                    relation_type TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

        // 索引加速查询
        String createIdxProject = "CREATE INDEX IF NOT EXISTS idx_project ON code_chunks(project_path)";
        String createIdxFile = "CREATE INDEX IF NOT EXISTS idx_file ON code_chunks(file_path)";
        String createIdxType = "CREATE INDEX IF NOT EXISTS idx_type ON code_chunks(chunk_type)";
        String createIdxRelProject = "CREATE INDEX IF NOT EXISTS idx_rel_project ON code_relations(project_path)";
        String createIdxRelFrom = "CREATE INDEX IF NOT EXISTS idx_rel_from ON code_relations(from_name)";
        String createIdxRelTo = "CREATE INDEX IF NOT EXISTS idx_rel_to ON code_relations(to_name)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createChunks);
            stmt.execute(createRelations);
            addColumnIfMissing(stmt, "code_chunks", "index_epoch", "TEXT DEFAULT 'none'");
            addColumnIfMissing(stmt, "code_chunks", "symbol_version", "TEXT DEFAULT 'none'");
            addColumnIfMissing(stmt, "code_chunks", "classpath_epoch", "TEXT DEFAULT 'none'");
            addColumnIfMissing(stmt, "code_relations", "resolution_source", "TEXT DEFAULT 'AST_INFERRED'");
            addColumnIfMissing(stmt, "code_relations", "confidence", "REAL DEFAULT 0.5");
            addColumnIfMissing(stmt, "code_relations", "classpath_epoch", "TEXT DEFAULT 'none'");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS symbol_invalidations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        project_path TEXT NOT NULL,
                        symbol_key TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        chunk_type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        old_symbol_version TEXT NOT NULL,
                        new_symbol_version TEXT NOT NULL,
                        old_index_epoch TEXT NOT NULL,
                        new_index_epoch TEXT NOT NULL,
                        classpath_epoch TEXT NOT NULL,
                        negative_fact TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute(createIdxProject);
            stmt.execute(createIdxFile);
            stmt.execute(createIdxType);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_invalidation_project ON symbol_invalidations(project_path)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_invalidation_symbol ON symbol_invalidations(symbol_key)");
            stmt.execute(createIdxRelProject);
            stmt.execute(createIdxRelFrom);
            stmt.execute(createIdxRelTo);
        }
    }

    private void addColumnIfMissing(Statement stmt, String table, String column, String definition) throws SQLException {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            if (!e.getMessage().toLowerCase().contains("duplicate column")) {
                throw e;
            }
        }
    }

    /**
     * 清空指定项目的索引数据
     */
    public void clearProject() throws SQLException {
        String deleteChunks = "DELETE FROM code_chunks WHERE project_path = ?";
        String deleteRelations = "DELETE FROM code_relations WHERE project_path = ?";
        String deleteInvalidations = "DELETE FROM symbol_invalidations WHERE project_path = ?";
        try (PreparedStatement ps1 = connection.prepareStatement(deleteChunks);
             PreparedStatement ps2 = connection.prepareStatement(deleteRelations);
             PreparedStatement ps3 = connection.prepareStatement(deleteInvalidations)) {
            ps1.setString(1, projectPath);
            ps2.setString(1, projectPath);
            ps3.setString(1, projectPath);
            ps1.executeUpdate();
            ps2.executeUpdate();
            ps3.executeUpdate();
        }
    }

    /**
     * 批量插入代码块（事务保护）
     */
    public void insertChunks(List<CodeChunkEntry> entries) throws SQLException {
        insertChunks(entries, IndexEpoch.none().value());
    }

    public void replaceProjectIndex(List<CodeChunkEntry> entries,
                                    List<CodeRelation> relations,
                                    String indexEpoch) throws SQLException {
        Map<String, SymbolSnapshot> oldSnapshots = getSymbolSnapshots();

        // 原子操作：在同一个事务中 clear + insert，防止中途失败导致索引全部丢失
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            clearChunksAndRelations();
            insertChunks(entries, indexEpoch);
            insertRelations(relations);
            List<SymbolInvalidation> invalidations = diffInvalidations(oldSnapshots, getSymbolSnapshots());
            insertInvalidations(invalidations);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            log.error("索引替换失败，已回滚到旧索引: {}", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void clearChunksAndRelations() throws SQLException {
        String deleteChunks = "DELETE FROM code_chunks WHERE project_path = ?";
        String deleteRelations = "DELETE FROM code_relations WHERE project_path = ?";
        try (PreparedStatement ps1 = connection.prepareStatement(deleteChunks);
             PreparedStatement ps2 = connection.prepareStatement(deleteRelations)) {
            ps1.setString(1, projectPath);
            ps2.setString(1, projectPath);
            ps1.executeUpdate();
            ps2.executeUpdate();
        }
    }

    public Map<String, SymbolSnapshot> getSymbolSnapshots() throws SQLException {
        String sql = """
                SELECT file_path, chunk_type, name, symbol_version, index_epoch, classpath_epoch
                FROM code_chunks
                WHERE project_path = ?
                """;
        Map<String, SymbolSnapshot> snapshots = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String filePath = rs.getString("file_path");
                    String chunkType = rs.getString("chunk_type");
                    String name = rs.getString("name");
                    String key = SymbolSnapshot.symbolKey(filePath, chunkType, name);
                    snapshots.put(key, new SymbolSnapshot(
                            key,
                            filePath,
                            chunkType,
                            name,
                            blankToDefault(rs.getString("symbol_version"), SymbolVersion.none().value()),
                            blankToDefault(rs.getString("index_epoch"), IndexEpoch.none().value()),
                            blankToDefault(rs.getString("classpath_epoch"), ClasspathEpoch.none().value())));
                }
            }
        }
        return snapshots;
    }

    private List<SymbolInvalidation> diffInvalidations(Map<String, SymbolSnapshot> oldSnapshots,
                                                       Map<String, SymbolSnapshot> newSnapshots) {
        List<SymbolInvalidation> invalidations = new ArrayList<>();
        for (Map.Entry<String, SymbolSnapshot> entry : newSnapshots.entrySet()) {
            SymbolSnapshot oldSnapshot = oldSnapshots.get(entry.getKey());
            SymbolSnapshot newSnapshot = entry.getValue();
            if (oldSnapshot != null && !oldSnapshot.symbolVersion().equals(newSnapshot.symbolVersion())) {
                invalidations.add(SymbolInvalidation.from(oldSnapshot, newSnapshot));
            }
        }
        return invalidations;
    }

    public void insertInvalidations(List<SymbolInvalidation> invalidations) throws SQLException {
        if (invalidations == null || invalidations.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO symbol_invalidations (project_path, symbol_key, file_path, chunk_type, name,
                    old_symbol_version, new_symbol_version, old_index_epoch, new_index_epoch,
                    classpath_epoch, negative_fact)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (SymbolInvalidation invalidation : invalidations) {
                ps.setString(1, projectPath);
                ps.setString(2, invalidation.symbolKey());
                ps.setString(3, invalidation.filePath());
                ps.setString(4, invalidation.chunkType());
                ps.setString(5, invalidation.name());
                ps.setString(6, invalidation.oldSymbolVersion());
                ps.setString(7, invalidation.newSymbolVersion());
                ps.setString(8, invalidation.oldIndexEpoch());
                ps.setString(9, invalidation.newIndexEpoch());
                ps.setString(10, invalidation.classpathEpoch());
                ps.setString(11, invalidation.negativeFact());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public List<SymbolInvalidation> getInvalidationsForSymbol(String symbolKey, int limit) throws SQLException {
        if (symbolKey == null || symbolKey.isBlank() || limit <= 0) {
            return List.of();
        }
        String sql = """
                SELECT symbol_key, file_path, chunk_type, name, old_symbol_version, new_symbol_version,
                    old_index_epoch, new_index_epoch, classpath_epoch, negative_fact
                FROM symbol_invalidations
                WHERE project_path = ? AND symbol_key = ?
                ORDER BY id DESC
                LIMIT ?
                """;
        List<SymbolInvalidation> invalidations = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, symbolKey);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    invalidations.add(readInvalidation(rs));
                }
            }
        }
        return invalidations;
    }

    public List<SymbolInvalidation> getRecentInvalidations(int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        String sql = """
                SELECT symbol_key, file_path, chunk_type, name, old_symbol_version, new_symbol_version,
                    old_index_epoch, new_index_epoch, classpath_epoch, negative_fact
                FROM symbol_invalidations
                WHERE project_path = ?
                ORDER BY id DESC
                LIMIT ?
                """;
        List<SymbolInvalidation> invalidations = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    invalidations.add(readInvalidation(rs));
                }
            }
        }
        return invalidations;
    }

    private SymbolInvalidation readInvalidation(ResultSet rs) throws SQLException {
        return new SymbolInvalidation(
                rs.getString("symbol_key"),
                rs.getString("file_path"),
                rs.getString("chunk_type"),
                rs.getString("name"),
                rs.getString("old_symbol_version"),
                rs.getString("new_symbol_version"),
                rs.getString("old_index_epoch"),
                rs.getString("new_index_epoch"),
                rs.getString("classpath_epoch"),
                rs.getString("negative_fact"));
    }

    public void insertChunks(List<CodeChunkEntry> entries, String indexEpoch) throws SQLException {
        String sql = """
                INSERT INTO code_chunks (project_path, file_path, chunk_type, name, content, embedding_json,
                    index_epoch, symbol_version, classpath_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        // Bug #1 残留修复：检测是否在外层事务中，避免嵌套事务提前 commit
        boolean autoCommit = connection.getAutoCommit();
        boolean manageTransaction = autoCommit; // 只有 autoCommit=true 时才自己管理事务
        if (manageTransaction) {
            connection.setAutoCommit(false);
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeChunkEntry entry : entries) {
                SymbolSnapshot snapshot = SymbolSnapshot.from(
                        entry.chunk.filePath(),
                        entry.chunk.chunkType(),
                        entry.chunk.name(),
                        entry.chunk.content(),
                        safeIndexEpoch(indexEpoch),
                        classpathEpoch);
                ps.setString(1, projectPath);
                ps.setString(2, entry.chunk.filePath());
                ps.setString(3, entry.chunk.chunkType());
                ps.setString(4, entry.chunk.name());
                ps.setString(5, entry.chunk.content());
                ps.setString(6, embeddingToJson(entry.embedding));
                ps.setString(7, snapshot.indexEpoch());
                ps.setString(8, snapshot.symbolVersion());
                ps.setString(9, snapshot.classpathEpoch());
                ps.addBatch();
            }
            ps.executeBatch();
            if (manageTransaction) {
                connection.commit();
            }
        } catch (SQLException e) {
            if (manageTransaction) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (manageTransaction) {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * 批量插入代码关系（事务保护）
     */
    public void insertRelations(List<CodeRelation> relations) throws SQLException {
        String sql = """
                INSERT INTO code_relations (project_path, from_file, from_name, to_file, to_name, relation_type,
                    resolution_source, confidence, classpath_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        // Bug #1 残留修复：检测是否在外层事务中，避免嵌套事务提前 commit
        boolean autoCommit = connection.getAutoCommit();
        boolean manageTransaction = autoCommit;
        if (manageTransaction) {
            connection.setAutoCommit(false);
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeRelation rel : relations) {
                ps.setString(1, projectPath);
                ps.setString(2, rel.fromFile());
                ps.setString(3, rel.fromName());
                ps.setString(4, rel.toFile());
                ps.setString(5, rel.toName());
                ps.setString(6, rel.relationType());
                ps.setString(7, rel.resolutionSource());
                ps.setDouble(8, rel.confidence());
                ps.setString(9, rel.classpathEpoch());
                ps.addBatch();
            }
            ps.executeBatch();
            if (manageTransaction) {
                connection.commit();
            }
        } catch (SQLException e) {
            if (manageTransaction) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (manageTransaction) {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * 语义检索：根据查询向量返回最相似的 TopK 代码块
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK) throws SQLException {
        String sql = """
                SELECT file_path, chunk_type, name, content, embedding_json, index_epoch, symbol_version, classpath_epoch
                FROM code_chunks WHERE project_path = ?
                """;
        List<SearchResult> candidates = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String embeddingJson = rs.getString("embedding_json");
                    if (embeddingJson == null || embeddingJson.isEmpty()) {
                        continue;
                    }
                    float[] embedding = jsonToEmbedding(embeddingJson);
                    double similarity = cosineSimilarity(queryEmbedding, embedding);
                    candidates.add(searchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            similarity,
                            rs.getString("index_epoch"),
                            rs.getString("symbol_version"),
                            rs.getString("classpath_epoch")
                    ));
                }
            }
        }

        // 按相似度降序排序，取 TopK
        candidates.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return candidates.size() > topK ? new ArrayList<>(candidates.subList(0, topK)) : candidates;
    }

    /**
     * 根据关键词检索代码块（不经过 Embedding，用于精确匹配类名/方法名）
     */
    public List<SearchResult> searchByKeyword(String keyword) throws SQLException {
        // Bug #17 修复：添加 ORDER BY，优先返回名称匹配的结果
        String sql = """
                SELECT file_path, chunk_type, name, content, index_epoch, symbol_version, classpath_epoch FROM code_chunks
                WHERE project_path = ? AND (name LIKE ? ESCAPE '\\'
                    OR file_path LIKE ? ESCAPE '\\'
                    OR content LIKE ? ESCAPE '\\')
                ORDER BY
                    CASE WHEN name LIKE ? ESCAPE '\\' THEN 1 ELSE 2 END,
                    name
                """;
        List<SearchResult> results = new ArrayList<>();
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = "%" + escaped + "%";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setString(4, pattern);
            ps.setString(5, pattern); // ORDER BY 条件
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(searchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            0.3,
                            rs.getString("index_epoch"),
                            rs.getString("symbol_version"),
                            rs.getString("classpath_epoch")
                    ));
                }
            }
        }
        return results;
    }

    public List<SearchResult> findChunksByName(String name, int limit) throws SQLException {
        if (name == null || name.isBlank() || limit <= 0) {
            return List.of();
        }
        String sql = """
                SELECT file_path, chunk_type, name, content, index_epoch, symbol_version, classpath_epoch FROM code_chunks
                WHERE project_path = ? AND (name = ? OR name LIKE ? ESCAPE '\\')
                ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, chunk_type DESC, name
                LIMIT ?
                """;
        List<SearchResult> results = new ArrayList<>();
        String escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String prefixPattern = escaped + "(%";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            ps.setString(3, prefixPattern);
            ps.setString(4, name);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(searchResult(
                            rs.getString("file_path"),
                            rs.getString("chunk_type"),
                            rs.getString("name"),
                            rs.getString("content"),
                            0.0,
                            rs.getString("index_epoch"),
                            rs.getString("symbol_version"),
                            rs.getString("classpath_epoch")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * 图谱检索：查询与指定名称相关的所有关系
     */
    public List<CodeRelation> getRelations(String name) throws SQLException {
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type, resolution_source, confidence, classpath_epoch
                FROM code_relations
                WHERE project_path = ? AND (from_name = ? OR to_name = ?)
                ORDER BY confidence DESC
                """;
        List<CodeRelation> results = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(readRelation(rs));
                }
            }
        }
        return results;
    }

    /**
     * 获取指定类/方法的所有 outgoing 关系
     */
    public List<CodeRelation> getOutgoingRelations(String name) throws SQLException {
        String sql = """
                SELECT from_file, from_name, to_file, to_name, relation_type, resolution_source, confidence, classpath_epoch
                FROM code_relations
                WHERE project_path = ? AND from_name = ?
                ORDER BY confidence DESC
                """;
        List<CodeRelation> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(readRelation(rs));
                }
            }
        }
        return results;
    }

    private CodeRelation readRelation(ResultSet rs) throws SQLException {
        return new CodeRelation(
                rs.getString("from_file"),
                rs.getString("from_name"),
                rs.getString("to_file"),
                rs.getString("to_name"),
                rs.getString("relation_type"),
                rs.getString("resolution_source"),
                rs.getDouble("confidence"),
                rs.getString("classpath_epoch")
        );
    }

    /**
     * 统计当前项目的索引数据量
     */
    public IndexStats getStats() throws SQLException {
        String chunkSql = "SELECT COUNT(*) FROM code_chunks WHERE project_path = ?";
        String relSql = "SELECT COUNT(*) FROM code_relations WHERE project_path = ?";
        int chunks = 0;
        int relations = 0;

        try (PreparedStatement ps = connection.prepareStatement(chunkSql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) chunks = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(relSql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) relations = rs.getInt(1);
            }
        }
        return new IndexStats(chunks, relations);
    }

    public String currentIndexEpoch() throws SQLException {
        String sql = """
                SELECT index_epoch
                FROM code_chunks
                WHERE project_path = ?
                ORDER BY id DESC
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return safeIndexEpoch(rs.getString("index_epoch"));
                }
            }
        }
        return IndexEpoch.none().value();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String embeddingToJson(float[] embedding) {
        try {
            return mapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("向量序列化失败", e);
        }
    }

    private float[] jsonToEmbedding(String json) {
        try {
            return mapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("向量反序列化失败", e);
        }
    }

    @Override
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private SearchResult searchResult(String filePath, String chunkType, String name, String content, double similarity) {
        String symbolVersion = SymbolVersion.from(filePath, chunkType, name, content, classpathEpoch).value();
        return new SearchResult(filePath, chunkType, name, content, similarity,
                symbolVersion, classpathEpoch, IndexEpoch.none().value(), List.of());
    }

    private SearchResult searchResult(String filePath, String chunkType, String name, String content, double similarity,
                                      String indexEpoch, String symbolVersion, String resultClasspathEpoch) throws SQLException {
        String effectiveClasspathEpoch = blankToDefault(resultClasspathEpoch, classpathEpoch);
        String effectiveSymbolVersion = blankToDefault(symbolVersion,
                SymbolVersion.from(filePath, chunkType, name, content, effectiveClasspathEpoch).value());
        String effectiveIndexEpoch = blankToDefault(indexEpoch, IndexEpoch.none().value());
        return new SearchResult(filePath, chunkType, name, content, similarity,
                effectiveSymbolVersion,
                effectiveClasspathEpoch,
                effectiveIndexEpoch,
                getInvalidationsForSymbol(SymbolSnapshot.symbolKey(filePath, chunkType, name), 3));
    }

    private static String safeIndexEpoch(String indexEpoch) {
        return blankToDefault(indexEpoch, IndexEpoch.none().value());
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 带向量的代码块条目
     */
    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}

    /**
     * 检索结果
     */
    public record SearchResult(String filePath, String chunkType,
                               String name, String content, double similarity,
                               String symbolVersion, String classpathEpoch,
                               String indexEpoch, List<SymbolInvalidation> invalidations) {
        public SearchResult(String filePath, String chunkType, String name, String content, double similarity) {
            this(filePath, chunkType, name, content, similarity,
                    SymbolVersion.from(filePath, chunkType, name, content, ClasspathEpoch.none().value()).value(),
                    ClasspathEpoch.none().value(),
                    IndexEpoch.none().value(),
                    List.of());
        }

        public SearchResult(String filePath, String chunkType, String name, String content, double similarity,
                            String symbolVersion, String classpathEpoch) {
            this(filePath, chunkType, name, content, similarity, symbolVersion, classpathEpoch,
                    IndexEpoch.none().value(), List.of());
        }
    }

    /**
     * 索引统计
     */
    public record IndexStats(int chunkCount, int relationCount) {}
}
