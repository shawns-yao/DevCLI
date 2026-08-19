package com.devcli.runtime.api;

import com.devcli.agent.AgentTurnInbox;
import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.store.RunStore;
import com.devcli.runtime.store.RecoveryEvidenceRef;
import com.devcli.runtime.store.SqliteRunStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RuntimeThreadStore implements RunStore {
    private static final Logger log = LoggerFactory.getLogger(RuntimeThreadStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SESSION_PROJECTION_VERSION = 1;
    private static final int EVENT_FETCH_LIMIT = 128;

    private final Path dbPath;
    private final Connection connection;
    private final SqliteRunStore runStore;
    private boolean closed;

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

    private record EventReplay(List<LlmClient.Message> messages, long completedEventId) {
    }

    public record SessionProjection(
            int projectionVersion,
            String logIdentity,
            long eventCursor,
            String title,
            String state,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            double estimatedCostCny,
            long toolCalls,
            long toolFailures,
            long hookCalls,
            long hookFailures) {
        public SessionProjection {
            logIdentity = logIdentity == null ? "" : logIdentity;
            title = title == null ? "" : title;
            state = state == null || state.isBlank() ? "idle" : state;
            eventCursor = Math.max(0, eventCursor);
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            cachedInputTokens = Math.max(0, cachedInputTokens);
            estimatedCostCny = Math.max(0D, estimatedCostCny);
            toolCalls = Math.max(0, toolCalls);
            toolFailures = Math.max(0, toolFailures);
            hookCalls = Math.max(0, hookCalls);
            hookFailures = Math.max(0, hookFailures);
        }
    }

    public RuntimeThreadStore(Path dbPath) throws SQLException {
        this.dbPath = dbPath.toAbsolutePath().normalize();
        try {
            Path parent = this.dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new SQLException("无法创建 Runtime API 数据库目录: " + e.getMessage(), e);
        }
        this.runStore = new SqliteRunStore(this.dbPath);
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
            initTables();
        } catch (SQLException e) {
            runStore.close();
            throw e;
        }
    }

    public static Path defaultDbPath() {
        String configured = ConfigResolver.stringValue(
                "devcli.runtime.dir",
                "DEVCLI_RUNTIME_DIR",
                Path.of(System.getProperty("user.home"), ".devcli", "runtime").toString());
        return Path.of(configured).resolve("runtime.db");
    }

    @Override
    public RunRecord create(Submission submission) {
        return runStore.create(submission);
    }

    @Override
    public Optional<RunRecord> find(String id) {
        return runStore.find(id);
    }

    @Override
    public List<RunRecord> list(Source source, int limit) {
        return runStore.list(source, limit);
    }

    @Override
    public Optional<RunRecord> claimNext(Source source) {
        return runStore.claimNext(source);
    }

    @Override
    public boolean start(String id) {
        return runStore.start(id);
    }

    @Override
    public boolean complete(String id, String result) {
        return runStore.complete(id, result);
    }

    @Override
    public boolean fail(String id, String error) {
        return runStore.fail(id, error);
    }

    @Override
    public boolean reject(String id, String reason) {
        return runStore.reject(id, reason);
    }

    @Override
    public boolean cancel(String id, String reason) {
        return runStore.cancel(id, reason);
    }

    @Override
    public Optional<RunRecord> activeRun(String threadId) {
        return runStore.activeRun(threadId);
    }

    @Override
    public int recoverRunning(Source source, Status target, String reason) {
        return runStore.recoverRunning(source, target, reason);
    }

    @Override
    public RecoveryEvidenceRef upsertRecoveryEvidence(RecoveryEvidenceRef ref) {
        return runStore.upsertRecoveryEvidence(ref);
    }

    @Override
    public List<RecoveryEvidenceRef> listRecoveryEvidence(String runId, int limit) {
        return runStore.listRecoveryEvidence(runId, limit);
    }

    @Override
    public Path dbPath() {
        return dbPath;
    }

    public int importLegacyTasks(Path legacyDbPath) throws SQLException {
        return runStore.importLegacyTasks(legacyDbPath);
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

    public synchronized BranchRecord createRootBranch(String threadId, String name) {
        ensureRootBranch(threadId);
        String parentId = activeBranchId(threadId);
        String id = "branch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertBranch(threadId, id, name == null || name.isBlank() ? id : name.trim(),
                parentId, 0);
        return branches(threadId).stream()
                .filter(branch -> branch.id().equals(id))
                .findFirst()
                .orElseThrow();
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
        ensureOpen();
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
                long id = keys.next() ? keys.getLong(1) : 0;
                // SQLite JDBC commits this statement in auto-commit mode before returning.
                notifyAll();
                return id;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入 runtime event 失败: " + e.getMessage(), e);
        }
    }

    public synchronized List<RuntimeEvent> events(String threadId, long afterId) {
        ensureOpen();
        List<RuntimeEvent> result = new ArrayList<>();
        long cursor = Math.max(0, afterId);
        while (true) {
            List<RuntimeEvent> batch = readVisibleEventsBatch(threadId, cursor, EVENT_FETCH_LIMIT);
            if (batch.isEmpty()) {
                break;
            }
            result.addAll(batch);
            cursor = batch.getLast().id();
            if (batch.size() < EVENT_FETCH_LIMIT) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * 查询游标之后当前活动分支可见的有界事件批次。
     * 每次 SQL 读取有上限，分支可见性在 SQL 条件中先于 LIMIT 生效。
     */
    public synchronized List<RuntimeEvent> events(String threadId, long afterId, int limit) {
        ensureOpen();
        return readVisibleEventsBatch(threadId, Math.max(0, afterId),
                Math.max(1, Math.min(EVENT_FETCH_LIMIT, limit)));
    }

    /**
     * 在查询与等待之间持有同一监视器，避免错过提交后的唤醒；伪唤醒会回到查询循环。
     */
    public synchronized List<RuntimeEvent> awaitEvents(
            String threadId, long afterId, int limit, Duration timeout) throws InterruptedException {
        long cursor = Math.max(0, afterId);
        long timeoutNanos = timeout == null ? 0 : Math.max(0, timeout.toNanos());
        long deadline = timeoutNanos > 0 ? System.nanoTime() + timeoutNanos : System.nanoTime();
        while (!closed) {
            List<RuntimeEvent> result = readVisibleEventsBatch(threadId, cursor,
                    Math.max(1, Math.min(EVENT_FETCH_LIMIT, limit)));
            if (!result.isEmpty()) {
                return result;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return List.of();
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return List.of();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private List<RuntimeEvent> readVisibleEventsBatch(String threadId, long afterId, int limit) {
        List<BranchRecord> lineage = activeLineage(threadId);
        if (lineage.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, thread_id, branch_id, type, data, created_at
                FROM runtime_events
                WHERE thread_id = ? AND id > ? AND (
                """);
        for (int index = 0; index < lineage.size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            sql.append("(branch_id = ? AND id > ? AND id <= ?)");
        }
        sql.append(" ) ORDER BY id ASC LIMIT ?");

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            ps.setString(parameter++, threadId);
            ps.setLong(parameter++, Math.max(0, afterId));
            for (int index = 0; index < lineage.size(); index++) {
                BranchRecord branch = lineage.get(index);
                long upperInclusive = index + 1 < lineage.size()
                        ? lineage.get(index + 1).forkEventId() : Long.MAX_VALUE;
                ps.setString(parameter++, branch.id());
                ps.setLong(parameter++, branch.forkEventId());
                ps.setLong(parameter++, upperInclusive);
            }
            ps.setInt(parameter, Math.max(1, limit));
            List<RuntimeEvent> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new RuntimeEvent(
                            rs.getLong("id"),
                            rs.getString("thread_id"),
                            rs.getString("branch_id"),
                            rs.getString("type"),
                            rs.getString("data"),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime events 失败: " + e.getMessage(), e);
        }
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
        Optional<EventReplay> replay = completedEventReplay(threadId);
        if (replay.isPresent()) {
            EventReplay value = replay.get();
            return new ContextView(
                    value.messages(),
                    List.of(),
                    value.completedEventId(),
                    latestCheckpoint(threadId));
        }
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

    public synchronized SessionProjection sessionProjection(String threadId) {
        List<RuntimeEvent> visible = events(threadId, 0);
        String branchId = activeBranchId(threadId);
        String logIdentity = projectionLogIdentity(threadId);
        long currentCursor = visible.stream().mapToLong(RuntimeEvent::id).max().orElse(0L);
        Optional<SessionProjection> cached = loadSessionProjection(threadId, branchId);
        SessionProjection base = cached
                .filter(value -> value.projectionVersion() == SESSION_PROJECTION_VERSION)
                .filter(value -> value.logIdentity().equals(logIdentity))
                .filter(value -> value.eventCursor() <= currentCursor)
                .orElse(null);
        if (base != null && base.eventCursor() == currentCursor) {
            return base;
        }

        ProjectionAccumulator accumulator = base == null
                ? new ProjectionAccumulator(logIdentity)
                : ProjectionAccumulator.from(base);
        long afterCursor = base == null ? 0L : base.eventCursor();
        for (RuntimeEvent event : visible) {
            if (event.id() > afterCursor) {
                accumulator.apply(event);
            }
        }
        SessionProjection projection = accumulator.finish(currentCursor);
        saveSessionProjection(threadId, branchId, projection);
        return projection;
    }

    private Optional<SessionProjection> loadSessionProjection(String threadId, String branchId) {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT projection_version, log_identity, event_cursor, data_json
                FROM runtime_session_projections
                WHERE thread_id = ? AND branch_id = ?
                """)) {
            ps.setString(1, threadId);
            ps.setString(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                try {
                    JsonNode data = MAPPER.readTree(rs.getString("data_json"));
                    SessionProjection projection = new SessionProjection(
                            rs.getInt("projection_version"),
                            rs.getString("log_identity"),
                            rs.getLong("event_cursor"),
                            data.path("title").asText(""),
                            data.path("state").asText("idle"),
                            data.path("input_tokens").asLong(0),
                            data.path("output_tokens").asLong(0),
                            data.path("cached_input_tokens").asLong(0),
                            data.path("estimated_cost_cny").asDouble(0D),
                            data.path("tool_calls").asLong(0),
                            data.path("tool_failures").asLong(0),
                            data.path("hook_calls").asLong(0),
                            data.path("hook_failures").asLong(0));
                    return Optional.of(projection);
                } catch (Exception corrupted) {
                    log.warn("runtime session projection 损坏，将从事件重建: thread={}, branch={}",
                            threadId, branchId);
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取 runtime session projection 失败: " + e.getMessage(), e);
        }
    }

    private void saveSessionProjection(
            String threadId, String branchId, SessionProjection projection) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("title", projection.title());
        data.put("state", projection.state());
        data.put("input_tokens", projection.inputTokens());
        data.put("output_tokens", projection.outputTokens());
        data.put("cached_input_tokens", projection.cachedInputTokens());
        data.put("estimated_cost_cny", projection.estimatedCostCny());
        data.put("tool_calls", projection.toolCalls());
        data.put("tool_failures", projection.toolFailures());
        data.put("hook_calls", projection.hookCalls());
        data.put("hook_failures", projection.hookFailures());
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO runtime_session_projections(
                    thread_id, branch_id, projection_version, log_identity,
                    event_cursor, data_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(thread_id, branch_id) DO UPDATE SET
                    projection_version = excluded.projection_version,
                    log_identity = excluded.log_identity,
                    event_cursor = excluded.event_cursor,
                    data_json = excluded.data_json,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, threadId);
            ps.setString(2, branchId);
            ps.setInt(3, projection.projectionVersion());
            ps.setString(4, projection.logIdentity());
            ps.setLong(5, projection.eventCursor());
            ps.setString(6, data.toString());
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入 runtime session projection 失败: " + e.getMessage(), e);
        }
    }

    private String projectionLogIdentity(String threadId) {
        StringBuilder identity = new StringBuilder(threadId);
        for (BranchRecord branch : activeLineage(threadId)) {
            identity.append('|').append(branch.id()).append('@').append(branch.forkEventId());
        }
        return identity.toString();
    }

    private static String projectionTitle(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = input.replaceAll("\\s+", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 80) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, 80)) + "...";
    }

    private static final class ProjectionAccumulator {
        private final String logIdentity;
        private String title = "";
        private String state = "idle";
        private long inputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private double estimatedCostCny;
        private long toolCalls;
        private long toolFailures;
        private long hookCalls;
        private long hookFailures;

        private ProjectionAccumulator(String logIdentity) {
            this.logIdentity = logIdentity;
        }

        private static ProjectionAccumulator from(SessionProjection projection) {
            ProjectionAccumulator value = new ProjectionAccumulator(projection.logIdentity());
            value.title = projection.title();
            value.state = projection.state();
            value.inputTokens = projection.inputTokens();
            value.outputTokens = projection.outputTokens();
            value.cachedInputTokens = projection.cachedInputTokens();
            value.estimatedCostCny = projection.estimatedCostCny();
            value.toolCalls = projection.toolCalls();
            value.toolFailures = projection.toolFailures();
            value.hookCalls = projection.hookCalls();
            value.hookFailures = projection.hookFailures();
            return value;
        }

        private void apply(RuntimeEvent event) {
            try {
                JsonNode data = MAPPER.readTree(event.data());
                switch (event.type()) {
                    case "turn.started" -> {
                        if (title.isBlank()) {
                            title = projectionTitle(data.path("input").asText(""));
                        }
                        state = "running";
                    }
                    case "turn.completed" -> state = "idle";
                    case "turn.failed" -> state = "failed";
                    case "turn.rejected" -> state = "rejected";
                    case "session.state" -> state = data.path("state").asText(state);
                    case "model.usage" -> {
                        inputTokens += data.path("input_tokens").asLong(0);
                        outputTokens += data.path("output_tokens").asLong(0);
                        cachedInputTokens += data.path("cached_input_tokens").asLong(0);
                        estimatedCostCny += data.path("estimated_cost_cny").asDouble(0D);
                    }
                    case "tool.calls" -> toolCalls += data.path("calls").size();
                    case "tool.results" -> {
                        JsonNode results = data.path("results");
                        if (results.isArray()) {
                            for (JsonNode result : results) {
                                if (!"SUCCESS".equals(result.path("status").asText(""))) {
                                    toolFailures++;
                                }
                            }
                        }
                    }
                    case "hook.result" -> {
                        hookCalls++;
                        String decision = data.path("decision").asText("CONTINUE");
                        if (!"CONTINUE".equals(decision)) {
                            hookFailures++;
                        }
                    }
                    default -> { }
                }
            } catch (Exception ignored) {
                // 单条坏事件不破坏投影；事件日志仍保留以供诊断。
            }
        }

        private SessionProjection finish(long cursor) {
            return new SessionProjection(
                    SESSION_PROJECTION_VERSION,
                    logIdentity,
                    cursor,
                    title,
                    state,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    estimatedCostCny,
                    toolCalls,
                    toolFailures,
                    hookCalls,
                    hookFailures);
        }
    }

    private Optional<EventReplay> completedEventReplay(String threadId) {
        List<RuntimeEvent> visible = events(threadId, 0);
        Map<String, Long> completedTurns = new HashMap<>();
        for (RuntimeEvent event : visible) {
            if (!"turn.completed".equals(event.type())) {
                continue;
            }
            String turnId = eventTurnId(event);
            if (!turnId.isBlank()) {
                completedTurns.put(turnId, event.id());
            }
        }

        RuntimeEvent selected = null;
        RunEvent.ModelContext selectedContext = null;
        String selectedTurnId = "";
        for (RuntimeEvent event : visible) {
            if (!"model.context".equals(event.type())) {
                continue;
            }
            String turnId = eventTurnId(event);
            Long completedEventId = completedTurns.get(turnId);
            if (completedEventId == null || completedEventId < event.id()) {
                continue;
            }
            Optional<RunEvent.ModelContext> decoded = RunEventJsonCodec.decodeModelContext(event.data());
            if (decoded.isPresent() && (selected == null || event.id() > selected.id())) {
                selected = event;
                selectedContext = decoded.get();
                selectedTurnId = turnId;
            }
        }
        if (selected == null || selectedContext == null) {
            return Optional.empty();
        }

        long completedEventId = completedTurns.get(selectedTurnId);
        List<LlmClient.Message> messages = new ArrayList<>(selectedContext.toLlmMessages());
        StringBuilder streamedOutput = new StringBuilder();
        boolean assistantMessageRecorded = messages.stream()
                .anyMatch(message -> "assistant".equals(message.role()));
        for (RuntimeEvent event : visible) {
            if (event.id() <= selected.id() || event.id() > completedEventId
                    || !selectedTurnId.equals(eventTurnId(event))) {
                continue;
            }
            if ("model.message".equals(event.type())) {
                Optional<RunEvent.ModelMessage> decoded = RunEventJsonCodec.decodeModelMessage(event.data());
                if (decoded.isPresent()) {
                    LlmClient.Message message = decoded.get().message().toLlmMessage();
                    messages.add(message);
                    assistantMessageRecorded |= "assistant".equals(message.role());
                }
            } else if ("message.delta".equals(event.type())) {
                try {
                    streamedOutput.append(MAPPER.readTree(event.data()).path("content").asText(""));
                } catch (Exception ignored) {
                    // model.message 是主协议；delta 仅作为旧写入端的兼容回退。
                }
            }
        }
        if (!assistantMessageRecorded && !streamedOutput.isEmpty()) {
            messages.add(LlmClient.Message.assistant(streamedOutput.toString()));
        }
        return Optional.of(new EventReplay(List.copyOf(messages), completedEventId));
    }

    private static String eventTurnId(RuntimeEvent event) {
        try {
            return MAPPER.readTree(event.data()).path("turn_id").asText("");
        } catch (Exception ignored) {
            return "";
        }
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RuntimeThreadStore 已关闭");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        notifyAll();
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
        runStore.close();
    }
}
