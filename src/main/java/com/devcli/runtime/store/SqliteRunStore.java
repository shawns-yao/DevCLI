package com.devcli.runtime.store;

import org.sqlite.SQLiteConfig;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** SQLite RunStore；所有状态迁移都使用状态和版本双重条件。 */
public final class SqliteRunStore implements RunStore {
    private final Path dbPath;
    private final Connection connection;

    public SqliteRunStore(Path dbPath) throws SQLException {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath is required");
        }
        this.dbPath = dbPath.toAbsolutePath().normalize();
        try {
            Path parent = this.dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new SQLException("无法创建 RunStore 目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
        initSchema();
    }

    @Override
    public synchronized RunRecord create(Submission submission) {
        if (submission == null || submission.prompt().isBlank()) {
            throw new IllegalArgumentException("Run prompt 不能为空");
        }
        String id = submission.id().isBlank() ? generatedId(submission.source()) : submission.id();
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runtime_runs(
                    id, thread_id, branch_id, source, execution_policy, status,
                    prompt, result, error, recovery_reason, created_at, updated_at,
                    duration_ms, attempt, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '', '', '', ?, ?, 0, 0, 0)
                """)) {
            statement.setString(1, id);
            statement.setString(2, submission.threadId());
            statement.setString(3, submission.branchId());
            statement.setString(4, submission.source().name());
            statement.setString(5, submission.executionPolicy());
            statement.setString(6, Status.ENQUEUED.name());
            statement.setString(7, submission.prompt());
            statement.setString(8, now);
            statement.setString(9, now);
            statement.executeUpdate();
            return find(id).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("创建 Run 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<RunRecord> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM runtime_runs WHERE id = ?")) {
            statement.setString(1, id.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(fromRow(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<RunRecord> list(Source source, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<RunRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM runtime_runs
                WHERE source = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """)) {
            statement.setString(1, source.name());
            statement.setInt(2, bounded);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(fromRow(result));
                }
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("读取 Run 列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<RunRecord> claimNext(Source source) {
        try {
            connection.setAutoCommit(false);
            RunRecord candidate = null;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT * FROM runtime_runs
                    WHERE source = ? AND status = ?
                    ORDER BY created_at ASC, id ASC
                    LIMIT 1
                    """)) {
                select.setString(1, source.name());
                select.setString(2, Status.ENQUEUED.name());
                try (ResultSet result = select.executeQuery()) {
                    if (result.next()) {
                        candidate = fromRow(result);
                    }
                }
            }
            if (candidate == null) {
                connection.commit();
                return Optional.empty();
            }
            if (!startInCurrentTransaction(candidate)) {
                connection.rollback();
                return Optional.empty();
            }
            connection.commit();
            return find(candidate.id());
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("领取 Run 失败: " + e.getMessage(), e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public synchronized boolean start(String id) {
        Optional<RunRecord> current = find(id);
        if (current.isEmpty() || current.get().status() != Status.ENQUEUED) {
            return false;
        }
        try {
            return startInCurrentTransaction(current.get());
        } catch (SQLException e) {
            throw new IllegalStateException("启动 Run 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean complete(String id, String result) {
        return transition(id, Status.COMPLETED, result, "", "");
    }

    @Override
    public synchronized boolean fail(String id, String error) {
        return transition(id, Status.FAILED, "", error, "");
    }

    @Override
    public synchronized boolean reject(String id, String reason) {
        return transition(id, Status.REJECTED, "", reason, "");
    }

    @Override
    public synchronized boolean cancel(String id, String reason) {
        return transition(id, Status.CANCELED, "", reason, "");
    }

    @Override
    public synchronized Optional<RunRecord> activeRun(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM runtime_runs
                WHERE thread_id = ? AND status = ?
                ORDER BY started_at DESC, created_at DESC
                LIMIT 1
                """)) {
            statement.setString(1, threadId.trim());
            statement.setString(2, Status.RUNNING.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(fromRow(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取活动 Run 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized int recoverRunning(Source source, Status target, String reason) {
        if (target != Status.ENQUEUED && target != Status.FAILED && target != Status.CANCELED) {
            throw new IllegalArgumentException("恢复目标必须为 ENQUEUED、FAILED 或 CANCELED");
        }
        String now = Instant.now().toString();
        String finishedAt = target == Status.ENQUEUED ? null : now;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_runs
                SET status = ?, recovery_reason = ?, error = ?, started_at = CASE WHEN ? = 'ENQUEUED' THEN NULL ELSE started_at END,
                    finished_at = ?, updated_at = ?, version = version + 1
                WHERE source = ? AND status = ?
                """)) {
            statement.setString(1, target.name());
            statement.setString(2, safe(reason));
            statement.setString(3, target == Status.ENQUEUED ? "" : safe(reason));
            statement.setString(4, target.name());
            statement.setString(5, finishedAt);
            statement.setString(6, now);
            statement.setString(7, source.name());
            statement.setString(8, Status.RUNNING.name());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("恢复 Run 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized RecoveryEvidenceRef upsertRecoveryEvidence(RecoveryEvidenceRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("恢复证据不能为空");
        }
        Optional<RecoveryEvidenceRef> currentOptional = findRecoveryEvidence(
                ref.runId(), ref.kind(), ref.logicalKey());
        if (currentOptional.isEmpty()) {
            String now = Instant.now().toString();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO runtime_recovery_evidence(
                        run_id, thread_id, branch_id, kind, logical_key, normalized_ref,
                        sha256, state, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """)) {
                statement.setString(1, ref.runId());
                statement.setString(2, ref.threadId());
                statement.setString(3, ref.branchId());
                statement.setString(4, ref.kind().name());
                statement.setString(5, ref.logicalKey());
                statement.setString(6, ref.normalizedRef());
                statement.setString(7, ref.sha256());
                statement.setString(8, ref.state().name());
                statement.setString(9, now);
                statement.setString(10, now);
                statement.executeUpdate();
                return findRecoveryEvidence(ref.runId(), ref.kind(), ref.logicalKey()).orElseThrow();
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT)
                        .contains("constraint")) {
                    return upsertRecoveryEvidence(ref);
                }
                throw new IllegalStateException("写入恢复证据失败: " + e.getMessage(), e);
            }
        }

