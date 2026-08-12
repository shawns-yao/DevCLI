package com.devcli.session;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.api.RuntimeCheckpointCandidateFactory;
import com.devcli.runtime.api.RuntimeThreadStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * CLI 与 Runtime API 共用的持久会话树门面。
 *
 * <p>分支、事件和 checkpoint 全部写入 runtime.db。该服务只切换对话上下文，
 * 不调用 Side-Git，也不改变工作区文件。</p>
 */
public final class SessionTreeService implements AutoCloseable {
    private final RuntimeThreadStore store;
    private final Agent agent;
    private final String sessionId;

    public static SessionTreeService open(Path projectRoot, Agent agent) {
        try {
            RuntimeThreadStore store = new RuntimeThreadStore(RuntimeThreadStore.defaultDbPath());
            return new SessionTreeService(store, agent, sessionId(projectRoot));
        } catch (Exception error) {
            throw new IllegalStateException("无法打开持久会话树: " + error.getMessage(), error);
        }
    }

    public SessionTreeService(RuntimeThreadStore store, Agent agent, String sessionId) {
        this.store = Objects.requireNonNull(store, "store");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.sessionId = requireIdentifier(sessionId, "sessionId");
        this.store.ensureThread(this.sessionId);
        restoreActiveContext();
    }

    public synchronized String sessionId() {
        return sessionId;
    }

    public synchronized SessionTree snapshot() {
        List<SessionTree.Branch> branches = store.branches(sessionId).stream()
                .map(branch -> new SessionTree.Branch(
                        branch.id(), branch.name(), branch.parentBranchId(),
                        branch.forkEventId(), branch.active()))
                .toList();
        List<SessionTree.MessageNode> messages = store.messageNodes(sessionId).stream()
                .map(node -> new SessionTree.MessageNode(
                        node.id(), node.parentId(), node.branchId(), node.role(),
                        node.preview(), node.eventId()))
                .toList();
        return new SessionTree(sessionId, store.activeBranchId(sessionId), branches, messages);
    }

    public synchronized CommandResult execute(String payload) {
        String command = payload == null ? "status" : payload.trim();
        if (command.isBlank() || command.equalsIgnoreCase("status")) {
            return new CommandResult(formatStatus(), false);
        }
        if (command.equalsIgnoreCase("tree") || command.equalsIgnoreCase("list")) {
            return new CommandResult(formatTree(), false);
        }
        if (command.regionMatches(true, 0, "fork ", 0, 5)) {
            return fork(command.substring(5).trim());
        }
        if (command.regionMatches(true, 0, "use ", 0, 4)) {
            return use(command.substring(4).trim());
        }
        throw new IllegalArgumentException(
                "用法: /session status | /session tree | /session fork <name> [from <message-id>] | /session use <id|name>");
    }

    public synchronized CommandResult fork(String specification) {
        ForkRequest request = parseForkRequest(specification);
        String normalized = normalizeBranchName(request.name());
        boolean duplicate = snapshot().branches().stream()
                .anyMatch(node -> node.name().equalsIgnoreCase(normalized));
        if (duplicate) {
            throw new IllegalArgumentException("会话分支名称已存在: " + normalized);
        }
        long eventId = request.messageId().isBlank()
                ? 0 : store.forkAnchor(sessionId, request.messageId()).eventId();
        RuntimeThreadStore.BranchRecord branch = store.createBranch(sessionId, normalized, eventId);
        store.activateBranch(sessionId, branch.id());
        restoreActiveContext();
        return new CommandResult("已创建并切换到会话分支: " + normalized, true);
    }

