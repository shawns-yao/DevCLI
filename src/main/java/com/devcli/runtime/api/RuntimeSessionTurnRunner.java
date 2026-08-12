package com.devcli.runtime.api;

import com.devcli.agent.AgentTurnInbox;
import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.AgentSessionRuntime;
import com.devcli.runtime.event.RunEventSink;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime API 的持久会话执行器：每个 thread 绑定一个 AgentSessionRuntime，
 * 队列、历史、取消和工具资源不再在每个 HTTP turn 中重新创建。
 */
public final class RuntimeSessionTurnRunner implements TurnRunner, AutoCloseable {
    private final LlmClient llmClient;
    private final RuntimeThreadStore store;
    private final Path projectPath;
    private final int checkpointTriggerTokens;
    private final ConcurrentHashMap<String, AgentSessionRuntime> sessions = new ConcurrentHashMap<>();

    public RuntimeSessionTurnRunner(LlmClient llmClient, RuntimeThreadStore store, Path projectPath) {
        this(llmClient, store, projectPath, RuntimeCheckpointPolicy.configuredTriggerTokens());
    }

    public RuntimeSessionTurnRunner(LlmClient llmClient, RuntimeThreadStore store,
                                    Path projectPath, int checkpointTriggerTokens) {
        this.llmClient = java.util.Objects.requireNonNull(llmClient, "llmClient");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.projectPath = java.util.Objects.requireNonNull(projectPath, "projectPath")
                .toAbsolutePath().normalize();
        this.checkpointTriggerTokens = Math.max(0, checkpointTriggerTokens);
    }

    Path projectPath() {
        return projectPath;
    }

    @Override
    public TurnResult run(String threadId, String input, RunEventSink eventSink) {
        AgentSessionRuntime session = session(threadId);
        session.setRunEventSink(eventSink);
        eventSink.emit(new com.devcli.runtime.event.RunEvent.SessionStateChanged(
                threadId, "running", "turn_started"));
        List<LlmClient.Message> before = session.agent().getConversationHistory();
        try {
            AgentSessionRuntime.RunResult result = session.runBlocking(input);
            boolean compacted = checkpointTriggerTokens > 0
                    && session.agent().compactHistoryForPersistence(checkpointTriggerTokens);
            List<LlmClient.Message> history = session.agent().getConversationHistory();
            compacted |= hasNewCompactionBoundary(before, history);
            TurnRunner.CheckpointCandidate checkpoint = RuntimeCheckpointCandidateFactory
                    .fromHistory(history, compacted)
                    .orElse(null);
            String output = result.output().isBlank() ? latestAssistantContent(history) : result.output();
            return new TurnResult(output, checkpoint);
        } finally {
            store.saveQueueSnapshot(threadId, session.inbox().snapshot());
            eventSink.emit(new com.devcli.runtime.event.RunEvent.SessionStateChanged(
                    threadId, "idle", "turn_finished"));
        }
    }

    @Override
    public QueueResult enqueueSteering(String threadId, String input) {
        return enqueue(threadId, input, AgentTurnInbox.Channel.STEERING);
    }

    @Override
    public QueueResult enqueueFollowUp(String threadId, String input) {
        return enqueue(threadId, input, AgentTurnInbox.Channel.FOLLOW_UP);
    }

    @Override
    public QueueResult clearQueue(String threadId) {
        AgentSessionRuntime session = session(threadId);
        session.inbox().clear();
        store.saveQueueSnapshot(threadId, session.inbox().snapshot());
        return new QueueResult(true, AgentTurnInbox.Channel.FOLLOW_UP,
                "cleared", 0, 0);
    }

    @Override
    public boolean cancelCurrent(String threadId) {
        AgentSessionRuntime session = session(threadId);
        boolean running = session.isRunning();
        session.abort();
        return running;
    }

    @Override
    public void resetSession(String threadId) {
        AgentSessionRuntime removed = sessions.remove(threadId);
        if (removed != null) {
            removed.close();
        }
    }

    private QueueResult enqueue(String threadId, String input, AgentTurnInbox.Channel channel) {
        AgentSessionRuntime session = session(threadId);
        AgentTurnInbox.EnqueueResult result = channel == AgentTurnInbox.Channel.STEERING
                ? session.inbox().enqueueSteering(input)
                : session.inbox().enqueueFollowUp(input);
        if (result.accepted()) {
            store.saveQueueSnapshot(threadId, session.inbox().snapshot());
        }
        return QueueResult.from(result, channel);
    }

    private AgentSessionRuntime session(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is required");
        }
        return sessions.computeIfAbsent(threadId, id -> {
            AgentSessionRuntime session = AgentSessionRuntime.create(llmClient, projectPath,
                    RunEventSink.NO_OP);
            RuntimeThreadStore.ContextView view = store.contextView(id);
            List<LlmClient.Message> seed = new ArrayList<>(view.checkpointMessages());
            for (RuntimeThreadStore.TurnRecord turn : view.turns()) {
                seed.add(LlmClient.Message.user(turn.input()));
                seed.add(LlmClient.Message.assistant(turn.output()));
            }
            session.seedHistory(seed);
            AgentTurnInbox.Snapshot queued = store.queueSnapshot(id);
            queued.steering().forEach(item -> session.inbox().enqueueSteering(item.text()));
            queued.followUp().forEach(item -> session.inbox().enqueueFollowUp(item.text()));
            return session;
        });
    }

    private static boolean hasNewCompactionBoundary(List<LlmClient.Message> before,
                                                     List<LlmClient.Message> after) {
        return !latestCompactionBoundary(before).equals(latestCompactionBoundary(after));
    }

    private static String latestCompactionBoundary(List<LlmClient.Message> messages) {
        String latest = "";
        if (messages == null) return latest;
        for (LlmClient.Message message : messages) {
            if (message != null && CompactBoundaryMetadata
                    .parseFromSummaryMessage(message.content()).isPresent()) {
                latest = message.content();
            }
        }
        return latest;
    }

    private static String latestAssistantContent(List<LlmClient.Message> history) {
        if (history == null) return "";
        for (int index = history.size() - 1; index >= 0; index--) {
            LlmClient.Message message = history.get(index);
            if (message != null && "assistant".equals(message.role())
                    && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "";
    }

    @Override
    public void close() {
        sessions.values().forEach(AgentSessionRuntime::close);
        sessions.clear();
    }
}