        RecoveryEvidenceRef current = currentOptional.get();
        if (!current.threadId().equals(ref.threadId())
                || !current.branchId().equals(ref.branchId())) {
            throw new IllegalStateException("恢复证据身份不可变: " + ref.logicalKey());
        }
        if (current.state() != ref.state() && !allowedEvidenceTransition(current.state(), ref.state())) {
            throw new IllegalStateException("恢复证据状态迁移非法: "
                    + current.state() + " -> " + ref.state());
        }
        boolean changed = !current.normalizedRef().equals(ref.normalizedRef())
                || !current.sha256().equals(ref.sha256())
                || current.state() != ref.state();
        if (!changed) {
            return current;
        }
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_recovery_evidence
                SET normalized_ref = ?, sha256 = ?, state = ?, updated_at = ?, version = version + 1
                WHERE run_id = ? AND kind = ? AND logical_key = ? AND version = ?
                """)) {
            statement.setString(1, ref.normalizedRef());
            statement.setString(2, ref.sha256());
            statement.setString(3, ref.state().name());
            statement.setString(4, now);
            statement.setString(5, ref.runId());
            statement.setString(6, ref.kind().name());
            statement.setString(7, ref.logicalKey());
            statement.setLong(8, current.version());
            if (statement.executeUpdate() != 1) {
                return upsertRecoveryEvidence(ref);
            }
            return findRecoveryEvidence(ref.runId(), ref.kind(), ref.logicalKey()).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("更新恢复证据失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<RecoveryEvidenceRef> listRecoveryEvidence(String runId, int limit) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 1000));
        List<RecoveryEvidenceRef> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id, thread_id, branch_id, kind, logical_key, normalized_ref,
                       sha256, state, created_at, updated_at, version
                FROM runtime_recovery_evidence
                WHERE run_id = ?
                ORDER BY updated_at DESC, kind ASC, logical_key ASC
                LIMIT ?
                """)) {
            statement.setString(1, runId.trim());
            statement.setInt(2, bounded);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    refs.add(fromEvidenceRow(result));
                }
            }
            return List.copyOf(refs);
        } catch (SQLException e) {
            throw new IllegalStateException("读取恢复证据失败: " + e.getMessage(), e);
        }
    }

    /** 将旧 tasks.db 中的 runtime_tasks 只读导入 RunStore；重复 id 不覆盖。 */
    public synchronized int importLegacyTasks(Path legacyDbPath) throws SQLException {
        if (legacyDbPath == null || !Files.isRegularFile(legacyDbPath)) {
            return 0;
        }
        Path legacy = legacyDbPath.toAbsolutePath().normalize();
        if (legacy.equals(dbPath)) {
            return 0;
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        int imported = 0;
        try (Connection legacyConnection = DriverManager.getConnection(
                "jdbc:sqlite:" + legacy, config.toProperties())) {
            if (!tableExists(legacyConnection, "runtime_tasks")) {
                return 0;
            }
            connection.setAutoCommit(false);
            try (Statement select = legacyConnection.createStatement();
                 ResultSet rows = select.executeQuery("SELECT * FROM runtime_tasks");
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO runtime_runs(
                             id, thread_id, branch_id, source, execution_policy, status,
                             prompt, result, error, recovery_reason, created_at, started_at,
                             finished_at, updated_at, duration_ms, attempt, version
                         ) VALUES (?, '', 'main', 'BACKGROUND', 'react', ?, ?, ?, ?,
                                   'legacy_tasks_db_import', ?, ?, ?, ?, ?, ?, 0)
                         ON CONFLICT(id) DO NOTHING
                         """)) {
                while (rows.next()) {
                    String status = normalizedLegacyStatus(rows.getString("status"));
                    insert.setString(1, rows.getString("id"));
                    insert.setString(2, status);
                    insert.setString(3, safe(rows.getString("prompt")));
                    insert.setString(4, safe(rows.getString("result")));
                    insert.setString(5, safe(rows.getString("error")));
                    insert.setString(6, rows.getString("created_at"));
                    insert.setString(7, rows.getString("started_at"));
                    insert.setString(8, rows.getString("finished_at"));
                    insert.setString(9, firstNonBlank(rows.getString("updated_at"), rows.getString("created_at")));
                    insert.setLong(10, Math.max(0, rows.getLong("duration_ms")));
                    insert.setInt(11, rows.getString("started_at") == null ? 0 : 1);
                    imported += insert.executeUpdate();
                }
            }
            connection.commit();
            return imported;
        } catch (SQLException e) {
            rollbackQuietly();
            throw e;
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    @Override
    public Path dbPath() {
        return dbPath;
    }

    private boolean startInCurrentTransaction(RunRecord current) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_runs
                SET status = ?, started_at = ?, finished_at = NULL, updated_at = ?,
                    error = '', recovery_reason = '', attempt = attempt + 1, version = version + 1
                WHERE id = ? AND status = ? AND version = ?
                """)) {
            statement.setString(1, Status.RUNNING.name());
            statement.setString(2, now);
            statement.setString(3, now);
            statement.setString(4, current.id());
            statement.setString(5, Status.ENQUEUED.name());
            statement.setLong(6, current.version());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean transition(String id, Status target, String result,
                               String error, String recoveryReason) {
        Optional<RunRecord> currentOptional = find(id);
        if (currentOptional.isEmpty()) {
            return false;
        }
        RunRecord current = currentOptional.get();
        if (!allowed(current.status(), target)) {
            return false;
        }
        String now = Instant.now().toString();
        long duration = current.startedAt() == null
                ? 0
                : Math.max(0, Instant.now().toEpochMilli() - current.startedAt().toEpochMilli());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE runtime_runs
                SET status = ?, result = ?, error = ?, recovery_reason = ?,
                    finished_at = ?, updated_at = ?, duration_ms = ?, version = version + 1
                WHERE id = ? AND status = ? AND version = ?
                """)) {
            statement.setString(1, target.name());
            statement.setString(2, safe(result));
            statement.setString(3, safe(error));
            statement.setString(4, safe(recoveryReason));
            statement.setString(5, now);
            statement.setString(6, now);
            statement.setLong(7, duration);
            statement.setString(8, current.id());
            statement.setString(9, current.status().name());
            statement.setLong(10, current.version());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("迁移 Run 状态失败: " + e.getMessage(), e);
        }
    }

    private static boolean allowed(Status current, Status target) {
        if (current == Status.ENQUEUED) {
            return target == Status.RUNNING || target == Status.FAILED
                    || target == Status.CANCELED || target == Status.REJECTED;
        }
        if (current == Status.RUNNING) {
            return target == Status.COMPLETED || target == Status.FAILED || target == Status.CANCELED;
        }
        return false;
    }

    private RunRecord fromRow(ResultSet result) throws SQLException {
        return new RunRecord(
                result.getString("id"),
                result.getString("thread_id"),
                result.getString("branch_id"),
                enumValue(Source.class, result.getString("source"), Source.INTERACTIVE),
                result.getString("execution_policy"),
                enumValue(Status.class, result.getString("status"), Status.ENQUEUED),
                result.getString("prompt"),
                result.getString("result"),
                result.getString("error"),
                result.getString("recovery_reason"),
                instant(result.getString("created_at")),
                instant(result.getString("started_at")),
                instant(result.getString("finished_at")),
                instant(result.getString("updated_at")),
                result.getLong("duration_ms"),
                result.getInt("attempt"),
                result.getLong("version"));
    }

    private Optional<RecoveryEvidenceRef> findRecoveryEvidence(
            String runId, RecoveryEvidenceRef.Kind kind, String logicalKey) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id, thread_id, branch_id, kind, logical_key, normalized_ref,
                       sha256, state, created_at, updated_at, version
                FROM runtime_recovery_evidence
                WHERE run_id = ? AND kind = ? AND logical_key = ?
                """)) {
            statement.setString(1, runId);
            statement.setString(2, kind.name());
            statement.setString(3, logicalKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(fromEvidenceRow(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取恢复证据失败: " + e.getMessage(), e);
        }
    }

    private RecoveryEvidenceRef fromEvidenceRow(ResultSet result) throws SQLException {
        return new RecoveryEvidenceRef(
                result.getString("run_id"),
                result.getString("thread_id"),
                result.getString("branch_id"),
                enumValue(RecoveryEvidenceRef.Kind.class, result.getString("kind"),
                        RecoveryEvidenceRef.Kind.CHECKPOINT),
                result.getString("logical_key"),
                result.getString("normalized_ref"),
                result.getString("sha256"),
                enumValue(RecoveryEvidenceRef.State.class, result.getString("state"),
                        RecoveryEvidenceRef.State.ACTIVE),
                instant(result.getString("created_at")),
                instant(result.getString("updated_at")),
                result.getLong("version"));
    }

    private static boolean allowedEvidenceTransition(
            RecoveryEvidenceRef.State current, RecoveryEvidenceRef.State target) {
        if (current == target) {
            return true;
        }
        if (current == RecoveryEvidenceRef.State.DELETED) {
            return false;
        }
        if (target == RecoveryEvidenceRef.State.DELETED) {
            return true;
        }
        return switch (current) {
            case ACTIVE, PRESENT -> target == RecoveryEvidenceRef.State.PREPARED
                    || target == RecoveryEvidenceRef.State.PRESENT
                    || target == RecoveryEvidenceRef.State.COMPLETED
                    || target == RecoveryEvidenceRef.State.FAILED;
            case PREPARED -> target == RecoveryEvidenceRef.State.COMPLETED
                    || target == RecoveryEvidenceRef.State.ROLLED_BACK
                    || target == RecoveryEvidenceRef.State.FAILED;
            case COMPLETED, ROLLED_BACK -> false;
            case FAILED -> target == RecoveryEvidenceRef.State.COMPLETED
                    || target == RecoveryEvidenceRef.State.ROLLED_BACK;
            case DELETED -> false;
        };
    }

    private void initSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_runs (
                        id TEXT PRIMARY KEY,
                        thread_id TEXT NOT NULL DEFAULT '',
                        branch_id TEXT NOT NULL DEFAULT 'main',
                        source TEXT NOT NULL,
                        execution_policy TEXT NOT NULL,
                        status TEXT NOT NULL,
                        prompt TEXT NOT NULL,
                        result TEXT NOT NULL DEFAULT '',
                        error TEXT NOT NULL DEFAULT '',
                        recovery_reason TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        attempt INTEGER NOT NULL DEFAULT 0,
                        version INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runtime_runs_queue "
                    + "ON runtime_runs(source, status, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runtime_runs_thread "
                    + "ON runtime_runs(thread_id, status, created_at)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_recovery_evidence (
                        run_id TEXT NOT NULL,
                        thread_id TEXT NOT NULL DEFAULT '',
                        branch_id TEXT NOT NULL DEFAULT 'main',
                        kind TEXT NOT NULL,
                        logical_key TEXT NOT NULL,
                        normalized_ref TEXT NOT NULL,
                        sha256 TEXT NOT NULL DEFAULT '',
                        state TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(run_id, kind, logical_key)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runtime_recovery_evidence_run "
                    + "ON runtime_recovery_evidence(run_id, updated_at DESC, kind, logical_key)");
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static String normalizedLegacyStatus(String status) {
        String normalized = safe(status).toUpperCase(Locale.ROOT);
        try {
            return Status.valueOf(normalized).name();
        } catch (IllegalArgumentException ignored) {
            return Status.ENQUEUED.name();
        }
    }

    private static String generatedId(Source source) {
        String prefix = switch (source) {
            case BACKGROUND -> "task_";
            case RUNTIME_API -> "turn_";
            case INTERACTIVE -> "run_";
        };
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? safe(second) : first;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void setAutoCommitQuietly(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
