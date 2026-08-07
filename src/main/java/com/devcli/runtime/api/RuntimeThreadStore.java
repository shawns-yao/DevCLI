package com.devcli.runtime.api;

import com.devcli.agent.AgentTurnInbox;
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
            String branchId,
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

    public record BranchRecord(String id, String threadId, String name,
                               String parentBranchId, long forkEventId,
                               boolean active, Instant createdAt) {
        public BranchRecord {
            id = id == null ? "" : id;
            threadId = threadId == null ? "" : threadId;
            name = name == null || name.isBlank() ? id : name;
            parentBranchId = parentBranchId == null ? "" : parentBranchId;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
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
                INSERT INTO runtime_threads (id, active_branch_id, created_at) VALUES (?, 'main', ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
            insertBranch(id, "main", "main", "", 0);
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

    public synchronized String activeBranchId(String threadId) {
        ensureRootBranch(threadId);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT active_branch_id FROM runtime_threads WHERE id = ?")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null && !rs.getString(1).isBlank()) {
                    return rs.getString(1);
                }
            }
            return "main";
        } catch (SQLException e) {
            throw new IllegalStateException("读取 active branch 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<BranchRecord> branches(String threadId) {
        ensureRootBranch(threadId);
        List<BranchRecord> result = new ArrayList<>();
        String active = activeBranchId(threadId);
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, name, parent_branch_id, fork_event_id, created_at
                FROM runtime_branches WHERE thread_id = ? ORDER BY created_at, id
                """)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BranchRecord(rs.getString("id"), rs.getString("thread_id"),
                            rs.getString("name"), rs.getString("parent_branch_id"),
                            rs.getLong("fork_event_id"), active.equals(rs.getString("id")),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime branches 失败: " + e.getMessage(), e);
        }
        return List.copyOf(result);
    }

    public synchronized BranchRecord createBranch(String threadId, String name, long fromEventId) {
        ensureRootBranch(threadId);
        String parentId = activeBranchId(threadId);
        List<RuntimeEvent> visible = events(threadId, 0);
        long fork = fromEventId <= 0
                ? visible.stream().mapToLong(RuntimeEvent::id).max().orElse(0L)
                : fromEventId;
        if (fork > 0 && visible.stream().noneMatch(event -> event.id() == fork)) {
            throw new IllegalArgumentException("fork event 不属于当前 branch: " + fork);
        }
        String id = "branch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertBranch(threadId, id, name == null || name.isBlank() ? id : name.trim(),
                parentId, fork);
        return branches(threadId).stream().filter(branch -> branch.id().equals(id)).findFirst().orElseThrow();
    }

    public synchronized void activateBranch(String threadId, String branchId) {
        ensureRootBranch(threadId);
        boolean belongs = branches(threadId).stream().anyMatch(branch -> branch.id().equals(branchId));
        if (!belongs) {
            throw new IllegalArgumentException("branch 不属于当前 thread: " + branchId);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE runtime_threads SET active_branch_id = ? WHERE id = ?")) {
            ps.setString(1, branchId);
            ps.setString(2, threadId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("切换 runtime branch 失败: " + e.getMessage(), e);
        }
    }

    public synchronized AgentTurnInbox.Snapshot queueSnapshot(String threadId) {
        String branchId = activeBranchId(threadId);
        List<AgentTurnInbox.Item> steering = new ArrayList<>();
        List<AgentTurnInbox.Item> followUp = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT sequence, channel, text, created_at
                FROM runtime_queues
                WHERE thread_id = ? AND branch_id = ?
                ORDER BY sequence
                """)) {
            ps.setString(1, threadId);
            ps.setString(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AgentTurnInbox.Item item = new AgentTurnInbox.Item(
                            rs.getLong("sequence"),
                            AgentTurnInbox.Channel.valueOf(rs.getString("channel")),
                            rs.getString("text"),
                            Instant.parse(rs.getString("created_at")));
                    if (item.channel() == AgentTurnInbox.Channel.STEERING) steering.add(item);
                    else followUp.add(item);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime queue 失败: " + e.getMessage(), e);
        }
        return new AgentTurnInbox.Snapshot(steering, followUp,
                AgentTurnInbox.DEFAULT_CAPACITY);
    }

    public synchronized void saveQueueSnapshot(String threadId, AgentTurnInbox.Snapshot snapshot) {
        String branchId = activeBranchId(threadId);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM runtime_queues WHERE thread_id = ? AND branch_id = ?")) {
                delete.setString(1, threadId);
                delete.setString(2, branchId);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO runtime_queues(
                        thread_id, branch_id, sequence, channel, text, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                List<AgentTurnInbox.Item> items = new ArrayList<>();
                if (snapshot != null) {
                    items.addAll(snapshot.steering());
                    items.addAll(snapshot.followUp());
                }
                for (AgentTurnInbox.Item item : items) {
                    insert.setString(1, threadId);
                    insert.setString(2, branchId);
                    insert.setLong(3, item.sequence());
                    insert.setString(4, item.channel().name());
                    insert.setString(5, item.text());
                    insert.setString(6, item.queuedAt().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("写入 runtime queue 失败: " + e.getMessage(), e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    public synchronized long appendEvent(String threadId, String type, String data) {
        String branchId = activeBranchId(threadId);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_events (thread_id, branch_id, type, data, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, threadId);
            ps.setString(2, branchId);
            ps.setString(3, type);
            ps.setString(4, data == null ? "{}" : data);
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入 runtime event 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<RuntimeEvent> events(String threadId, long afterId) {
        List<RuntimeEvent> allEvents = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, branch_id, type, data, created_at FROM runtime_events
                WHERE thread_id = ? AND id > ?
                ORDER BY id ASC
                """)) {
            ps.setString(1, threadId);
            ps.setLong(2, afterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    allEvents.add(new RuntimeEvent(
                            rs.getLong("id"),
                            rs.getString("thread_id"),
                            rs.getString("branch_id"),
                            rs.getString("type"),
                            rs.getString("data"),
                            Instant.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime events 失败: " + e.getMessage(), e);
        }
        return filterVisibleEvents(threadId, allEvents);
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
                    thread_id, branch_id, covered_through_event_id, messages_json, summary,
                    metadata_json, message_tree_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, threadId);
            ps.setString(2, activeBranchId(threadId));
            ps.setLong(3, coveredThroughEventId);
            ps.setString(4, MAPPER.writeValueAsString(candidate.messages()));
            ps.setString(5, candidate.summary());
            ps.setString(6, MAPPER.writeValueAsString(candidate.metadata()));
            ps.setString(7, MAPPER.writeValueAsString(candidate.messageTree()));
            ps.setString(8, Instant.now().toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("写入 runtime checkpoint 失败: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<RuntimeCheckpoint> latestCheckpoint(String threadId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, branch_id, covered_through_event_id, messages_json,
                       summary, metadata_json, created_at, message_tree_json
                FROM runtime_checkpoints
                WHERE thread_id = ?
                ORDER BY covered_through_event_id DESC, id DESC
                """)) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        String branchId = rs.getString("branch_id");
                        if (!isVisibleBranchAtEvent(threadId, branchId,
                                rs.getLong("covered_through_event_id"))) {
                            continue;
                        }
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
                                branchId,
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

    private List<RuntimeEvent> filterVisibleEvents(String threadId, List<RuntimeEvent> events) {
        List<BranchRecord> lineage = activeLineage(threadId);
        List<RuntimeEvent> visible = new ArrayList<>();
        for (int index = 0; index < lineage.size(); index++) {
            BranchRecord branch = lineage.get(index);
            long lowerExclusive = branch.forkEventId();
            long upperInclusive = index + 1 < lineage.size()
                    ? lineage.get(index + 1).forkEventId() : Long.MAX_VALUE;
            for (RuntimeEvent event : events) {
                if (branch.id().equals(event.branchId())
                        && event.id() > lowerExclusive
                        && event.id() <= upperInclusive) {
                    visible.add(event);
                }
            }
        }
        visible.sort(java.util.Comparator.comparingLong(RuntimeEvent::id));
        return List.copyOf(visible);
    }

    private boolean isVisibleBranchAtEvent(String threadId, String branchId, long eventId) {
        List<BranchRecord> lineage = activeLineage(threadId);
        for (int index = 0; index < lineage.size(); index++) {
            BranchRecord branch = lineage.get(index);
            if (!branch.id().equals(branchId)) continue;
            long lowerExclusive = branch.forkEventId();
            long upperInclusive = index + 1 < lineage.size()
                    ? lineage.get(index + 1).forkEventId() : Long.MAX_VALUE;
            return eventId > lowerExclusive && eventId <= upperInclusive;
        }
        return false;
    }

    private List<BranchRecord> activeLineage(String threadId) {
        List<BranchRecord> all = branches(threadId);
        Map<String, BranchRecord> byId = new HashMap<>();
        for (BranchRecord branch : all) {
            byId.put(branch.id(), branch);
        }
        List<BranchRecord> reversed = new ArrayList<>();
        BranchRecord current = byId.get(activeBranchId(threadId));
        while (current != null) {
            reversed.add(current);
            current = current.parentBranchId().isBlank()
                    ? null : byId.get(current.parentBranchId());
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private void ensureRootBranch(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is required");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM runtime_branches WHERE thread_id = ? AND id = 'main'")) {
            ps.setString(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    insertBranch(threadId, "main", "main", "", 0);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 runtime root branch 失败: " + e.getMessage(), e);
        }
    }

    private void insertBranch(String threadId, String id, String name,
                              String parentBranchId, long forkEventId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_branches(
                    id, thread_id, name, parent_branch_id, fork_event_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, threadId);
            ps.setString(3, name);
            ps.setString(4, parentBranchId == null ? "" : parentBranchId);
            ps.setLong(5, Math.max(0, forkEventId));
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入 runtime branch 失败: " + e.getMessage(), e);
        }
    }

    private void initTables() throws SQLException {
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
            ensureColumn(stmt, "runtime_threads", "active_branch_id",
                    "TEXT NOT NULL DEFAULT 'main'");
            ensureColumn(stmt, "runtime_events", "branch_id",
                    "TEXT NOT NULL DEFAULT 'main'");
            ensureColumn(stmt, "runtime_checkpoints", "message_tree_json",
                    "TEXT NOT NULL DEFAULT '[]'");
            ensureColumn(stmt, "runtime_checkpoints", "branch_id",
                    "TEXT NOT NULL DEFAULT 'main'");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runtime_events_branch "
                    + "ON runtime_events(thread_id, branch_id, id)");
            stmt.execute("DROP INDEX IF EXISTS idx_runtime_checkpoint_coverage");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_runtime_checkpoint_coverage "
                    + "ON runtime_checkpoints(thread_id, branch_id, covered_through_event_id)");
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
