package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RuntimeThreadStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RuntimeThreadStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection connection;

    /** 一次完整 turn 的输入/输出对，供后续 turn 重放历史上下文。 */
    public record TurnRecord(String input, String output, long completedEventId) {
        public TurnRecord(String input, String output) {
            this(input, output, 0);
        }
    }

    public record RuntimeCheckpoint(
            long id,
            String threadId,
            long coveredThroughEventId,
            List<LlmClient.Message> messages,
            String summary,
            CompactBoundaryMetadata metadata,
            List<TurnRunner.MessageTreeNode> messageTree,
            Instant createdAt) {
        public RuntimeCheckpoint {
            messages = messages == null ? List.of() : List.copyOf(messages);
            summary = summary == null ? "" : summary;
            metadata = metadata == null ? new CompactBoundaryMetadata(
                    "unknown", "unknown", "unknown", 0, 0, 0, 0, 0, 0) : metadata;
            messageTree = messageTree == null ? List.of() : List.copyOf(messageTree);
        }
    }

    public record ContextView(
            List<LlmClient.Message> checkpointMessages,
            List<TurnRecord> turns,
            long lastCompletedEventId,
            Optional<RuntimeCheckpoint> checkpoint) {
        public ContextView {
            checkpointMessages = checkpointMessages == null ? List.of() : List.copyOf(checkpointMessages);
            turns = turns == null ? List.of() : List.copyOf(turns);
            checkpoint = checkpoint == null ? Optional.empty() : checkpoint;
        }
    }

    public RuntimeThreadStore(Path dbPath) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new SQLException("无法创建 Runtime API 数据库目录: " + e.getMessage(), e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initTables();
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("devcli.runtime.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_RUNTIME_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".devcli", "runtime").toString();
        }
        return Path.of(configured).resolve("runtime.db");
    }

    public synchronized String createThread() {
        String id = "thread_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_threads (id, created_at) VALUES (?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
            RunEvent event = new RunEvent.ThreadCreated(id);
            appendEvent(id, event.type(), RunEventJsonCodec.encode(event, ""));
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("创建 runtime thread 失败: " + e.getMessage(), e);
        }
    }

    public synchronized boolean exists(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM runtime_threads WHERE id = ?")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime thread 失败: " + e.getMessage(), e);
        }
    }

    public synchronized long appendEvent(String threadId, String type, String data) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_events (thread_id, type, data, created_at)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, threadId);
            ps.setString(2, type);
            ps.setString(3, data == null ? "{}" : data);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入 runtime event 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<RuntimeEvent> events(String threadId, long afterId) {
        List<RuntimeEvent> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, type, data, created_at FROM runtime_events
                WHERE thread_id = ? AND id > ?
                ORDER BY id ASC
                """)) {
            ps.setString(1, threadId);
            ps.setLong(2, afterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new RuntimeEvent(
                            rs.getLong("id"),
                            rs.getString("thread_id"),
                            rs.getString("type"),
                            rs.getString("data"),
                            Instant.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime events 失败: " + e.getMessage(), e);
        }
        return events;
    }

    /**
     * 按时间序返回指定 thread 已完成 turn 的输入/输出对（只取有 turn.completed 终态的完整 turn，
     * 失败/被拒的 turn 不进入历史）。事件 data 为写入端手拼 JSON，解析端容错：单条解析失败跳过并 warn。
     */
    public synchronized List<TurnRecord> turnHistory(String threadId) {
        return turnHistoryAfter(threadId, 0);
    }

    public synchronized List<TurnRecord> turnHistoryAfter(String threadId, long afterEventId) {
        List<RuntimeEvent> allEvents = events(threadId, Math.max(0, afterEventId));
        Map<String, String> inputs = new HashMap<>();
        Map<String, String> outputs = new HashMap<>();
        List<TurnRecord> history = new ArrayList<>();
        for (RuntimeEvent event : allEvents) {
            try {
                JsonNode data = MAPPER.readTree(event.data());
                String turnId = data.path("turn_id").asText("");
                if (turnId.isBlank()) {
                    continue;
                }
                switch (event.type()) {
                    case "turn.started" -> inputs.put(turnId, data.path("input").asText(""));
                    case "message.delta" -> outputs.merge(turnId, data.path("content").asText(""), String::concat);
                    case "turn.completed" -> {
                        String input = inputs.remove(turnId);
                        String output = outputs.remove(turnId);
                        if (input != null && !input.isBlank()) {
                            history.add(new TurnRecord(
                                    input, output == null ? "" : output, event.id()));
                        }
                    }
                    default -> { /* thread.created / turn.failed / turn.rejected 不进历史 */ }
                }
            } catch (Exception e) {
                log.warn("解析 runtime event 失败，跳过该事件: id={}, type={}", event.id(), event.type());
            }
        }
        return history;
    }

    public synchronized ContextView contextView(String threadId) {
        Optional<RuntimeCheckpoint> checkpoint = latestCheckpoint(threadId);
        long coveredThrough = checkpoint.map(RuntimeCheckpoint::coveredThroughEventId).orElse(0L);
        List<TurnRecord> turns = turnHistoryAfter(threadId, coveredThrough);
        long lastCompletedEventId = turns.isEmpty()
                ? coveredThrough
                : turns.getLast().completedEventId();
        return new ContextView(
                checkpoint.map(RuntimeCheckpoint::messages).orElse(List.of()),
                turns,
                lastCompletedEventId,
                checkpoint);
    }

    public synchronized void saveCheckpoint(
            String threadId,
            long coveredThroughEventId,
            TurnRunner.CheckpointCandidate candidate) {
        if (candidate == null || candidate.messages().isEmpty() || coveredThroughEventId <= 0) return;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_checkpoints (
                    thread_id, covered_through_event_id, messages_json, summary,
                    metadata_json, message_tree_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, threadId);
            ps.setLong(2, coveredThroughEventId);
            ps.setString(3, MAPPER.writeValueAsString(candidate.messages()));
            ps.setString(4, candidate.summary());
            ps.setString(5, MAPPER.writeValueAsString(candidate.metadata()));
            ps.setString(6, MAPPER.writeValueAsString(candidate.messageTree()));
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入 runtime checkpoint 失败: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<RuntimeCheckpoint> latestCheckpoint(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, covered_through_event_id, messages_json,
                       summary, metadata_json, created_at
                       , message_tree_json
                FROM runtime_checkpoints
                WHERE thread_id = ?
                ORDER BY covered_through_event_id DESC, id DESC
                """)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        List<LlmClient.Message> messages = MAPPER.readValue(
                                rs.getString("messages_json"),
                                new TypeReference<List<LlmClient.Message>>() {});
                        CompactBoundaryMetadata metadata = MAPPER.readValue(
                                rs.getString("metadata_json"), CompactBoundaryMetadata.class);
                        List<TurnRunner.MessageTreeNode> messageTree = MAPPER.readValue(
                                rs.getString("message_tree_json"),
                                new TypeReference<List<TurnRunner.MessageTreeNode>>() {});
                        return Optional.of(new RuntimeCheckpoint(
                                rs.getLong("id"),
                                rs.getString("thread_id"),
                                rs.getLong("covered_through_event_id"),
                                messages,
                                rs.getString("summary"),
                                metadata,
                                messageTree,
                                Instant.parse(rs.getString("created_at"))));
                    } catch (Exception corrupted) {
                        log.warn("解析 runtime checkpoint 失败，回退更早检查点: id={}", rs.getLong("id"));
                    }
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime checkpoint 失败: " + e.getMessage(), e);
        }
    }

    private void initTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_threads (
                        id TEXT PRIMARY KEY,
                        created_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS runtime_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        thread_id TEXT NOT NULL,
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
                        covered_through_event_id INTEGER NOT NULL,
                        messages_json TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        metadata_json TEXT NOT NULL,
                        message_tree_json TEXT NOT NULL DEFAULT '[]',
                        created_at TEXT NOT NULL
                    )
                    """);
            ensureColumn(stmt, "runtime_checkpoints", "message_tree_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_runtime_checkpoint_coverage "
                    + "ON runtime_checkpoints(thread_id, covered_through_event_id)");
        }
    }

    private static void ensureColumn(Statement statement, String table, String column,
                                     String definition) throws SQLException {
        try {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // 已存在时保持兼容；SQLite 没有 IF NOT EXISTS 的 ADD COLUMN 语法。
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
