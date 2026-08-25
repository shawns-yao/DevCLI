package com.devcli.rag;

import com.devcli.util.VectorMath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

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
        applyConcurrencyPragmas();
        initTables();
    }

    /**
     * 开启 WAL 模式与 busy_timeout，缓解 {@code /index} 写入与 {@code search_code} 读取跨连接并发时
     * 的 {@code SQLITE_BUSY}：WAL 下读不阻塞写、写不阻塞读，busy_timeout 给偶发锁竞争一个等待窗口
     * 而非立即抛错。PRAGMA 失败仅告警降级（回退默认 rollback journal），不阻塞存储初始化。
     */
    private void applyConcurrencyPragmas() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            log.warn("启用 WAL/busy_timeout 失败，回退默认 journal 模式: {}", e.getMessage());
        }
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
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS code_index_state (
                        project_path TEXT PRIMARY KEY,
                        active_epoch TEXT NOT NULL DEFAULT 'none',
                        status TEXT NOT NULL DEFAULT 'CURRENT',
                        generation INTEGER NOT NULL DEFAULT 0,
                        updated_at_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS code_index_dirty_files (
                        project_path TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        marked_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(project_path, file_path)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS code_shadow_chunks (
                        project_path TEXT NOT NULL,
                        target_epoch TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        chunk_type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        content TEXT NOT NULL,
                        embedding_json TEXT,
                        symbol_version TEXT NOT NULL,
                        classpath_epoch TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS code_shadow_relations (
                        project_path TEXT NOT NULL,
                        target_epoch TEXT NOT NULL,
                        from_file TEXT NOT NULL,
                        from_name TEXT NOT NULL,
                        to_file TEXT,
                        to_name TEXT,
                        relation_type TEXT NOT NULL,
                        resolution_source TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        classpath_epoch TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS code_shadow_state (
                        project_path TEXT NOT NULL,
                        target_epoch TEXT NOT NULL,
                        base_epoch TEXT NOT NULL,
                        base_generation INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(project_path, target_epoch)
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shadow_chunks_project_epoch "
                    + "ON code_shadow_chunks(project_path, target_epoch)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shadow_relations_project_epoch "
                    + "ON code_shadow_relations(project_path, target_epoch)");
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
        String deleteState = "DELETE FROM code_index_state WHERE project_path = ?";
        String deleteDirty = "DELETE FROM code_index_dirty_files WHERE project_path = ?";
        String deleteShadowChunks = "DELETE FROM code_shadow_chunks WHERE project_path = ?";
        String deleteShadowRelations = "DELETE FROM code_shadow_relations WHERE project_path = ?";
        String deleteShadowState = "DELETE FROM code_shadow_state WHERE project_path = ?";
        try (PreparedStatement ps1 = connection.prepareStatement(deleteChunks);
             PreparedStatement ps2 = connection.prepareStatement(deleteRelations);
             PreparedStatement ps3 = connection.prepareStatement(deleteInvalidations);
             PreparedStatement ps4 = connection.prepareStatement(deleteState);
             PreparedStatement ps5 = connection.prepareStatement(deleteDirty);
             PreparedStatement ps6 = connection.prepareStatement(deleteShadowChunks);
             PreparedStatement ps7 = connection.prepareStatement(deleteShadowRelations);
             PreparedStatement ps8 = connection.prepareStatement(deleteShadowState)) {
            ps1.setString(1, projectPath);
            ps2.setString(1, projectPath);
            ps3.setString(1, projectPath);
            ps4.setString(1, projectPath);
            ps5.setString(1, projectPath);
            ps6.setString(1, projectPath);
            ps7.setString(1, projectPath);
            ps8.setString(1, projectPath);
            ps1.executeUpdate();
            ps2.executeUpdate();
            ps3.executeUpdate();
            ps4.executeUpdate();
            ps5.executeUpdate();
            ps6.executeUpdate();
            ps7.executeUpdate();
            ps8.executeUpdate();
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
        List<CodeChunkEntry> safeEntries = entries == null ? List.of() : entries;
        List<String> indexedFiles = safeEntries.stream()
                .map(entry -> entry.chunk().filePath())
                .distinct()
                .toList();
        try (ShadowIndexSession shadow = beginShadowIndex(
                indexEpoch, indexedFiles, ShadowIndexMode.FULL)) {
            shadow.stageChunks(safeEntries);
            shadow.stageRelations(relations);
            shadow.validate();
            if (!shadow.promote()) {
                throw new SQLException("shadow index CAS failed: target=" + safeIndexEpoch(indexEpoch)
                        + ", actualEpoch=" + currentIndexEpoch()
                        + ", actualGeneration=" + currentIndexGeneration());
            }
        }
    }

    /**
     * 创建持久化影子索引。增量模式先复制活跃代码块并剔除脏文件，后续只需补入脏文件的新块。
     * 关系图由调用方完整重建，避免变更符号的入边沿用旧解析结果。
     */
    public ShadowIndexSession beginShadowIndex(String targetEpoch,
                                               List<String> dirtyFiles,
                                               ShadowIndexMode mode) throws SQLException {
        String safeTargetEpoch = safeIndexEpoch(targetEpoch);
        if (IndexEpoch.none().value().equals(safeTargetEpoch)) {
            throw new IllegalArgumentException("shadow target epoch must not be none");
        }
        ShadowIndexMode effectiveMode = mode == null ? ShadowIndexMode.FULL : mode;
        List<String> dirtyAliases = dirtyPathAliases(dirtyFiles == null ? List.of() : dirtyFiles);
        ShadowIndexBuild build = new ShadowIndexBuild(
                currentIndexEpoch(), currentIndexGeneration(), safeTargetEpoch, effectiveMode);

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            deleteShadowIndex(safeTargetEpoch);
            if (effectiveMode == ShadowIndexMode.INCREMENTAL) {
                copyActiveChunksToShadow(safeTargetEpoch);
                deleteShadowChunksForFiles(safeTargetEpoch, dirtyAliases);
            }
            insertShadowState(build);
            upsertIndexState(build.baseEpoch(), "BUILDING", false);
            insertDirtyFiles(dirtyAliases);
            connection.commit();
            return new ShadowIndexSession(build);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** 当前项目已经进入 dirty queue 的规范化文件。 */
    public List<Path> pendingDirtyFiles() throws SQLException {
        return dirtyProjectFiles();
    }

    /** classpath 变化时符号版本也会变化，禁止复用旧 epoch 的块。 */
    public boolean canReuseActiveChunks() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM code_chunks
                WHERE project_path = ? AND classpath_epoch <> ?
                LIMIT 1
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, classpathEpoch);
            try (ResultSet rs = statement.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private void copyActiveChunksToShadow(String targetEpoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_shadow_chunks (
                    project_path, target_epoch, file_path, chunk_type, name, content,
                    embedding_json, symbol_version, classpath_epoch)
                SELECT project_path, ?, file_path, chunk_type, name, content,
                    embedding_json, symbol_version, classpath_epoch
                FROM code_chunks WHERE project_path = ?
                """)) {
            statement.setString(1, targetEpoch);
            statement.setString(2, projectPath);
            statement.executeUpdate();
        }
    }

    private void deleteShadowChunksForFiles(String targetEpoch, List<String> filePaths) throws SQLException {
        if (filePaths.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM code_shadow_chunks
                WHERE project_path = ? AND target_epoch = ? AND file_path = ?
                """)) {
            for (String filePath : filePaths) {
                statement.setString(1, projectPath);
                statement.setString(2, targetEpoch);
                statement.setString(3, filePath);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertShadowState(ShadowIndexBuild build) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_shadow_state (
                    project_path, target_epoch, base_epoch, base_generation, mode, status, created_at_ms)
                VALUES (?, ?, ?, ?, ?, 'BUILDING', ?)
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, build.targetEpoch());
            statement.setString(3, build.baseEpoch());
            statement.setLong(4, build.baseGeneration());
            statement.setString(5, build.mode().name());
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void stageShadowChunks(ShadowIndexBuild build, List<CodeChunkEntry> entries) throws SQLException {
        requireShadowState(build, "BUILDING");
        String sql = """
                INSERT INTO code_shadow_chunks (
                    project_path, target_epoch, file_path, chunk_type, name, content,
                    embedding_json, symbol_version, classpath_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CodeChunkEntry entry : entries == null ? List.<CodeChunkEntry>of() : entries) {
                SymbolSnapshot snapshot = SymbolSnapshot.from(
                        entry.chunk.filePath(), entry.chunk.chunkType(), entry.chunk.name(),
                        entry.chunk.content(), build.targetEpoch(), classpathEpoch);
                statement.setString(1, projectPath);
                statement.setString(2, build.targetEpoch());
                statement.setString(3, entry.chunk.filePath());
                statement.setString(4, entry.chunk.chunkType());
                statement.setString(5, entry.chunk.name());
                statement.setString(6, entry.chunk.content());
                statement.setString(7, embeddingToJson(entry.embedding));
                statement.setString(8, snapshot.symbolVersion());
                statement.setString(9, snapshot.classpathEpoch());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void stageShadowRelations(ShadowIndexBuild build, List<CodeRelation> relations) throws SQLException {
        requireShadowState(build, "BUILDING");
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM code_shadow_relations WHERE project_path = ? AND target_epoch = ?
                """)) {
            delete.setString(1, projectPath);
            delete.setString(2, build.targetEpoch());
            delete.executeUpdate();
        }
        String sql = """
                INSERT INTO code_shadow_relations (
                    project_path, target_epoch, from_file, from_name, to_file, to_name,
                    relation_type, resolution_source, confidence, classpath_epoch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CodeRelation relation : relations == null ? List.<CodeRelation>of() : relations) {
                statement.setString(1, projectPath);
                statement.setString(2, build.targetEpoch());
                statement.setString(3, relation.fromFile());
                statement.setString(4, relation.fromName());
                statement.setString(5, relation.toFile());
                statement.setString(6, relation.toName());
                statement.setString(7, relation.relationType());
                statement.setString(8, relation.resolutionSource());
                statement.setDouble(9, relation.confidence());
                statement.setString(10, relation.classpathEpoch());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void validateShadowIndex(ShadowIndexBuild build) throws SQLException {
        requireShadowState(build, "BUILDING");
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE code_shadow_state SET status = 'VALIDATED'
                WHERE project_path = ? AND target_epoch = ?
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, build.targetEpoch());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("shadow index disappeared before validation: " + build.targetEpoch());
            }
        }
    }

    private boolean promoteShadowIndex(ShadowIndexBuild build) throws SQLException {
        requireShadowState(build, "VALIDATED");
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!build.baseEpoch().equals(currentIndexEpoch())
                    || build.baseGeneration() != currentIndexGeneration()) {
                connection.rollback();
                return false;
            }
            Map<String, SymbolSnapshot> oldSnapshots = getSymbolSnapshots();
            clearChunksAndRelations();
            copyShadowChunksToActive(build.targetEpoch());
            copyShadowRelationsToActive(build.targetEpoch());
            List<SymbolInvalidation> invalidations = diffInvalidations(
                    oldSnapshots, getSymbolSnapshots(), build.targetEpoch());
            insertInvalidations(invalidations);
            upsertIndexState(build.targetEpoch(), "CURRENT", true);
            clearDirtyFiles();
            deleteShadowIndex(build.targetEpoch());
            connection.commit();
            return true;
        } catch (SQLException e) {
            connection.rollback();
            log.error("影子索引提升失败，已保留活跃索引: {}", e.getMessage());
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void copyShadowChunksToActive(String targetEpoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_chunks (
                    project_path, file_path, chunk_type, name, content, embedding_json,
                    index_epoch, symbol_version, classpath_epoch)
                SELECT project_path, file_path, chunk_type, name, content, embedding_json,
                    target_epoch, symbol_version, classpath_epoch
                FROM code_shadow_chunks WHERE project_path = ? AND target_epoch = ?
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, targetEpoch);
            statement.executeUpdate();
        }
    }

    private void copyShadowRelationsToActive(String targetEpoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_relations (
                    project_path, from_file, from_name, to_file, to_name, relation_type,
                    resolution_source, confidence, classpath_epoch)
                SELECT project_path, from_file, from_name, to_file, to_name, relation_type,
                    resolution_source, confidence, classpath_epoch
                FROM code_shadow_relations WHERE project_path = ? AND target_epoch = ?
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, targetEpoch);
            statement.executeUpdate();
        }
    }

    private void requireShadowState(ShadowIndexBuild build, String expectedStatus) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT base_epoch, base_generation, mode, status
                FROM code_shadow_state WHERE project_path = ? AND target_epoch = ?
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, build.targetEpoch());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()
                        || !build.baseEpoch().equals(safeIndexEpoch(rs.getString("base_epoch")))
                        || build.baseGeneration() != rs.getLong("base_generation")
                        || !build.mode().name().equals(rs.getString("mode"))
                        || !expectedStatus.equals(rs.getString("status"))) {
                    throw new SQLException("shadow index state mismatch: target=" + build.targetEpoch()
                            + ", expectedStatus=" + expectedStatus);
                }
            }
        }
    }

    private void deleteShadowIndex(String targetEpoch) throws SQLException {
        deleteShadowRows("code_shadow_chunks", targetEpoch);
        deleteShadowRows("code_shadow_relations", targetEpoch);
        deleteShadowRows("code_shadow_state", targetEpoch);
    }

    private void deleteShadowRows(String table, String targetEpoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE project_path = ? AND target_epoch = ?")) {
            statement.setString(1, projectPath);
            statement.setString(2, targetEpoch);
            statement.executeUpdate();
        }
    }

    /** 标记项目文件的现有索引内容已过期；调用方只在真实写入成功后发布。 */
    public void markDirtyFiles(List<String> filePaths) throws SQLException {
        if (filePaths == null || filePaths.isEmpty()) return;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            insertDirtyFiles(filePaths);
            upsertIndexState(currentIndexEpoch(), "STALE", true);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void insertDirtyFiles(List<String> filePaths) throws SQLException {
        String sql = """
                INSERT INTO code_index_dirty_files(project_path, file_path, marked_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT(project_path, file_path) DO UPDATE SET marked_at_ms=excluded.marked_at_ms
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long markedAt = System.currentTimeMillis();
            for (String filePath : dirtyPathAliases(filePaths)) {
                statement.setString(1, projectPath);
                statement.setString(2, filePath);
                statement.setLong(3, markedAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<String> dirtyPathAliases(List<String> filePaths) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        for (String filePath : filePaths) {
            if (filePath == null || filePath.isBlank()) continue;
            Path candidate = Path.of(filePath);
            aliases.add(filePath);
            Path absolute = (candidate.isAbsolute() ? candidate : root.resolve(candidate))
                    .toAbsolutePath().normalize();
            aliases.add(absolute.toString());
            if (absolute.startsWith(root)) {
                aliases.add(relativeProjectPath(absolute));
            }
        }
        return List.copyOf(aliases);
    }

    private void upsertIndexState(String epoch, String status, boolean incrementGeneration)
            throws SQLException {
        String sql = """
                INSERT INTO code_index_state(project_path, active_epoch, status, generation, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(project_path) DO UPDATE SET
                    active_epoch=excluded.active_epoch,
                    status=excluded.status,
                    generation=code_index_state.generation + ?,
                    updated_at_ms=excluded.updated_at_ms
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectPath);
            statement.setString(2, safeIndexEpoch(epoch));
            statement.setString(3, status);
            statement.setInt(4, incrementGeneration ? 1 : 0);
            statement.setLong(5, System.currentTimeMillis());
            statement.setInt(6, incrementGeneration ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void clearDirtyFiles() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM code_index_dirty_files WHERE project_path = ?")) {
            statement.setString(1, projectPath);
            statement.executeUpdate();
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
                                                       Map<String, SymbolSnapshot> newSnapshots,
                                                       String newIndexEpoch) {
        List<SymbolInvalidation> invalidations = new ArrayList<>();
        for (Map.Entry<String, SymbolSnapshot> entry : newSnapshots.entrySet()) {
            SymbolSnapshot oldSnapshot = oldSnapshots.get(entry.getKey());
            SymbolSnapshot newSnapshot = entry.getValue();
            if (oldSnapshot != null && !oldSnapshot.symbolVersion().equals(newSnapshot.symbolVersion())) {
                invalidations.add(SymbolInvalidation.from(oldSnapshot, newSnapshot));
            }
        }
        for (Map.Entry<String, SymbolSnapshot> entry : oldSnapshots.entrySet()) {
            if (!newSnapshots.containsKey(entry.getKey())) {
                invalidations.add(SymbolInvalidation.deleted(entry.getValue(), newIndexEpoch));
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
        boolean manageTransaction = autoCommit;
        if (manageTransaction) {
            connection.setAutoCommit(false);
        }
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

    public List<SymbolInvalidation> getRelevantInvalidations(String query, int limit) throws SQLException {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        String normalizedQuery = normalizeForMatch(query);
        return getRecentInvalidations(Math.max(limit * 5, limit)).stream()
                .filter(invalidation -> matchesInvalidationQuery(invalidation, normalizedQuery))
                .limit(limit)
                .toList();
    }

    private boolean matchesInvalidationQuery(SymbolInvalidation invalidation, String normalizedQuery) {
        if (invalidation == null || normalizedQuery.isBlank()) {
            return false;
        }
        String haystack = normalizeForMatch(String.join(" ",
                invalidation.symbolKey(),
                invalidation.filePath(),
                invalidation.chunkType(),
                invalidation.name(),
                invalidation.negativeFact()));
        for (String token : normalizedQuery.split("\\s+")) {
            if (!token.isBlank() && haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeForMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim();
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
        List<SearchResult> topResults = candidates.size() > topK
                ? new ArrayList<>(candidates.subList(0, topK)) : candidates;
        return refreshExternalChanges(topResults);
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
        return mergeDirtyKeywordCandidates(keyword, refreshExternalChanges(results));
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
        return refreshExternalChanges(results);
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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT active_epoch FROM code_index_state WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return safeIndexEpoch(rs.getString(1));
            }
        }
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

    IndexWatchSnapshot indexWatchSnapshot() throws SQLException {
        LinkedHashSet<String> indexedPaths = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT file_path FROM code_chunks
                WHERE project_path = ?
                """)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    resolveProjectPath(rs.getString(1))
                            .map(this::relativeProjectPath)
                            .ifPresent(indexedPaths::add);
                }
            }
        }
        long updatedAt = 0L;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT updated_at_ms FROM code_index_state
                WHERE project_path = ?
                """)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) updatedAt = Math.max(0L, rs.getLong(1));
            }
        }
        return new IndexWatchSnapshot(Set.copyOf(indexedPaths), updatedAt);
    }

    private long currentIndexGeneration() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT generation FROM code_index_state WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getLong(1)) : 0;
            }
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        return VectorMath.cosineSimilarity(a, b);
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
        IndexFreshness freshness = indexFreshness(filePath, effectiveIndexEpoch);
        return new SearchResult(filePath, chunkType, name, content, similarity,
                effectiveSymbolVersion,
                effectiveClasspathEpoch,
                effectiveIndexEpoch,
                getInvalidationsForSymbol(SymbolSnapshot.symbolKey(filePath, chunkType, name), 3),
                freshness);
    }

    /**
     * 只校验已经进入最终候选集的块，兜住 IDE/脚本直接修改主项目但未经过工具写入的情况。
     */
    private List<SearchResult> refreshExternalChanges(List<SearchResult> results) throws SQLException {
        if (results == null || results.isEmpty()) return List.of();
        List<SearchResult> refreshed = new ArrayList<>(results.size());
        Map<String, LiveFile> liveFiles = new HashMap<>();
        LinkedHashSet<String> newlyDirty = new LinkedHashSet<>();
        for (SearchResult result : results) {
            LiveChunk live = readLiveChunk(result.filePath(), result.chunkType(), result.name(), liveFiles);
            if (!live.verifiable()) {
                refreshed.add(result);
                continue;
            }
            boolean changed = live.content().filter(result.content()::equals).isEmpty();
            if (result.freshness() != IndexFreshness.DIRTY && !changed) {
                refreshed.add(result);
                continue;
            }
            if (changed) {
                newlyDirty.add(result.filePath());
            }
            String currentContent = live.content().orElse(
                    "索引命中的代码块已从当前文件中移除，请重新读取文件确认现状。");
            refreshed.add(new SearchResult(result.filePath(), result.chunkType(), result.name(),
                    currentContent, result.similarity(), result.symbolVersion(),
                    result.classpathEpoch(), result.indexEpoch(), result.invalidations(),
                    IndexFreshness.DIRTY));
        }
        if (!newlyDirty.isEmpty()) {
            try {
                markDirtyFiles(List.copyOf(newlyDirty));
            } catch (SQLException e) {
                log.warn("持久化外部文件索引失效标记失败: {}", e.getMessage());
            }
        }
        return refreshed;
    }

    /**
     * 旧索引只能提供已经存在的候选。文件进入 DIRTY 后，再从当前文件实时分块并合并关键词命中，
     * 使新增方法和新增配置键无需等待下一轮索引也能被精确检索发现。
     */
    private List<SearchResult> mergeDirtyKeywordCandidates(String keyword,
                                                            List<SearchResult> indexedResults)
            throws SQLException {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (SearchResult result : indexedResults) {
            merged.put(liveResultKey(result.filePath(), result.chunkType(), result.name()), result);
        }

        for (Path file : dirtyProjectFiles()) {
            LiveFile liveFile = readLiveFile(file.toString());
            if (!liveFile.verifiable() || liveFile.rawContent() == null) continue;
            for (CodeChunk chunk : liveFile.chunks()) {
                if (!containsKeyword(chunk, keyword)) continue;
                SearchResult liveResult = liveDirtyResult(chunk);
                merged.put(liveResultKey(liveResult.filePath(), liveResult.chunkType(), liveResult.name()),
                        liveResult);
            }
        }
        return List.copyOf(merged.values());
    }

    private List<Path> dirtyProjectFiles() throws SQLException {
        String sql = """
                SELECT file_path FROM code_index_dirty_files
                WHERE project_path = ?
                ORDER BY marked_at_ms, file_path
                """;
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    resolveProjectPath(rs.getString(1)).ifPresent(files::add);
                }
            }
        }
        return List.copyOf(files);
    }

    private SearchResult liveDirtyResult(CodeChunk chunk) throws SQLException {
        String symbolVersion = SymbolVersion.from(chunk.filePath(), chunk.chunkType(), chunk.name(),
                chunk.content(), classpathEpoch).value();
        return new SearchResult(chunk.filePath(), chunk.chunkType(), chunk.name(), chunk.content(),
                0.3, symbolVersion, classpathEpoch, currentIndexEpoch(),
                getInvalidationsForSymbol(SymbolSnapshot.symbolKey(
                        chunk.filePath(), chunk.chunkType(), chunk.name()), 3),
                IndexFreshness.DIRTY);
    }

    private static boolean containsKeyword(CodeChunk chunk, String keyword) {
        String needle = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        return chunk.name().toLowerCase(Locale.ROOT).contains(needle)
                || chunk.filePath().toLowerCase(Locale.ROOT).contains(needle)
                || chunk.content().toLowerCase(Locale.ROOT).contains(needle);
    }

    private String liveResultKey(String filePath, String chunkType, String name) {
        String canonicalPath = resolveProjectPath(filePath)
                .map(Path::toString)
                .orElse(filePath == null ? "" : filePath);
        String logicalName = name == null ? "" : name;
        if ("file".equals(chunkType)) {
            int segment = logicalName.lastIndexOf('#');
            logicalName = segment >= 0 && logicalName.substring(segment + 1).matches("\\d+")
                    ? "file" + logicalName.substring(segment)
                    : "file";
        }
        return canonicalPath + "#" + chunkType + "#" + logicalName;
    }

    private LiveChunk readLiveChunk(String filePath, String chunkType, String name,
                                    Map<String, LiveFile> liveFiles) {
        LiveFile liveFile = liveFiles.computeIfAbsent(filePath, this::readLiveFile);
        if (!liveFile.verifiable()) return LiveChunk.unverifiable();
        if (liveFile.rawContent() == null) return LiveChunk.missing();
        if ("file".equals(chunkType) && (name == null || !name.matches(".*#\\d+$"))) {
            return LiveChunk.present(liveFile.rawContent());
        }
        for (CodeChunk chunk : liveFile.chunks()) {
            if (chunk.chunkType().equals(chunkType) && sameChunkName(chunkType, chunk.name(), name)) {
                return LiveChunk.present(chunk.content());
            }
        }
        return LiveChunk.missing();
    }

    private LiveFile readLiveFile(String filePath) {
        try {
            Path file = resolveProjectPath(filePath).orElse(null);
            if (file == null) return LiveFile.unverifiable();
            if (!java.nio.file.Files.isRegularFile(file)) return LiveFile.missing();
            String rawContent = java.nio.file.Files.readString(file);
            return new LiveFile(true, rawContent, new CodeChunker().chunkFile(file));
        } catch (Exception ignored) {
            return LiveFile.unverifiable();
        }
    }

    private Optional<Path> resolveProjectPath(String filePath) {
        try {
            Path root = Path.of(projectPath).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(root) || filePath == null || filePath.isBlank()) {
                return Optional.empty();
            }
            Path candidate = Path.of(filePath);
            Path file = (candidate.isAbsolute() ? candidate : root.resolve(candidate))
                    .toAbsolutePath().normalize();
            return file.startsWith(root) ? Optional.of(file) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String relativeProjectPath(Path file) {
        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static boolean sameChunkName(String chunkType, String currentName, String indexedName) {
        if (java.util.Objects.equals(currentName, indexedName)) return true;
        if (!"file".equals(chunkType) || indexedName == null) return false;
        int segment = indexedName.lastIndexOf('#');
        return segment >= 0 && currentName.endsWith(indexedName.substring(segment));
    }

    private IndexFreshness indexFreshness(String filePath, String resultEpoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM code_index_dirty_files
                WHERE project_path = ? AND file_path = ? LIMIT 1
                """)) {
            statement.setString(1, projectPath);
            statement.setString(2, filePath);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return IndexFreshness.DIRTY;
            }
        }
        return safeIndexEpoch(resultEpoch).equals(currentIndexEpoch())
                && "CURRENT".equals(currentIndexStatus())
                ? IndexFreshness.CURRENT : IndexFreshness.STALE;
    }

    private String currentIndexStatus() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM code_index_state
                WHERE project_path = ?
                """)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "CURRENT";
            }
        }
    }

    private static String safeIndexEpoch(String indexEpoch) {
        return blankToDefault(indexEpoch, IndexEpoch.none().value());
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record LiveChunk(boolean verifiable, Optional<String> content) {
        static LiveChunk present(String content) {
            return new LiveChunk(true, Optional.ofNullable(content));
        }

        static LiveChunk missing() {
            return new LiveChunk(true, Optional.empty());
        }

        static LiveChunk unverifiable() {
            return new LiveChunk(false, Optional.empty());
        }
    }

    private record LiveFile(boolean verifiable, String rawContent, List<CodeChunk> chunks) {
        LiveFile {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }

        static LiveFile missing() {
            return new LiveFile(true, null, List.of());
        }

        static LiveFile unverifiable() {
            return new LiveFile(false, null, List.of());
        }
    }

    /**
     * 带向量的代码块条目
     */
    public record CodeChunkEntry(CodeChunk chunk, float[] embedding) {}

    public enum ShadowIndexMode {
        FULL,
        INCREMENTAL
    }

    private record ShadowIndexBuild(String baseEpoch,
                                    long baseGeneration,
                                    String targetEpoch,
                                    ShadowIndexMode mode) {
        private ShadowIndexBuild {
            baseEpoch = safeIndexEpoch(baseEpoch);
            targetEpoch = safeIndexEpoch(targetEpoch);
            baseGeneration = Math.max(0, baseGeneration);
            mode = mode == null ? ShadowIndexMode.FULL : mode;
        }
    }

    public final class ShadowIndexSession implements AutoCloseable {
        private final ShadowIndexBuild build;
        private boolean validated;
        private boolean promoted;
        private boolean closed;

        private ShadowIndexSession(ShadowIndexBuild build) {
            this.build = build;
        }

        public void stageChunks(List<CodeChunkEntry> entries) throws SQLException {
            ensureOpen();
            stageShadowChunks(build, entries);
        }

        public void stageRelations(List<CodeRelation> relations) throws SQLException {
            ensureOpen();
            stageShadowRelations(build, relations);
        }

        public void validate() throws SQLException {
            ensureOpen();
            validateShadowIndex(build);
            validated = true;
        }

        public boolean promote() throws SQLException {
            ensureOpen();
            if (!validated) {
                throw new IllegalStateException("shadow index must be validated before promotion");
            }
            promoted = promoteShadowIndex(build);
            return promoted;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("shadow index session is closed");
        }

        @Override
        public void close() throws SQLException {
            if (closed) return;
            closed = true;
            if (!promoted) {
                deleteShadowIndex(build.targetEpoch());
                if (build.baseEpoch().equals(currentIndexEpoch())
                        && build.baseGeneration() == currentIndexGeneration()) {
                    upsertIndexState(build.baseEpoch(), "STALE", false);
                }
            }
        }
    }

    record IndexWatchSnapshot(Set<String> indexedPaths, long indexUpdatedAtMillis) {
        IndexWatchSnapshot {
            indexedPaths = indexedPaths == null ? Set.of() : Set.copyOf(indexedPaths);
            indexUpdatedAtMillis = Math.max(0L, indexUpdatedAtMillis);
        }
    }

    /**
     * 检索结果
     */
    public record SearchResult(String filePath, String chunkType,
                               String name, String content, double similarity,
                               String symbolVersion, String classpathEpoch,
                               String indexEpoch, List<SymbolInvalidation> invalidations,
                               IndexFreshness freshness) {
        public SearchResult {
            freshness = freshness == null ? IndexFreshness.STALE : freshness;
        }

        public SearchResult(String filePath, String chunkType, String name, String content,
                            double similarity, String symbolVersion, String classpathEpoch,
                            String indexEpoch, List<SymbolInvalidation> invalidations) {
            this(filePath, chunkType, name, content, similarity, symbolVersion, classpathEpoch,
                    indexEpoch, invalidations, IndexFreshness.STALE);
        }
        public SearchResult(String filePath, String chunkType, String name, String content, double similarity) {
            this(filePath, chunkType, name, content, similarity,
                    SymbolVersion.from(filePath, chunkType, name, content, ClasspathEpoch.none().value()).value(),
                    ClasspathEpoch.none().value(),
                    IndexEpoch.none().value(),
                    List.of(), IndexFreshness.STALE);
        }

        public SearchResult(String filePath, String chunkType, String name, String content, double similarity,
                            String symbolVersion, String classpathEpoch) {
            this(filePath, chunkType, name, content, similarity, symbolVersion, classpathEpoch,
                    IndexEpoch.none().value(), List.of(), IndexFreshness.STALE);
        }
    }

    public enum IndexFreshness {
        CURRENT,
        STALE,
        DIRTY
    }

    /**
     * 索引统计
     */
    public record IndexStats(int chunkCount, int relationCount) {}
}
