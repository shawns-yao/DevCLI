package com.devcli.rag;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 沿用同一连接初始化索引结构、FTS 触发器和历史向量编码。 */
final class VectorStoreSchema {
    private final Connection connection;
    private final java.util.function.Function<String, float[]> decode;

    VectorStoreSchema(Connection connection, java.util.function.Function<String, float[]> decode) {
        this.connection = connection;
        this.decode = decode;
    }

    void initialize() throws SQLException {
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
            addColumnIfMissing(stmt, "code_chunks", "embedding_blob", "BLOB");
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
                        embedding_blob BLOB,
                        symbol_version TEXT NOT NULL,
                        classpath_epoch TEXT NOT NULL
                    )
                    """);
            addColumnIfMissing(stmt, "code_shadow_chunks", "embedding_blob", "BLOB");
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
            initFullTextIndex(stmt);
        }
        migrateLegacyEmbeddings("code_chunks", "id");
        migrateLegacyEmbeddings("code_shadow_chunks", "rowid");
    }

    void initFullTextIndex(Statement stmt) throws SQLException {
        if (fullTextIndexNeedsRebuild(stmt)) {
            stmt.execute("DROP TRIGGER IF EXISTS code_chunks_fts_ai");
            stmt.execute("DROP TRIGGER IF EXISTS code_chunks_fts_ad");
            stmt.execute("DROP TRIGGER IF EXISTS code_chunks_fts_au");
            stmt.execute("DROP TABLE IF EXISTS code_chunks_fts");
            stmt.execute("DELETE FROM rag_schema_state WHERE schema_key='fts_version'");
        }
        stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS code_chunks_fts USING fts5(
                    project_path UNINDEXED, file_path, name, content,
                    content='code_chunks', content_rowid='id', tokenize='trigram')
                """);
        stmt.execute("""
                CREATE TRIGGER IF NOT EXISTS code_chunks_fts_ai AFTER INSERT ON code_chunks BEGIN
                    INSERT INTO code_chunks_fts(rowid, project_path, file_path, name, content)
                    VALUES (new.id, new.project_path, new.file_path, new.name, new.content);
                END
                """);
        stmt.execute("""
                CREATE TRIGGER IF NOT EXISTS code_chunks_fts_ad AFTER DELETE ON code_chunks BEGIN
                    INSERT INTO code_chunks_fts(code_chunks_fts, rowid, project_path, file_path, name, content)
                    VALUES ('delete', old.id, old.project_path, old.file_path, old.name, old.content);
                END
                """);
        stmt.execute("""
                CREATE TRIGGER IF NOT EXISTS code_chunks_fts_au AFTER UPDATE ON code_chunks BEGIN
                    INSERT INTO code_chunks_fts(code_chunks_fts, rowid, project_path, file_path, name, content)
                    VALUES ('delete', old.id, old.project_path, old.file_path, old.name, old.content);
                    INSERT INTO code_chunks_fts(rowid, project_path, file_path, name, content)
                    VALUES (new.id, new.project_path, new.file_path, new.name, new.content);
                END
                """);
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_schema_state (
                    schema_key TEXT PRIMARY KEY,
                    schema_value TEXT NOT NULL
                )
                """);
        boolean rebuild;
        try (ResultSet state = stmt.executeQuery(
                "SELECT schema_value FROM rag_schema_state WHERE schema_key='fts_version'")) {
            rebuild = !state.next() || !"1".equals(state.getString(1));
        }
        if (rebuild) {
            stmt.execute("INSERT INTO code_chunks_fts(code_chunks_fts) VALUES('rebuild')");
            stmt.execute("""
                    INSERT INTO rag_schema_state(schema_key, schema_value) VALUES('fts_version', '1')
                    ON CONFLICT(schema_key) DO UPDATE SET schema_value=excluded.schema_value
                    """);
        }
    }

    boolean fullTextIndexNeedsRebuild(Statement stmt) throws SQLException {
        try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(code_chunks_fts)")) {
            boolean exists = false;
            boolean hasProjectPath = false;
            while (columns.next()) {
                exists = true;
                if ("project_path".equalsIgnoreCase(columns.getString(2))) {
                    hasProjectPath = true;
                }
            }
            return exists && !hasProjectPath;
        }
    }

    void migrateLegacyEmbeddings(String table, String idColumn) throws SQLException {
        List<LegacyEmbedding> legacy = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + idColumn
                     + ", embedding_json FROM " + table
                     + " WHERE embedding_blob IS NULL AND embedding_json IS NOT NULL")) {
            while (rows.next()) {
                legacy.add(new LegacyEmbedding(rows.getLong(1), decode.apply(rows.getString(2))));
            }
        }
        if (legacy.isEmpty()) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + table
                + " SET embedding_blob = ?, embedding_json = NULL WHERE " + idColumn + " = ?")) {
            for (LegacyEmbedding row : legacy) {
                update.setBytes(1, VectorCodec.encode(row.vector()));
                update.setLong(2, row.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    void addColumnIfMissing(Statement stmt, String table, String column, String definition) throws SQLException {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            if (!e.getMessage().toLowerCase().contains("duplicate column")) {
                throw e;
            }
        }
    }

    record LegacyEmbedding(long id, float[] vector) {
    }
}
