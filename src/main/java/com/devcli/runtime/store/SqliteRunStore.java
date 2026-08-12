package com.devcli.runtime.store;

import com.devcli.runtime.RunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQLite 本地 RunStore。单连接由同步方法串行使用，跨进程状态竞争依赖 version/CAS。 */
public final class SqliteRunStore implements RunStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SCHEMA_VERSION = 1;

    private final Path dbPath;
    private final Connection connection;

    public SqliteRunStore(Path dbPath) throws SQLException {
        this.dbPath = normalizeDbPath(dbPath);
        try {
            Path parent = this.dbPath.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception error) {
            throw new SQLException("无法创建 RunStore 目录: " + error.getMessage(), error);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
        initialize();
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("devcli.runtime.dir");
        if (configured == null || configured.isBlank()) configured = System.getenv("DEVCLI_RUNTIME_DIR");
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".devcli", "runtime").toString();
        }
        return Path.of(configured).resolve("runtime.db");
    }

    public Path dbPath() {
        return dbPath;
    }

    @Override
    public synchronized RunRecord submit(RunSubmission submission) {
        if (!submission.idempotencyKey().isBlank()) {
            Optional<RunRecord> existing = findByIdempotency(submission.source(), submission.idempotencyKey());
            if (existing.isPresent()) return existing.get();
        }
        String id = submission.runId() == null || submission.runId().isBlank()
                ? "run_" + shortId() : submission.runId().trim();
        String now = Instant.now().toString();
        String project = submission.projectPath() == null ? ""
                : submission.projectPath().toAbsolutePath().normalize().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO runs(
                    id, source, status, thread_id, project_path, prompt,
                    result, error, idempotency_key, version, current_attempt,
                    budget_state_json, checkpoint_ref, patch_journal_ref, snapshot_ref,
                    created_at, updated_at, duration_ms
                ) VALUES (?, ?, ?, ?, ?, ?, '', '', ?, 0, 0, ?, '', '', '', ?, ?, 0)
                """)) {
            statement.setString(1, id);
            statement.setString(2, submission.source().value());
            statement.setString(3, RunStatus.ENQUEUED.value());
            statement.setString(4, submission.threadId());
            statement.setString(5, project);
            statement.setString(6, submission.prompt());
            statement.setString(7, nullIfBlank(submission.idempotencyKey()));
            statement.setString(8, submission.budgetStateJson());
            statement.setString(9, now);
            statement.setString(10, now);
            statement.executeUpdate();
            return find(id).orElseThrow();
        } catch (SQLException error) {
            if (!submission.idempotencyKey().isBlank()) {
                Optional<RunRecord> raced = findByIdempotency(submission.source(), submission.idempotencyKey());
                if (raced.isPresent()) return raced.get();
            }
            throw storageFailure("提交 Run", error);
        }
    }

    @Override
    public synchronized Optional<RunRecord> find(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM runs WHERE id = ?")) {
            statement.setString(1, runId.trim());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readRun(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw storageFailure("读取 Run", error);
        }
    }

    @Override
    public synchronized List<RunRecord> list(SubmissionSource source, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<RunRecord> result = new ArrayList<>();
        String sql = source == null
                ? "SELECT * FROM runs ORDER BY created_at DESC, id DESC LIMIT ?"
                : "SELECT * FROM runs WHERE source = ? ORDER BY created_at DESC, id DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (source != null) statement.setString(index++, source.value());
            statement.setInt(index, bounded);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(readRun(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw storageFailure("列出 Run", error);
        }
    }

    @Override
    public synchronized Optional<ClaimedRun> claimNext(
            SubmissionSource source, String workerId, Duration leaseDuration) {
        return claim(source, null, workerId, leaseDuration);
    }

    @Override
    public synchronized Optional<ClaimedRun> claimNextById(
            String runId, String workerId, Duration leaseDuration) {
        return claim(null, requireText(runId, "runId"), workerId, leaseDuration);
    }

    private Optional<ClaimedRun> claim(SubmissionSource source, String runId,
                                       String workerId, Duration leaseDuration) {
        String worker = requireText(workerId, "workerId");
        Duration lease = positiveLease(leaseDuration);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(lease);
        try {
            connection.setAutoCommit(false);
            RunRecord candidate = runId == null
                    ? selectClaimCandidate(source) : selectClaimCandidate(runId);
            if (candidate == null) {
                connection.commit();
                return Optional.empty();
            }
            long sequence = candidate.currentAttempt() + 1;
            String attemptId = candidate.id() + ":attempt:" + sequence;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runs
                    SET status = ?, version = version + 1, current_attempt = ?,
                        lease_expires_at = ?, started_at = COALESCE(started_at, ?), updated_at = ?
                    WHERE id = ? AND version = ? AND status = ?
                    """)) {
                update.setString(1, RunStatus.RUNNING.value());
                update.setLong(2, sequence);
                update.setString(3, expiresAt.toString());
                update.setString(4, now.toString());
                update.setString(5, now.toString());
                update.setString(6, candidate.id());
                update.setLong(7, candidate.version());
                update.setString(8, candidate.status().value());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return Optional.empty();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO run_attempts(
                        id, run_id, sequence, status, worker_id, reason,
                        lease_expires_at, started_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, '', ?, ?, ?)
                    """)) {
                insert.setString(1, attemptId);
                insert.setString(2, candidate.id());
                insert.setLong(3, sequence);
                insert.setString(4, AttemptStatus.RUNNING.value());
                insert.setString(5, worker);
                insert.setString(6, expiresAt.toString());
                insert.setString(7, now.toString());
                insert.setString(8, now.toString());
                insert.executeUpdate();
            }
            connection.commit();
            return Optional.of(new ClaimedRun(
                    find(candidate.id()).orElseThrow(), currentAttempt(candidate.id()).orElseThrow()));
        } catch (SQLException error) {
            rollbackQuietly();
            throw storageFailure("认领 Run", error);
        } finally {
            autoCommitQuietly();
        }
    }

    @Override
    public synchronized boolean renewLease(String runId, long expectedVersion,
                                            String attemptId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(positiveLease(leaseDuration));
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runs SET lease_expires_at = ?, updated_at = ?, version = version + 1
                    WHERE id = ? AND version = ? AND status = ?
                    """)) {
                update.setString(1, expiresAt.toString());
                update.setString(2, now.toString());
                update.setString(3, runId);
                update.setLong(4, expectedVersion);
                update.setString(5, RunStatus.RUNNING.value());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement updateAttempt = connection.prepareStatement("""
                    UPDATE run_attempts SET lease_expires_at = ?, updated_at = ?
                    WHERE id = ? AND run_id = ? AND status = ?
                    """)) {
                updateAttempt.setString(1, expiresAt.toString());
                updateAttempt.setString(2, now.toString());
                updateAttempt.setString(3, attemptId);
                updateAttempt.setString(4, runId);
                updateAttempt.setString(5, AttemptStatus.RUNNING.value());
                if (updateAttempt.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            connection.commit();
            return true;
        } catch (SQLException error) {
            rollbackQuietly();
            throw storageFailure("续租 Run", error);
        } finally {
            autoCommitQuietly();
        }
    }

    @Override
    public synchronized boolean complete(
            String runId, long expectedVersion, String attemptId,
            RunStatus terminalStatus, String result, String error,
            RunContext.RunBudgetState budgetState) {
        if (terminalStatus == null || !terminalStatus.terminal()) {
            throw new IllegalArgumentException("terminalStatus is required");
        }
        Instant now = Instant.now();
        try {
            connection.setAutoCommit(false);
            RunRecord current = find(runId).orElse(null);
            if (current == null || current.version() != expectedVersion
                    || current.status() != RunStatus.RUNNING) {
                connection.rollback();
                return false;
            }
            long duration = current.startedAt() == null ? 0
                    : Math.max(0, now.toEpochMilli() - current.startedAt().toEpochMilli());
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE runs
                    SET status = ?, result = ?, error = ?, finished_at = ?,
                        duration_ms = ?, updated_at = ?, lease_expires_at = NULL,
                        budget_state_json = ?, version = version + 1
                    WHERE id = ? AND version = ? AND status = ?
                    """)) {
                update.setString(1, terminalStatus.value());
                update.setString(2, text(result));
                update.setString(3, text(error));
                update.setString(4, now.toString());
                update.setLong(5, duration);
                update.setString(6, now.toString());
                update.setString(7, encodeBudget(budgetState));
                update.setString(8, runId);
                update.setLong(9, expectedVersion);
                update.setString(10, RunStatus.RUNNING.value());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            AttemptStatus attemptStatus = switch (terminalStatus) {
                case COMPLETED -> AttemptStatus.COMPLETED;
                case CANCELED -> AttemptStatus.CANCELED;
                default -> AttemptStatus.FAILED;
            };
            try (PreparedStatement updateAttempt = connection.prepareStatement("""
                    UPDATE run_attempts SET status = ?, reason = ?, finished_at = ?, updated_at = ?
                    WHERE id = ? AND run_id = ? AND status = ?
                    """)) {
                updateAttempt.setString(1, attemptStatus.value());
                updateAttempt.setString(2, text(error));
                updateAttempt.setString(3, now.toString());
                updateAttempt.setString(4, now.toString());
                updateAttempt.setString(5, attemptId);
                updateAttempt.setString(6, runId);
                updateAttempt.setString(7, AttemptStatus.RUNNING.value());
                if (updateAttempt.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            connection.commit();
            return true;
        } catch (SQLException storageError) {
            rollbackQuietly();
            throw storageFailure("完成 Run", storageError);
        } finally {
            autoCommitQuietly();
        }
    }

    @Override
    public synchronized boolean cancel(String runId, long expectedVersion, String reason,
                                       RunContext.RunBudgetState budgetState) {
        RunRecord current = find(runId).orElse(null);
        if (current == null || current.version() != expectedVersion || current.terminal()) return false;
        if (current.status() == RunStatus.RUNNING) {
            Optional<AttemptRecord> attempt = currentAttempt(runId);
            if (attempt.isPresent()) {
                return complete(runId, expectedVersion, attempt.get().id(), RunStatus.CANCELED,
                        current.result(), reason, budgetState);
            }
        }
        Instant now = Instant.now();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runs SET status = ?, error = ?, finished_at = ?, updated_at = ?,
                    budget_state_json = ?, lease_expires_at = NULL, version = version + 1
                WHERE id = ? AND version = ? AND status IN (?, ?)
                """)) {
            update.setString(1, RunStatus.CANCELED.value());
            update.setString(2, text(reason));
            update.setString(3, now.toString());
            update.setString(4, now.toString());
            update.setString(5, encodeBudget(budgetState));
            update.setString(6, runId);
            update.setLong(7, expectedVersion);
            update.setString(8, RunStatus.ENQUEUED.value());
            update.setString(9, RunStatus.RECOVERY_REQUIRED.value());
            return update.executeUpdate() == 1;
        } catch (SQLException error) {
            throw storageFailure("取消 Run", error);
        }
    }

    @Override
    public synchronized boolean saveBudgetState(
            String runId, long expectedVersion, RunContext.RunBudgetState budgetState) {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runs SET budget_state_json = ?, updated_at = ?, version = version + 1
                WHERE id = ? AND version = ?
                """)) {
            update.setString(1, encodeBudget(budgetState));
            update.setString(2, Instant.now().toString());
            update.setString(3, runId);
            update.setLong(4, expectedVersion);
            return update.executeUpdate() == 1;
        } catch (SQLException error) {
            throw storageFailure("保存预算状态", error);
        }
    }

    @Override
    public synchronized boolean saveRecoveryReferences(
            String runId, long expectedVersion, String checkpointRef,
            String patchJournalRef, String snapshotRef) {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runs SET checkpoint_ref = ?, patch_journal_ref = ?, snapshot_ref = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND version = ?
                """)) {
            update.setString(1, text(checkpointRef));
            update.setString(2, text(patchJournalRef));
            update.setString(3, text(snapshotRef));
            update.setString(4, Instant.now().toString());
            update.setString(5, runId);
            update.setLong(6, expectedVersion);
            return update.executeUpdate() == 1;
        } catch (SQLException error) {
            throw storageFailure("保存恢复引用", error);
        }
    }

    @Override
    public synchronized boolean linkCheckpointByThread(String threadId, String checkpointRef) {
        if (threadId == null || threadId.isBlank()) return false;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runs SET checkpoint_ref = ?, updated_at = ?, version = version + 1
                WHERE id = (
                    SELECT id FROM runs
                    WHERE thread_id = ? AND status = ?
                    ORDER BY finished_at DESC, created_at DESC LIMIT 1
                )
                """)) {
            update.setString(1, text(checkpointRef));
            update.setString(2, Instant.now().toString());
            update.setString(3, threadId);
            update.setString(4, RunStatus.COMPLETED.value());
            return update.executeUpdate() == 1;
        } catch (SQLException error) {
            throw storageFailure("关联 Runtime checkpoint", error);
        }
    }

    @Override
    public synchronized boolean clearRecoveryReferences(
            String runId, long expectedVersion, boolean checkpoint,
            boolean patchJournal, boolean snapshot) {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE runs SET
                    checkpoint_ref = CASE WHEN ? THEN '' ELSE checkpoint_ref END,
                    patch_journal_ref = CASE WHEN ? THEN '' ELSE patch_journal_ref END,
                    snapshot_ref = CASE WHEN ? THEN '' ELSE snapshot_ref END,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND version = ?
                """)) {
            update.setBoolean(1, checkpoint);
            update.setBoolean(2, patchJournal);
            update.setBoolean(3, snapshot);
            update.setString(4, Instant.now().toString());
            update.setString(5, runId);
            update.setLong(6, expectedVersion);
            return update.executeUpdate() == 1;
        } catch (SQLException error) {
            throw storageFailure("清理恢复引用", error);
        }
    }

    @Override
    public synchronized List<RunRecord> reconcileExpiredLeases() {
        Instant now = Instant.now();
        List<RunRecord> expired = new ArrayList<>();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT * FROM runs
                    WHERE status = ? AND lease_expires_at IS NOT NULL AND lease_expires_at <= ?
                    ORDER BY created_at, id
                    """)) {
                select.setString(1, RunStatus.RUNNING.value());
                select.setString(2, now.toString());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) expired.add(readRun(rows));
                }
            }
            for (RunRecord run : expired) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE runs SET status = ?, error = ?, lease_expires_at = NULL,
                            updated_at = ?, version = version + 1
                        WHERE id = ? AND version = ? AND status = ?
                        """)) {
                    update.setString(1, RunStatus.RECOVERY_REQUIRED.value());
                    update.setString(2, "worker_lease_expired");
                    update.setString(3, now.toString());
                    update.setString(4, run.id());
                    update.setLong(5, run.version());
                    update.setString(6, RunStatus.RUNNING.value());
                    update.executeUpdate();
                }
                try (PreparedStatement attempt = connection.prepareStatement("""
                        UPDATE run_attempts SET status = ?, reason = ?, finished_at = ?, updated_at = ?
                        WHERE run_id = ? AND sequence = ? AND status = ?
                        """)) {
                    attempt.setString(1, AttemptStatus.ABANDONED.value());
                    attempt.setString(2, "worker_lease_expired");
                    attempt.setString(3, now.toString());
                    attempt.setString(4, now.toString());
                    attempt.setString(5, run.id());
                    attempt.setLong(6, run.currentAttempt());
                    attempt.setString(7, AttemptStatus.RUNNING.value());
                    attempt.executeUpdate();
                }
            }
            connection.commit();
            return expired.stream().map(run -> find(run.id()).orElse(run)).toList();
        } catch (SQLException error) {
            rollbackQuietly();
            throw storageFailure("对账过期租约", error);
        } finally {
            autoCommitQuietly();
        }
    }

    @Override
    public synchronized Optional<AttemptRecord> currentAttempt(String runId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM run_attempts WHERE run_id = ? ORDER BY sequence DESC LIMIT 1
                """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readAttempt(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw storageFailure("读取 Run Attempt", error);
        }
    }

    @Override
    public synchronized Optional<String> latestActiveRunId() {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM runs WHERE status IN (?, ?, ?)
                ORDER BY updated_at DESC, created_at DESC LIMIT 1
                """)) {
            statement.setString(1, RunStatus.RUNNING.value());
            statement.setString(2, RunStatus.ENQUEUED.value());
            statement.setString(3, RunStatus.RECOVERY_REQUIRED.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw storageFailure("读取当前 Run", error);
        }
    }

    public synchronized Optional<RunContext.RunBudgetState> budgetState(String runId) {
        Optional<RunRecord> record = find(runId);
        if (record.isEmpty() || record.get().budgetStateJson().isBlank()) return Optional.empty();
        try {
            return Optional.of(decodeBudget(record.get().budgetStateJson()));
        } catch (Exception error) {
            throw new IllegalStateException("解析预算状态失败: " + error.getMessage(), error);
        }
    }

    /** 将旧 tasks.db 数据幂等迁入统一 runs 表，不修改旧数据库。 */
    public synchronized int migrateLegacyTasks(Path legacyDbPath) {
        Path legacy = legacyDbPath == null ? null : legacyDbPath.toAbsolutePath().normalize();
        if (legacy == null || !Files.isRegularFile(legacy) || legacy.equals(dbPath)) return 0;
        int imported = 0;
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + legacy)) {
            if (!tableExists(source, "runtime_tasks")) return 0;
            try (Statement statement = source.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM runtime_tasks ORDER BY created_at, id")) {
                while (rows.next()) {
                    String legacyId = rows.getString("id");
                    if (find(legacyId).isPresent()) continue;
                    importLegacyRow(rows);
                    imported++;
                }
            }
            return imported;
        } catch (SQLException error) {
            throw storageFailure("迁移旧后台任务", error);
        }
    }

    public synchronized int importLegacyTasksFromCurrentDatabase() {
        try {
            if (!tableExists(connection, "runtime_tasks")) return 0;
            int imported = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT * FROM runtime_tasks ORDER BY created_at, id")) {
                while (rows.next()) {
                    String legacyId = rows.getString("id");
                    if (find(legacyId).isPresent()) continue;
                    importLegacyRow(rows);
                    imported++;
                }
            }
            return imported;
        } catch (SQLException error) {
            throw storageFailure("迁移同库旧后台任务", error);
        }
    }

    private void importLegacyRow(ResultSet row) throws SQLException {
        String status = legacyRunStatus(row.getString("status")).value();
        String created = valueOr(row.getString("created_at"), Instant.now().toString());
        String updated = valueOr(row.getString("updated_at"), created);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO runs(
                    id, source, status, thread_id, project_path, prompt, result, error,
                    idempotency_key, version, current_attempt, lease_expires_at,
                    budget_state_json, checkpoint_ref, patch_journal_ref, snapshot_ref,
                    created_at, started_at, finished_at, updated_at, duration_ms
                ) VALUES (?, ?, ?, '', '', ?, ?, ?, NULL, 0, 0, NULL, '', '', '', '',
                          ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, row.getString("id"));
            insert.setString(2, SubmissionSource.BACKGROUND.value());
            insert.setString(3, status);
            insert.setString(4, row.getString("prompt"));
            insert.setString(5, text(row.getString("result")));
            insert.setString(6, text(row.getString("error")));
            insert.setString(7, created);
            insert.setString(8, row.getString("started_at"));
            insert.setString(9, row.getString("finished_at"));
            insert.setString(10, updated);
            insert.setLong(11, row.getLong("duration_ms"));
            insert.executeUpdate();
        }
    }

    private RunRecord selectClaimCandidate(SubmissionSource source) throws SQLException {
        String sql = source == null
                ? "SELECT * FROM runs WHERE status IN (?, ?) ORDER BY created_at, id LIMIT 1"
                : "SELECT * FROM runs WHERE source = ? AND status IN (?, ?) ORDER BY created_at, id LIMIT 1";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            int index = 1;
            if (source != null) select.setString(index++, source.value());
            select.setString(index++, RunStatus.ENQUEUED.value());
            select.setString(index, RunStatus.RECOVERY_REQUIRED.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? readRun(rows) : null;
            }
        }
    }

    private RunRecord selectClaimCandidate(String runId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT * FROM runs WHERE id = ? AND status IN (?, ?) LIMIT 1
                """)) {
            select.setString(1, runId);
            select.setString(2, RunStatus.ENQUEUED.value());
            select.setString(3, RunStatus.RECOVERY_REQUIRED.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? readRun(rows) : null;
            }
        }
    }

    private Optional<RunRecord> findByIdempotency(
            SubmissionSource source, String idempotencyKey) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM runs WHERE source = ? AND idempotency_key = ? LIMIT 1
                """)) {
            statement.setString(1, source.value());
            statement.setString(2, idempotencyKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readRun(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw storageFailure("读取幂等 Run", error);
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS run_store_schema (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS runs (
                        id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        status TEXT NOT NULL,
                        thread_id TEXT NOT NULL DEFAULT '',
                        project_path TEXT NOT NULL DEFAULT '',
                        prompt TEXT NOT NULL,
                        result TEXT NOT NULL DEFAULT '',
                        error TEXT NOT NULL DEFAULT '',
                        idempotency_key TEXT,
                        version INTEGER NOT NULL DEFAULT 0,
                        current_attempt INTEGER NOT NULL DEFAULT 0,
                        lease_expires_at TEXT,
                        budget_state_json TEXT NOT NULL DEFAULT '',
                        checkpoint_ref TEXT NOT NULL DEFAULT '',
                        patch_journal_ref TEXT NOT NULL DEFAULT '',
                        snapshot_ref TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        finished_at TEXT,
                        updated_at TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS run_attempts (
                        id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        worker_id TEXT NOT NULL,
                        reason TEXT NOT NULL DEFAULT '',
                        lease_expires_at TEXT,
                        started_at TEXT NOT NULL,
                        finished_at TEXT,
                        updated_at TEXT NOT NULL,
                        UNIQUE(run_id, sequence),
                        FOREIGN KEY(run_id) REFERENCES runs(id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_runs_source_status_created "
                    + "ON runs(source, status, created_at)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_runs_idempotency "
                    + "ON runs(source, idempotency_key) WHERE idempotency_key IS NOT NULL");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_run_attempts_run "
                    + "ON run_attempts(run_id, sequence)");
            try (PreparedStatement version = connection.prepareStatement(
                    "INSERT OR IGNORE INTO run_store_schema(version, applied_at) VALUES (?, ?)")) {
                version.setInt(1, SCHEMA_VERSION);
                version.setString(2, Instant.now().toString());
                version.executeUpdate();
            }
        }
    }

    private static RunRecord readRun(ResultSet row) throws SQLException {
        return new RunRecord(
                row.getString("id"), SubmissionSource.from(row.getString("source")),
                RunStatus.from(row.getString("status")), row.getString("thread_id"),
                row.getString("project_path"), row.getString("prompt"),
                row.getString("result"), row.getString("error"),
                row.getString("idempotency_key"), row.getLong("version"),
                row.getLong("current_attempt"), instant(row.getString("lease_expires_at")),
                row.getString("budget_state_json"), row.getString("checkpoint_ref"),
                row.getString("patch_journal_ref"), row.getString("snapshot_ref"),
                instant(row.getString("created_at")), instant(row.getString("started_at")),
                instant(row.getString("finished_at")), instant(row.getString("updated_at")),
                row.getLong("duration_ms"));
    }

    private static AttemptRecord readAttempt(ResultSet row) throws SQLException {
        return new AttemptRecord(
                row.getString("id"), row.getString("run_id"), row.getLong("sequence"),
                AttemptStatus.from(row.getString("status")), row.getString("worker_id"),
                row.getString("reason"), instant(row.getString("lease_expires_at")),
                instant(row.getString("started_at")), instant(row.getString("finished_at")),
                instant(row.getString("updated_at")));
    }

    private static RunStatus legacyRunStatus(String status) {
        RunStatus parsed = RunStatus.from(status);
        return parsed == RunStatus.RUNNING ? RunStatus.RECOVERY_REQUIRED : parsed;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static String encodeBudget(RunContext.RunBudgetState budgetState) {
        if (budgetState == null) return "";
        try {
            var root = MAPPER.createObjectNode();
            root.put("schemaVersion", budgetState.schemaVersion());
            root.put("runId", budgetState.runId());
            root.set("policy", MAPPER.valueToTree(budgetState.policy()));
            root.set("usage", MAPPER.valueToTree(budgetState.usage()));
            root.put("updatedAt", budgetState.updatedAt().toString());
            return MAPPER.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalArgumentException("预算状态无法序列化", error);
        }
    }

    public static RunContext.RunBudgetState decodeBudget(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return new RunContext.RunBudgetState(
                    root.path("schemaVersion").asInt(1),
                    root.path("runId").asText(""),
                    MAPPER.treeToValue(root.path("policy"), com.devcli.budget.RunBudgetPolicy.class),
                    MAPPER.treeToValue(root.path("usage"), com.devcli.budget.RunBudget.Snapshot.class),
                    Instant.parse(root.path("updatedAt").asText()));
        } catch (Exception error) {
            throw new IllegalArgumentException("预算状态无法解析", error);
        }
    }

    private static Path normalizeDbPath(Path path) {
        if (path == null) throw new IllegalArgumentException("dbPath is required");
        return path.toAbsolutePath().normalize();
    }

    private static Duration positiveLease(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return duration;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private IllegalStateException storageFailure(String operation, SQLException error) {
        return new IllegalStateException(operation + " 失败: " + error.getMessage(), error);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void autoCommitQuietly() {
        try {
            connection.setAutoCommit(true);
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
