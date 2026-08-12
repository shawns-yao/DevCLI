package com.devcli.runtime.api;

import com.devcli.agent.AgentTurnInbox;
import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.store.SqliteRunStore;
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
    private final SqliteRunStore runStore;

    /** 一次完整 turn 的输入/输出对，供后续 turn 重放历史上下文。 */
    public record TurnRecord(String input, String output, long completedEventId) {
        public TurnRecord(String input, String output) {
            this(input, output, 0);
        }
    }

    public record MessageNodeRecord(String id, String parentId, String branchId,
                                    String role, String content, String preview, long eventId) {
        public MessageNodeRecord {
            id = id == null ? "" : id;
            parentId = parentId == null ? "" : parentId;
            branchId = branchId == null ? "" : branchId;
            role = role == null ? "" : role;
            content = content == null ? "" : content;
            preview = preview == null ? "" : preview;
            eventId = Math.max(0, eventId);
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
        Path normalizedDbPath = dbPath.toAbsolutePath().normalize();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + normalizedDbPath);
        initTables();
        this.runStore = new SqliteRunStore(normalizedDbPath);
    }

    public static Path defaultDbPath() {
        String configured = System.getProperty("devcli.runtime.dir");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_RUNTIME_DIR");
        }
        if (configured == null || configured.isBlank()) {
            configured = Path.of(System.getProperty("user.home"), ".devcli", "runtime").toString();
        }
        return SqliteRunStore.defaultDbPath();
    }

    /** 兼容门面共享的统一 RunStore，不再为 Runtime API 建立第二套运行状态库。 */
    public SqliteRunStore runStore() {
        return runStore;
    }

    public synchronized String createThread() {
        String id = "thread_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ensureThread(id);
        return id;
    }

    /** 创建或打开稳定 thread id，供 CLI 跨进程复用同一 Session Tree。 */
    public synchronized String ensureThread(String threadId) {
        String id = requireThreadId(threadId);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR IGNORE INTO runtime_threads (id, active_branch_id, created_at) VALUES (?, 'main', ?)
                """)) {
            ps.setString(1, id);
            ps.setString(2, Instant.now().toString());
            boolean created = ps.executeUpdate() > 0;
            ensureRootBranch(id);
            if (created) {
                RunEvent event = new RunEvent.ThreadCreated(id);
                appendEvent(id, event.type(), RunEventJsonCodec.encode(event, ""));
            }
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

    /** 从空白上下文创建独立根分支；既有分支历史保留但不进入新分支上下文。 */
    public synchronized BranchRecord createEmptyBranch(String threadId, String name) {
        ensureRootBranch(threadId);
        String id = "branch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertBranch(threadId, id, name == null || name.isBlank() ? id : name.trim(),
                "", 0);
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

    /** 以 Runtime 事件协议原子顺序写入一个已完成的顶层 turn。 */
    public synchronized long appendCompletedTurn(String threadId, String input, String output) {
        String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RunEvent.TurnStarted started = new RunEvent.TurnStarted(input);
        appendEvent(threadId, started.type(), RunEventJsonCodec.encode(started, turnId));
        if (output != null && !output.isBlank()) {
            RunEvent.MessageDelta message = new RunEvent.MessageDelta(output);
            appendEvent(threadId, message.type(), RunEventJsonCodec.encode(message, turnId));
        }
        RunEvent.TurnCompleted completed = new RunEvent.TurnCompleted("completed");
        return appendEvent(threadId, completed.type(), RunEventJsonCodec.encode(completed, turnId));
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

    /** 当前可见会话路径中的 user/assistant 消息节点，可作为精确 fork 锚点。 */
    public synchronized List<MessageNodeRecord> messageNodes(String threadId) {
        return messageNodes(threadId, Long.MAX_VALUE);
    }

    private List<MessageNodeRecord> messageNodes(String threadId, long throughEventId) {
        List<RuntimeEvent> visible = events(threadId, 0);
        List<MessageNodeRecord> result = new ArrayList<>();
        Map<String, String> inputs = new HashMap<>();
        Map<String, Long> inputEventIds = new HashMap<>();
        Map<String, String> outputs = new HashMap<>();
        Map<String, String> outputBranches = new HashMap<>();
        String parentId = "";
        for (RuntimeEvent event : visible) {
            if (event.id() > throughEventId) break;
            try {
                JsonNode data = MAPPER.readTree(event.data());
                String turnId = data.path("turn_id").asText("");
                if (turnId.isBlank()) continue;
                switch (event.type()) {
                    case "turn.started" -> {
                        inputs.put(turnId, data.path("input").asText(""));
                        inputEventIds.put(turnId, event.id());
                    }
                    case "message.delta" -> {
                        outputs.merge(turnId, data.path("content").asText(""), String::concat);
                        outputBranches.put(turnId, event.branchId());
                    }
                    case "turn.completed" -> {
                        String input = inputs.remove(turnId);
                        Long inputEventId = inputEventIds.remove(turnId);
                        String output = outputs.remove(turnId);
                        String outputBranch = outputBranches.remove(turnId);
                        if (input != null && inputEventId != null) {
                            String id = messageNodeId(turnId, "user");
                            result.add(new MessageNodeRecord(id, parentId, event.branchId(),
                                    "user", input, preview(input), inputEventId));
                            parentId = id;
                        }
                        if (output != null) {
                            String id = messageNodeId(turnId, "assistant");
                            result.add(new MessageNodeRecord(id, parentId,
                                    outputBranch == null ? event.branchId() : outputBranch,
                                    "assistant", output, preview(output), event.id()));
                            parentId = id;
                        }
                    }
                    default -> { }
                }
            } catch (Exception error) {
                log.warn("解析会话消息节点失败，跳过事件: id={}, type={}", event.id(), event.type());
            }
        }
        return List.copyOf(result);
    }

    public synchronized MessageNodeRecord messageNode(String threadId, String messageId) {
        String target = messageId == null ? "" : messageId.trim();
        return messageNodes(threadId).stream()
                .filter(node -> node.id().equals(target))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到当前会话路径中的消息节点: " + target));
    }

    public synchronized MessageNodeRecord forkAnchor(String threadId, String messageId) {
        MessageNodeRecord node = messageNode(threadId, messageId);
        if (!"assistant".equals(node.role())) {
            throw new IllegalArgumentException("只能从已完成的 assistant 消息节点建立分支: " + messageId);
        }
        return node;
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
                : turns.get(turns.size() - 1).completedEventId();
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

    /** 当前活动分支自身最近的 checkpoint，不把父分支 checkpoint 当作本分支新边界。 */
    public synchronized Optional<RuntimeCheckpoint> latestCheckpointOnActiveBranch(String threadId) {
        String active = activeBranchId(threadId);
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT id, thread_id, branch_id, covered_through_event_id, messages_json,
                       summary, metadata_json, created_at, message_tree_json
                FROM runtime_checkpoints
                WHERE thread_id = ? AND branch_id = ?
                ORDER BY covered_through_event_id DESC, id DESC
                LIMIT 1
                """)) {
            ps.setString(1, threadId);
            ps.setString(2, active);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                List<LlmClient.Message> messages = MAPPER.readValue(
                        rs.getString("messages_json"),
                        new TypeReference<List<LlmClient.Message>>() {});
                CompactBoundaryMetadata metadata = MAPPER.readValue(
                        rs.getString("metadata_json"), CompactBoundaryMetadata.class);
                List<TurnRunner.MessageTreeNode> messageTree = MAPPER.readValue(
                        rs.getString("message_tree_json"),
                        new TypeReference<List<TurnRunner.MessageTreeNode>>() {});
                return Optional.of(new RuntimeCheckpoint(
                        rs.getLong("id"), rs.getString("thread_id"), active,
                        rs.getLong("covered_through_event_id"), messages,
                        rs.getString("summary"), metadata, messageTree,
                        Instant.parse(rs.getString("created_at"))));
            }
        } catch (Exception error) {
            log.warn("读取活动分支 checkpoint 失败: thread={}, branch={}", threadId, active);
            return Optional.empty();
        }
    }

    /** 在当前可见路径上重建截至指定已完成事件的消息上下文。 */
    public synchronized List<LlmClient.Message> contextMessagesThrough(String threadId,
                                                                        long completedEventId) {
        if (completedEventId <= 0) return List.of();
        List<LlmClient.Message> result = new ArrayList<>();
        for (MessageNodeRecord node : messageNodes(threadId, completedEventId)) {
            if ("user".equals(node.role())) {
                result.add(LlmClient.Message.user(node.content()));
            } else if ("assistant".equals(node.role())) {
                result.add(LlmClient.Message.assistant(node.content()));
            }
        }
        return List.copyOf(result);
    }

    private static String requireThreadId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IllegalArgumentException("threadId 格式无效");
        }
        return normalized;
    }

    private static String messageNodeId(String turnId, String role) {
        return "msg_" + UUID.nameUUIDFromBytes((turnId + "\u0000" + role)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private static String preview(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 72 ? normalized : normalized.substring(0, 69) + "...";
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
        runStore.close();
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
