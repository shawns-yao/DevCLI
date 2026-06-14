package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证旧库（无 subject/active/superseded_by 列）被 SqliteLongTermMemoryStore 平滑升级（addColumnIfMissing）。
 */
class SqliteLongTermMemoryStoreMigrationTest {

    @Test
    void legacyTableWithoutNewColumnsUpgradesAndDefaultsActive(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("memory_vectors.db");

        // 1. 手工建旧 schema（无 subject/active/superseded_by），插一行
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE memory_facts (
                        id TEXT PRIMARY KEY,
                        content TEXT NOT NULL,
                        type TEXT NOT NULL,
                        timestamp_ms INTEGER NOT NULL,
                        metadata_json TEXT NOT NULL DEFAULT '{}',
                        token_count INTEGER NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("INSERT INTO memory_facts(id, content, type, timestamp_ms, metadata_json, token_count) "
                    + "VALUES ('old-1', '旧库事实', 'FACT', 1700000000000, '{}', 5)");
        }

        // 2. 新版 store 打开同库，触发幂等补列后正常读出
        try (SqliteLongTermMemoryStore store = new SqliteLongTermMemoryStore(tempDir)) {
            assertTrue(store.isPersistent(), "旧库补列后应可用");
            List<MemoryEntry> all = store.loadAll();
            assertEquals(1, all.size());
            MemoryEntry old = all.get(0);
            assertEquals("old-1", old.getId());
            assertTrue(old.isActive(), "旧行升级后应默认 active=true");
            assertTrue(old.getSubject().isBlank(), "旧行升级后 subject 默认空串");
            assertTrue(old.getSupersededBy().isBlank());
        }
    }
}