    public synchronized CommandResult use(String idOrName) {
        String target = idOrName == null ? "" : idOrName.trim();
        if (target.isBlank()) {
            throw new IllegalArgumentException("会话分支 id 或名称不能为空");
        }
        List<SessionTree.Branch> matches = snapshot().branches().stream()
                .filter(node -> node.id().equals(target) || node.name().equalsIgnoreCase(target))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("未找到会话分支: " + target);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("会话分支名称不唯一，请使用 branch id: " + target);
        }
        SessionTree.Branch branch = matches.get(0);
        store.activateBranch(sessionId, branch.id());
        restoreActiveContext();
        return new CommandResult("已切换到会话分支: " + branch.name(), true);
    }

    /** `/clear` 使用空白子分支保留原历史，避免破坏既有分支和 checkpoint。 */
    public synchronized CommandResult clearToNewBranch() {
        String name = "clear-" + System.currentTimeMillis();
        RuntimeThreadStore.BranchRecord branch = store.createEmptyBranch(sessionId, name);
        store.activateBranch(sessionId, branch.id());
        restoreActiveContext();
        return new CommandResult("当前对话已清空，新会话分支: " + name, true);
    }

    /** 持久化已完成 turn，并把结构化执行的顶层输入输出同步回 ReAct 对话。 */
    public synchronized void recordCompletedTurn(String input, String output,
                                                 List<LlmClient.Message> modelMessages) {
        long coverage = store.appendCompletedTurn(sessionId, input, output);
        List<LlmClient.Message> history;
        if (modelMessages == null || modelMessages.isEmpty()) {
            history = new ArrayList<>(agent.getConversationHistory());
            history.add(LlmClient.Message.user(input == null ? "" : input));
            history.add(LlmClient.Message.assistant(output == null ? "" : output));
            agent.replaceConversationHistory(history);
        } else {
            history = new ArrayList<>(modelMessages);
        }
        Optional<com.devcli.runtime.api.TurnRunner.CheckpointCandidate> checkpoint =
                RuntimeCheckpointCandidateFactory.fromHistory(history, true);
        if (checkpoint.isPresent()) {
            String previousBoundary = store.latestCheckpointOnActiveBranch(sessionId)
                    .map(RuntimeThreadStore.RuntimeCheckpoint::summary).orElse("");
            if (!checkpoint.get().summary().equals(previousBoundary)) {
                store.saveCheckpoint(sessionId, coverage, checkpoint.get());
            }
        }
    }

    public synchronized String formatStatus() {
        SessionTree tree = snapshot();
        SessionTree.Branch active = tree.branches().stream()
                .filter(SessionTree.Branch::active).findFirst().orElse(null);
        return "会话: " + tree.sessionId()
                + "\n当前分支: " + (active == null ? tree.activeBranchId() : active.name())
                + "\n分支数量: " + tree.branches().size()
                + "\n消息节点: " + tree.messages().size();
    }

    public synchronized String formatTree() {
        SessionTree tree = snapshot();
        Map<String, SessionTree.Branch> byId = new HashMap<>();
        tree.branches().forEach(node -> byId.put(node.id(), node));
        StringBuilder result = new StringBuilder("会话树 ").append(tree.sessionId());
        for (SessionTree.Branch node : tree.branches()) {
            int depth = depth(node, byId);
            result.append('\n').append("  ".repeat(Math.max(0, depth)))
                    .append(node.active() ? "* " : "- ")
                    .append(node.name()).append(" [").append(node.id()).append(']');
            tree.messages().stream()
                    .filter(message -> message.branchId().equals(node.id()))
                    .forEach(message -> result.append('\n')
                            .append("  ".repeat(Math.max(0, depth + 1)))
                            .append("- ").append(message.role()).append(' ')
                            .append(message.preview()).append(" [").append(message.id()).append(']'));
        }
        return result.toString();
    }

    private void restoreActiveContext() {
        RuntimeThreadStore.ContextView view = store.contextView(sessionId);
        List<LlmClient.Message> seed = new ArrayList<>(view.checkpointMessages());
        for (RuntimeThreadStore.TurnRecord turn : view.turns()) {
            seed.add(LlmClient.Message.user(turn.input()));
            seed.add(LlmClient.Message.assistant(turn.output()));
        }
        agent.clearHistory();
        agent.seedHistory(seed);
    }

    private static int depth(SessionTree.Branch node, Map<String, SessionTree.Branch> byId) {
        int depth = 0;
        String parent = node.parentId();
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        while (!parent.isBlank() && visited.add(parent)) {
            SessionTree.Branch parentNode = byId.get(parent);
            if (parentNode == null) break;
            depth++;
            parent = parentNode.parentId();
        }
        return depth;
    }

    private static String normalizeBranchName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("分支名称只能包含字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

    private static ForkRequest parseForkRequest(String value) {
        String specification = value == null ? "" : value.trim();
        int separator = specification.toLowerCase(Locale.ROOT).lastIndexOf(" from ");
        if (separator < 0) {
            return new ForkRequest(specification, "");
        }
        return new ForkRequest(
                specification.substring(0, separator).trim(),
                specification.substring(separator + 6).trim());
    }

    private static String sessionId(Path projectRoot) {
        Path normalized = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath().normalize();
        String key = normalized.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            key = key.toLowerCase(Locale.ROOT);
        }
        return "cli_" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private static String requireIdentifier(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IllegalArgumentException(label + " 格式无效");
        }
        return normalized;
    }

    @Override
    public synchronized void close() {
        store.close();
    }

    public record CommandResult(String message, boolean contextChanged) {
        public CommandResult {
            message = message == null ? "" : message;
        }
    }

    private record ForkRequest(String name, String messageId) {
        private ForkRequest {
            name = name == null ? "" : name;
            messageId = messageId == null ? "" : messageId;
        }
    }
}
