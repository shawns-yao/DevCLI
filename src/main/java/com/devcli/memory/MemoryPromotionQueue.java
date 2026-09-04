package com.devcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.function.Supplier;

/** SQLite write-ahead 候选队列；它是晋升协议日志，不是第四层记忆。 */
public final class MemoryPromotionQueue implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemoryPromotionQueue.class);
    private static final ObjectMapper JSON = MemoryJson.mapper();
    private static final long CLAIM_LEASE_MILLIS = 5 * 60 * 1_000L;
    private final Connection connection;
    private final String ownerId = UUID.randomUUID().toString();

    public MemoryPromotionQueue(Path memoryDir) {
        try {
            Files.createDirectories(memoryDir);
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + memoryDir.resolve("memory_vectors.db"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS memory_promotion_jobs (
                            id TEXT PRIMARY KEY,
                            snapshot_json TEXT NOT NULL,
                            state TEXT NOT NULL,
                            owner_id TEXT NOT NULL DEFAULT '',
                            result_ref TEXT NOT NULL DEFAULT '',
                            detail TEXT NOT NULL DEFAULT '',
                            created_at_ms INTEGER NOT NULL,
                            updated_at_ms INTEGER NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_promotion_state_created "
                        + "ON memory_promotion_jobs(state, created_at_ms)");
            }
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion queue initialization failed", error);
        }
    }

    public synchronized String enqueue(TaskMemorySnapshot snapshot) {
        String id = "promotion-" + UUID.randomUUID().toString().substring(0, 12);
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_promotion_jobs(
                    id, snapshot_json, state, owner_id, result_ref, detail, created_at_ms, updated_at_ms)
                VALUES (?, ?, 'PENDING', '', '', '', ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, JSON.writeValueAsString(snapshot));
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.executeUpdate();
            return id;
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion enqueue failed", error);
        }
    }

    public synchronized Optional<Job> claimNext() {
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String id = null;
                long staleBefore = Instant.now().toEpochMilli() - CLAIM_LEASE_MILLIS;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT id FROM memory_promotion_jobs
                        WHERE state IN ('PENDING', 'FAILED_RETRYABLE')
                           OR (state='CURATING' AND updated_at_ms < ?)
                        ORDER BY created_at_ms ASC LIMIT 1
                        """)) {
                    select.setLong(1, staleBefore);
                    try (ResultSet result = select.executeQuery()) {
                        if (result.next()) id = result.getString(1);
                    }
                }
                if (id == null) {
                    connection.commit();
                    return Optional.empty();
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE memory_promotion_jobs SET state='CURATING', owner_id=?, updated_at_ms=?
                        WHERE id=? AND (state IN ('PENDING', 'FAILED_RETRYABLE')
                           OR (state='CURATING' AND updated_at_ms < ?))
                        """)) {
                    update.setString(1, ownerId);
                    update.setLong(2, Instant.now().toEpochMilli());
                    update.setString(3, id);
                    update.setLong(4, staleBefore);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                connection.commit();
                return find(id);
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion claim failed", error);
        }
    }

    /** 是否仍有可处理的候选；供后台失败重试调度判断是否需要继续工作。 */
    public synchronized boolean hasRunnableJobs() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM memory_promotion_jobs "
                        + "WHERE state IN ('PENDING', 'FAILED_RETRYABLE') LIMIT 1")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (Exception error) {
            log.warn("memory promotion runnable-job check failed: {}", error.getMessage());
            return false;
        }
    }

    public synchronized Optional<Job> find(String id) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_json, state, result_ref, detail, created_at_ms, updated_at_ms
                FROM memory_promotion_jobs WHERE id=?
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(parse(result));
            }
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion lookup failed", error);
        }
    }

    public synchronized List<Job> listAwaitingConfirmation(int limit) {
        List<Job> jobs = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_json, state, result_ref, detail, created_at_ms, updated_at_ms
                FROM memory_promotion_jobs WHERE state='AWAITING_CONFIRMATION'
                ORDER BY created_at_ms ASC LIMIT ?
                """)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) jobs.add(parse(result));
            }
            return List.copyOf(jobs);
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion list failed", error);
        }
    }

    public void markCommitted(String id, String memoryId) {
        transition(id, State.COMMITTED, memoryId, "", Set.of(State.CURATING, State.AWAITING_CONFIRMATION));
    }

    public void markSkipped(String id, String reason) {
        transition(id, State.SKIPPED, "", reason, Set.of(State.CURATING, State.AWAITING_CONFIRMATION));
    }

    public void markAwaitingConfirmation(String id, String detail) {
        transition(id, State.AWAITING_CONFIRMATION, "", detail, Set.of(State.CURATING));
    }

    public void markFailedRetryable(String id, String detail) {
        transition(id, State.FAILED_RETRYABLE, "", detail, Set.of(State.CURATING));
    }

    /** 在同一进程内把状态校验、事实写入和队列终态串成一个清空闸门。 */
    public synchronized boolean commitIfState(String id, Set<State> allowedStates,
                                              Supplier<String> persister) {
        Job current = find(id).orElse(null);
        if (current == null || allowedStates == null || !allowedStates.contains(current.state())) {
            return false;
        }
        String resultRef = persister.get();
        if (resultRef == null || resultRef.isBlank()) {
            throw new IllegalStateException("memory promotion persister returned no reference");
        }
        return transition(id, State.COMMITTED, resultRef, "", allowedStates);
    }

    public synchronized void deleteAllJobs() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM memory_promotion_jobs");
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion clear failed", error);
        }
    }

    private synchronized boolean transition(String id, State state, String resultRef, String detail,
                                             Set<State> allowedStates) {
        if (id == null || state == null || allowedStates == null || allowedStates.isEmpty()) return false;
        String placeholders = String.join(",", java.util.Collections.nCopies(allowedStates.size(), "?"));
        String ownerFence = allowedStates.contains(State.CURATING)
                ? " AND (state <> 'CURATING' OR owner_id=?)" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE memory_promotion_jobs
                SET state=?, owner_id='', result_ref=?, detail=?, updated_at_ms=?
                WHERE id=? AND state IN (""" + placeholders + ")" + ownerFence)) {
            statement.setString(1, state.name());
            statement.setString(2, resultRef == null ? "" : resultRef);
            statement.setString(3, detail == null ? "" : detail);
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.setString(5, id);
            int parameter = 6;
            for (State allowed : allowedStates) statement.setString(parameter++, allowed.name());
            if (!ownerFence.isBlank()) statement.setString(parameter, ownerId);
            return statement.executeUpdate() == 1;
        } catch (Exception error) {
            throw new IllegalStateException("memory promotion transition failed", error);
        }
    }

    private static Job parse(ResultSet result) throws Exception {
        return new Job(result.getString("id"),
                JSON.readValue(result.getString("snapshot_json"), TaskMemorySnapshot.class),
                State.valueOf(result.getString("state")), result.getString("result_ref"),
                result.getString("detail"), Instant.ofEpochMilli(result.getLong("created_at_ms")),
                Instant.ofEpochMilli(result.getLong("updated_at_ms")));
    }

    @Override
    public synchronized void close() {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE memory_promotion_jobs SET state='PENDING', owner_id='', updated_at_ms=?
                WHERE state='CURATING' AND owner_id=?
                """)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.setString(2, ownerId);
            statement.executeUpdate();
        } catch (Exception error) {
            log.warn("failed to release memory promotion claims: {}", error.getMessage());
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }

    public enum State {
        PENDING, CURATING, AWAITING_CONFIRMATION, COMMITTED, SKIPPED, FAILED_RETRYABLE
    }

    public record Job(String id, TaskMemorySnapshot snapshot, State state,
                      String resultRef, String detail, Instant createdAt, Instant updatedAt) {
    }
}
