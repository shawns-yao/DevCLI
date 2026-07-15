package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;

import java.util.Objects;

/** 将强类型运行事件持久化到指定 Runtime thread/turn。 */
final class RuntimeEventPublisher implements RunEventSink {
    private final RuntimeThreadStore store;
    private final String threadId;
    private final String turnId;
    private boolean messageDeltaEmitted;

    RuntimeEventPublisher(RuntimeThreadStore store, String threadId, String turnId) {
        this.store = Objects.requireNonNull(store, "store");
        this.threadId = Objects.requireNonNullElse(threadId, "");
        this.turnId = Objects.requireNonNullElse(turnId, "");
    }

    @Override
    public synchronized void emit(RunEvent event) {
        publish(event);
    }

    synchronized long publish(RunEvent event) {
        Objects.requireNonNull(event, "event");
        long eventId = store.appendEvent(
                threadId,
                event.type(),
                RunEventJsonCodec.encode(event, turnId));
        if (event instanceof RunEvent.MessageDelta) {
            messageDeltaEmitted = true;
        }
        return eventId;
    }

    synchronized boolean hasMessageDelta() {
        return messageDeltaEmitted;
    }
}
