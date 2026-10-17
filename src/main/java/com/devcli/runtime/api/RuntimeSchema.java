package com.devcli.runtime.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Runtime SQLite 结构与已有表的列迁移；不吞掉结构写入失败。 */
final class RuntimeSchema {
    private RuntimeSchema() {}

    static void initialize(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_threads (
                        id TEXT PRIMARY KEY,
                        active_branch_id TEXT NOT NULL DEFAULT 'main',
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        thread_id TEXT NOT NULL,
                        branch_id TEXT NOT NULL DEFAULT 'main',
                        type TEXT NOT NULL,
                        data TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_events_thread ON runtime_events(thread_id, id)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        thread_id TEXT NOT NULL,
                        branch_id TEXT NOT NULL DEFAULT 'main',
                        covered_through_event_id INTEGER NOT NULL,
                        messages_json TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        metadata_json TEXT NOT NULL,
                        message_tree_json TEXT NOT NULL DEFAULT '[]',
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_branches (
                        id TEXT NOT NULL,
                        thread_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        parent_branch_id TEXT NOT NULL DEFAULT '',
                        fork_event_id INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(thread_id, id)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_queues (
                        thread_id TEXT NOT NULL,
                        branch_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        channel TEXT NOT NULL,
                        text TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(thread_id, branch_id, sequence)
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_session_projections (
                        thread_id TEXT NOT NULL,
                        branch_id TEXT NOT NULL,
                        projection_version INTEGER NOT NULL,
                        log_identity TEXT NOT NULL,
                        event_cursor INTEGER NOT NULL,
                        data_json TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(thread_id, branch_id)
                    )
                    """);
            if (!hasColumn(connection, "runtime_threads", "active_branch_id"))
                stmt.execute("ALTER TABLE runtime_threads ADD COLUMN active_branch_id TEXT NOT NULL DEFAULT 'main'");
            if (!hasColumn(connection, "runtime_events", "branch_id"))
                stmt.execute("ALTER TABLE runtime_events ADD COLUMN branch_id TEXT NOT NULL DEFAULT 'main'");
            if (!hasColumn(connection, "runtime_events", "run_id"))
                stmt.execute("ALTER TABLE runtime_events ADD COLUMN run_id TEXT NOT NULL DEFAULT ''");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_events_run ON runtime_events(run_id, id)");
            if (!hasColumn(connection, "runtime_checkpoints", "message_tree_json"))
                stmt.execute("ALTER TABLE runtime_checkpoints ADD COLUMN message_tree_json TEXT NOT NULL DEFAULT '[]'");
            if (!hasColumn(connection, "runtime_checkpoints", "branch_id"))
                stmt.execute("ALTER TABLE runtime_checkpoints ADD COLUMN branch_id TEXT NOT NULL DEFAULT 'main'");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_events_branch "
                    + "ON runtime_events(thread_id, branch_id, id)");
            stmt.execute("DROP INDEX IF EXISTS idx_runtime_checkpoint_coverage");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_runtime_checkpoint_coverage "
                    + "ON runtime_checkpoints(thread_id, branch_id, covered_through_event_id)");
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var columns = statement.executeQuery()) {
                return columns.next();
            }
        }
    }
}
