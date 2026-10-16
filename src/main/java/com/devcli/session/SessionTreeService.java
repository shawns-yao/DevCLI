package com.devcli.session;

import com.devcli.agent.Agent;
import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.api.ConversationTurnRecorder;
import com.devcli.runtime.api.RuntimeThreadStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** CLI 与 Runtime API 共用的持久会话分支服务。 */
public final class SessionTreeService {
    private static final String SESSION_PROPERTY = "devcli.session.id";
    private static final String SESSION_ENV = "DEVCLI_SESSION_ID";

    private final Agent agent;
    private final RuntimeThreadStore store;
    private final Path currentSessionFile;
    private final AtomicBoolean legacyNoticePending = new AtomicBoolean(true);
    private String threadId;

    private SessionTreeService(Agent agent, RuntimeThreadStore store,
                               Path currentSessionFile, String threadId) {
        this.agent = java.util.Objects.requireNonNull(agent, "agent");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.currentSessionFile = currentSessionFile;
        this.threadId = threadId;
        restoreActiveBranch();
    }

    public static SessionTreeService open(Agent agent, RuntimeThreadStore store) {
        Path parent = store.dbPath().getParent();
        Path pointer = (parent == null ? Path.of(".") : parent).resolve("cli-session.current");
        String configured = ConfigResolver.optional(SESSION_PROPERTY, SESSION_ENV);
        String threadId;
        if (configured != null) {
            if (!store.exists(configured)) {
                throw new IllegalArgumentException("配置的会话不存在: " + configured);
            }
            threadId = configured;
        } else {
            threadId = readPointer(pointer).filter(store::exists).orElseGet(store::createThread);
        }
        SessionTreeService service = new SessionTreeService(agent, store, pointer, threadId);
        service.persistPointer();
        return service;
    }

    public synchronized String threadId() {
        return threadId;
    }

    public synchronized String activeBranchId() {
        return store.activeBranchId(threadId);
    }

    public synchronized SessionTree tree() {
        List<SessionTree.Branch> branches = store.branches(threadId).stream()
                .map(branch -> new SessionTree.Branch(
                        branch.id(), branch.name(), branch.parentBranchId(),
                        branch.forkEventId(), branch.active(), branch.createdAt()))
                .toList();
        return new SessionTree(threadId, activeBranchId(), branches);
    }

    public synchronized String renderTree() {
        SessionTree tree = tree();
        StringBuilder output = new StringBuilder("会话 ").append(tree.threadId()).append('\n');
        for (SessionTree.Branch branch : tree.branches()) {
            output.append(branch.active() ? "* " : "  ")
                    .append(branch.name())
                    .append(" [").append(branch.id()).append(']');
            if (!branch.parentBranchId().isBlank()) {
                output.append(" <- ").append(branch.parentBranchId())
                        .append('@').append(branch.forkEventId());
            }
            output.append('\n');
        }
        return output.toString().stripTrailing();
    }

    public synchronized String status() {
        RuntimeThreadStore.SessionProjection projection = store.sessionProjection(threadId);
        return "当前会话: " + threadId
                + "\n当前分支: " + activeBranchId()
                + "\n分支数量: " + store.branches(threadId).size()
                + "\n事件游标: " + projection.eventCursor()
                + "\n状态: " + projection.state()
                + (projection.title().isBlank() ? "" : "\n标题: " + projection.title());
    }

    public synchronized String fork(String name) {
        return fork(name, 0);
    }

    public synchronized String fork(String name, long fromEventId) {
        String normalized = normalizeName(name);
        RuntimeThreadStore.BranchRecord branch = store.createBranch(
                threadId, normalized, Math.max(0, fromEventId));
        store.activateBranch(threadId, branch.id());
        restoreActiveBranch();
        return "已创建并切换到分支: " + branch.name() + " [" + branch.id() + "]";
    }

    public synchronized String use(String idOrName) {
        String target = idOrName == null ? "" : idOrName.trim();
        if (target.isBlank()) {
            throw new IllegalArgumentException("分支名称或 id 不能为空");
        }
        List<RuntimeThreadStore.BranchRecord> matches = store.branches(threadId).stream()
                .filter(branch -> branch.id().equals(target) || branch.name().equals(target))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("未找到分支: " + target);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("分支名称不唯一，请使用 branch id: " + target);
        }
        RuntimeThreadStore.BranchRecord branch = matches.get(0);
        store.activateBranch(threadId, branch.id());
        restoreActiveBranch();
        return "已切换到分支: " + branch.name() + " [" + branch.id() + "]";
    }

    public synchronized String clearCurrent() {
        String name = "clear-" + Long.toHexString(System.currentTimeMillis());
        RuntimeThreadStore.BranchRecord branch = store.createRootBranch(threadId, name);
        store.activateBranch(threadId, branch.id());
        restoreActiveBranch();
        return "当前对话已清空；原历史保留在会话树，新分支为 " + branch.name();
    }

    public synchronized String newSession() {
        threadId = store.createThread();
        persistPointer();
        restoreActiveBranch();
        return "已创建并切换到会话: " + threadId;
    }

    public synchronized String useThread(String requestedThreadId) {
        String target = requestedThreadId == null ? "" : requestedThreadId.trim();
        if (target.isBlank() || !store.exists(target)) {
            throw new IllegalArgumentException("未找到会话: " + target);
        }
        threadId = target;
        persistPointer();
        restoreActiveBranch();
        return "已切换到会话: " + threadId;
    }

    public boolean consumeLegacyAliasNotice() {
        return legacyNoticePending.compareAndSet(true, false);
    }

    /** 先绑定日志，再执行当前回合；异常退出也保留中途消息和工具事实。 */
    public <T> T runTurn(String mode, String input, String modelInput,
                         java.util.concurrent.Callable<T> action,
                         java.util.function.Function<T, String> output) throws Exception {
        return ConversationTurnRecorder.run(agent, store, threadId(), mode, input, modelInput, action, output);
    }

    public com.devcli.memory.CompactionResult compactHistoryNow(int keep) throws Exception {
        return runTurn("compact", "/compact", "/compact", () -> agent.compactHistoryNow(keep), result -> "");
    }

    private void restoreActiveBranch() {
        agent.getTurnInbox().clear();
        RuntimeThreadStore.ContextView view = store.contextView(threadId);
        replaceAgentHistory(view.messages());
        view.userInputs().forEach(agent::recordUserIntent);
        if (!view.messages().isEmpty()) agent.markTrustedIntentIncomplete();
    }

    private void replaceAgentHistory(List<LlmClient.Message> history) {
        agent.clearHistory();
        if (history == null || history.isEmpty()) {
            return;
        }
        List<LlmClient.Message> seed = new ArrayList<>(history);
        if (!seed.isEmpty() && "system".equals(seed.get(0).role())) {
            seed.remove(0);
        }
        agent.seedHistory(seed);
    }

    private void persistPointer() {
        try {
            Path parent = currentSessionFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = currentSessionFile.resolveSibling(
                    currentSessionFile.getFileName() + ".tmp");
            Files.writeString(temporary, threadId + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, currentSessionFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, currentSessionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法保存当前 CLI 会话指针: " + e.getMessage(), e);
        }
    }

    private static Optional<String> readPointer(Path pointer) {
        try {
            if (!Files.isRegularFile(pointer)) {
                return Optional.empty();
            }
            String value = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("分支名称不能为空");
        }
        String normalized = name.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("分支名称只能包含字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

}
