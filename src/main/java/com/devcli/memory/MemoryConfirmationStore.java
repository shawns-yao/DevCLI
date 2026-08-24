package com.devcli.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 持久化敏感记忆确认意图和终态结果；SQLite 不可用时保守降级到进程内存。 */
final class MemoryConfirmationStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MemoryConfirmationStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection connection;
    private final Map<String, Ticket> fallback = new ConcurrentHashMap<>();

    MemoryConfirmationStore(Path memoryDir) {
        Connection opened = null;
        try {
            Files.createDirectories(memoryDir);
            opened = DriverManager.getConnection(
                    "jdbc:sqlite:" + memoryDir.resolve("memory_vectors.db"));
            try (Statement statement = opened.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS memory_confirmations (
                            id TEXT PRIMARY KEY,
                            sanitized_fact TEXT NOT NULL,
                            expires_at_ms INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            result_json TEXT NOT NULL DEFAULT '',
                            updated_at_ms INTEGER NOT NULL
                        )
                        """);
            }
        } catch (Exception e) {
            log.warn("memory confirmation persistence unavailable; using process memory: {}", e.getMessage());
            if (opened != null) {
                try {
                    opened.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            opened = null;
        }
        this.connection = opened;
    }

    synchronized void create(String id, String sanitizedFact, Instant expiresAt) {
        Ticket ticket = new Ticket(id, sanitizedFact, expiresAt, State.PENDING, null);
        if (connection == null) {
            fallback.putIfAbsent(id, ticket);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO memory_confirmations(
                    id, sanitized_fact, expires_at_ms, state, result_json, updated_at_ms)
                VALUES (?, ?, ?, 'PENDING', '', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, sanitizedFact);
            statement.setLong(3, expiresAt.toEpochMilli());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("persist memory confirmation failed: {}", e.getMessage());
            fallback.putIfAbsent(id, ticket);
        }
    }

    synchronized Optional<Ticket> find(String id, Instant now) {
        if (id == null || id.isBlank()) return Optional.empty();
        Ticket fallbackTicket = fallback.get(id);
        if (connection == null || fallbackTicket != null) {
            return valid(fallbackTicket, now);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sanitized_fact, expires_at_ms, state, result_json
                FROM memory_confirmations WHERE id = ?
                """)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                State state = State.valueOf(rs.getString(3));
                MemoryManager.StoreResult result = state == State.COMPLETED
                        ? parseResult(rs.getString(4)) : null;
                return valid(new Ticket(id, rs.getString(1),
                        Instant.ofEpochMilli(rs.getLong(2)), state, result), now);
            }
        } catch (Exception e) {
            log.warn("read memory confirmation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    synchronized void complete(String id, MemoryManager.StoreResult result) {
        Ticket existing = fallback.get(id);
        if (connection == null || existing != null) {
            if (existing != null) fallback.put(id, existing.complete(result));
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE memory_confirmations
                SET state = 'COMPLETED', result_json = ?, updated_at_ms = ?
                WHERE id = ? AND state = 'PENDING'
                """)) {
            statement.setString(1, resultJson(result));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, id);
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("complete memory confirmation failed: {}", e.getMessage());
        }
    }

    synchronized boolean cancel(String id, Instant now) {
        Optional<Ticket> ticket = find(id, now);
        if (ticket.isEmpty() || ticket.get().state() != State.PENDING) return false;
        if (connection == null || fallback.containsKey(id)) {
            fallback.put(id, ticket.get().cancel());
            return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE memory_confirmations SET state = 'CANCELLED', updated_at_ms = ?
                WHERE id = ? AND state = 'PENDING'
                """)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, id);
            return statement.executeUpdate() == 1;
        } catch (Exception e) {
            log.warn("cancel memory confirmation failed: {}", e.getMessage());
            return false;
        }
    }

    synchronized void pruneExpired(Instant now) {
        fallback.entrySet().removeIf(entry -> entry.getValue().expired(now));
        if (connection == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM memory_confirmations
                WHERE state = 'PENDING' AND expires_at_ms < ?
                """)) {
            statement.setLong(1, now.toEpochMilli());
            statement.executeUpdate();
        } catch (Exception e) {
            log.warn("prune memory confirmations failed: {}", e.getMessage());
        }
    }

    private static Optional<Ticket> valid(Ticket ticket, Instant now) {
        if (ticket == null || ticket.state() == State.CANCELLED || ticket.expired(now)) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    private static String resultJson(MemoryManager.StoreResult result) throws Exception {
        return JSON.writeValueAsString(new ResultSnapshot(
                result.stored(), result.decision().action().name(), result.decision().reason(),
                result.decision().metadata(), result.message(), result.id(), result.confirmationId()));
    }

    private static MemoryManager.StoreResult parseResult(String json) throws Exception {
        JsonNode node = JSON.readTree(json);
        Map<String, String> metadata = JSON.convertValue(node.path("metadata"),
                new TypeReference<>() { });
        LongTermMemoryPolicy.Decision decision = new LongTermMemoryPolicy.Decision(
                LongTermMemoryPolicy.Action.valueOf(node.path("action").asText()),
                node.path("reason").asText(), metadata);
        return new MemoryManager.StoreResult(node.path("stored").asBoolean(), decision,
                node.path("message").asText(), node.path("id").asText(),
                node.path("confirmationId").asText());
    }

    @Override
    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("close memory confirmation store failed: {}", e.getMessage());
        }
    }

    enum State { PENDING, COMPLETED, CANCELLED }

    record Ticket(String id, String sanitizedFact, Instant expiresAt,
                  State state, MemoryManager.StoreResult result) {
        boolean expired(Instant now) {
            return state == State.PENDING && expiresAt.isBefore(now);
        }

        Ticket complete(MemoryManager.StoreResult result) {
            return new Ticket(id, sanitizedFact, expiresAt, State.COMPLETED, result);
        }

        Ticket cancel() {
            return new Ticket(id, sanitizedFact, expiresAt, State.CANCELLED, null);
        }
    }

    private record ResultSnapshot(boolean stored, String action, String reason,
                                  Map<String, String> metadata, String message,
                                  String id, String confirmationId) {
    }
}
