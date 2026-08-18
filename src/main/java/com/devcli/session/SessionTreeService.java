package com.devcli.session;

import com.devcli.agent.Agent;
import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.runtime.RunCoordinator;
import com.devcli.runtime.api.RunEventJsonCodec;
import com.devcli.runtime.api.RuntimeThreadStore;
import com.devcli.runtime.event.RunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** CLI 与 Runtime API 共用的持久会话分支服务。 */
public final class SessionTreeService {
    private static final Logger log = LoggerFactory.getLogger(SessionTreeService.class);
    private static final String SESSION_PROPERTY = "devcli.session.id";
    private static final String SESSION_ENV = "DEVCLI_SESSION_ID";

    private final Agent agent;
    private final RuntimeThreadStore store;
    private final RunCoordinator runCoordinator;
    private final Path currentSessionFile;
    private final AtomicBoolean legacyNoticePending = new AtomicBoolean(true);
    private String threadId;

    private SessionTreeService(Agent agent, RuntimeThreadStore store,
                               Path currentSessionFile, String threadId) {
        this.agent = java.util.Objects.requireNonNull(agent, "agent");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.runCoordinator = new RunCoordinator(store);
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

    /**
     * 记录 CLI 模型可见上下文和顶层结果。返回值仅在持久化失败时包含错误摘要，
     * 由终端显示；可选 JSONL 归档不参与恢复。
     */
    public synchronized Optional<String> recordTurn(
            String mode, String submittedInput, String expandedInput,
            String response, List<LlmClient.Message> modelMessages) {
        String turnId = "turn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String branchId = activeBranchId();
        try {
            runCoordinator.submitInteractive(
                    turnId, threadId, branchId, normalizedMode(mode), safe(submittedInput));
            if (!runCoordinator.start(turnId)) {
                throw new IllegalStateException("Run 状态无法进入 running: " + turnId);
            }
            append(turnId, new RunEvent.TurnStarted(safe(submittedInput)));

            List<LlmClient.Message> context = buildPersistedContext(
                    expandedInput, response, modelMessages);
            append(turnId, RunEvent.ModelContext.from(0, context));
            String finalOutput = response == null || response.isBlank()
                    ? latestAssistantContent(context)
                    : response;
            if (!finalOutput.isBlank()) {
                append(turnId, new RunEvent.MessageDelta(finalOutput));
            }
            append(turnId, new RunEvent.TurnCompleted("completed"));
            if (!runCoordinator.complete(turnId, finalOutput)) {
                throw new IllegalStateException("Run 状态无法进入 completed: " + turnId);
            }
            if (modelMessages == null || modelMessages.isEmpty()) {
                replaceAgentHistory(context);
            }
            return Optional.empty();
        } catch (Exception e) {
            runCoordinator.fail(turnId, e.getMessage());
            log.warn("CLI session persistence failed: thread={}, branch={}, turn={}",
                    threadId, branchId, turnId, e);
            return Optional.of("会话持久化失败: " + e.getMessage());
        }
    }

    private List<LlmClient.Message> buildPersistedContext(
            String expandedInput, String response, List<LlmClient.Message> modelMessages) {
        if (modelMessages != null && !modelMessages.isEmpty()) {
            return List.copyOf(modelMessages);
        }
        List<LlmClient.Message> context = new ArrayList<>(agent.getConversationHistory());
        context.add(LlmClient.Message.user(safe(expandedInput)));
        if (response != null && !response.isBlank()) {
            context.add(LlmClient.Message.assistant(response));
        }
        return List.copyOf(context);
    }

    private long append(String turnId, RunEvent event) {
        return store.appendEvent(
                threadId, event.type(), RunEventJsonCodec.encode(event, turnId));
    }

    private void restoreActiveBranch() {
        agent.getTurnInbox().clear();
        RuntimeThreadStore.ContextView view = store.contextView(threadId);
        List<LlmClient.Message> history = new ArrayList<>(view.checkpointMessages());
        for (RuntimeThreadStore.TurnRecord turn : view.turns()) {
            history.add(LlmClient.Message.user(turn.input()));
            history.add(LlmClient.Message.assistant(turn.output()));
        }
        replaceAgentHistory(history);
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

    private static String normalizedMode(String mode) {
        return mode == null || mode.isBlank()
                ? "react" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static String latestAssistantContent(List<LlmClient.Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            LlmClient.Message message = messages.get(index);
            if (message != null && "assistant".equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
