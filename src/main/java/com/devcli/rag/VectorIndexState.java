package com.devcli.rag;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import static com.devcli.rag.VectorSearch.safeIndexEpoch;
import static com.devcli.rag.VectorSearch.blankToDefault;

/** 索引版本、失效路径与 watcher 元数据；使用调用方的同一连接和事务。 */
final class VectorIndexState {
    private final Connection connection;
    private final String projectPath;
    private final VectorSearch search;
    VectorIndexState(Connection connection, String projectPath, VectorSearch search) {
        this.connection = connection;
        this.projectPath = projectPath;
        this.search = search;
    }
    void insertDirtyFiles(List<String> filePaths) throws SQLException {
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

    List<String> dirtyPathAliases(List<String> filePaths) {
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
                aliases.add(search.relativeProjectPath(absolute));
            }
        }
        return List.copyOf(aliases);
    }

    void upsertIndexState(String epoch, String status, boolean incrementGeneration)
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

    void clearDirtyFiles() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM code_index_dirty_files WHERE project_path = ?")) {
            statement.setString(1, projectPath);
            statement.executeUpdate();
        }
    }

    String currentIndexEpoch() throws SQLException {
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

    VectorStore.IndexWatchSnapshot indexWatchSnapshot() throws SQLException {
        LinkedHashSet<String> indexedPaths = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT file_path FROM code_chunks
                WHERE project_path = ?
                """)) {
            statement.setString(1, projectPath);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    search.resolveProjectPath(rs.getString(1))
                            .map(search::relativeProjectPath)
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
        return new VectorStore.IndexWatchSnapshot(Set.copyOf(indexedPaths), updatedAt);
    }

    long currentIndexGeneration() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT generation FROM code_index_state WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getLong(1)) : 0;
            }
        }
    }
}
