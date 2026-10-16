package com.devcli.runtime.api;

import com.devcli.event.RunEvent;
import com.devcli.event.RunEventSink;

import java.util.Objects;

/** 将强类型运行事件持久化到指定 Runtime thread/turn。 */
public final class RuntimeEventPublisher implements RunEventSink {
    private final RuntimeThreadStore store;
    private final String threadId;
    private final String turnId;
    private final String branchId;
    private boolean messageDeltaEmitted;
    private RunEvent.ExecutionState state;
    private String assistantOutput = "";
    private RunEventSink.PersistenceFailure failure;

    public RuntimeEventPublisher(RuntimeThreadStore store, String threadId, String turnId) {
        this.store = Objects.requireNonNull(store, "store");
        this.threadId = Objects.requireNonNullElse(threadId, "");
        this.turnId = Objects.requireNonNullElse(turnId, "");
        this.branchId = store.activeBranchId(threadId);
    }

    @Override
    public synchronized void emit(RunEvent event) {
        publish(event);
    }

    synchronized long publish(RunEvent event) {
        Objects.requireNonNull(event, "event");
        if (failure != null) throw failure;
        long eventId;
        try {
            eventId = store.appendEvent(threadId, branchId, event.type(), RunEventJsonCodec.encode(event, turnId));
        } catch (RuntimeException error) {
            failure = new RunEventSink.PersistenceFailure(error);
            throw failure;
        }
        if (event instanceof RunEvent.MessageDelta) {
            messageDeltaEmitted = true;
        } else if (event instanceof RunEvent.ExecutionStateChanged changed) {
            state = changed.state();
        } else if (event instanceof RunEvent.ModelMessage message && "assistant".equals(message.message().role())) {
            assistantOutput = message.message().content();
        }
        return eventId;
    }

    synchronized boolean hasMessageDelta() {
        return messageDeltaEmitted;
    }

    synchronized RunEvent.ExecutionState state() { return state; }
    synchronized String assistantOutput() { return assistantOutput; }
}
