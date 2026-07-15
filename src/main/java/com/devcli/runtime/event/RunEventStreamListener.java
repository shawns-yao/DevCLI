package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;

import java.util.Objects;

/** 将模型流式回调转换为共享运行事件。 */
public final class RunEventStreamListener implements LlmClient.StreamListener {
    private final RunEventSink sink;

    public RunEventStreamListener(RunEventSink sink) {
        this.sink = Objects.requireNonNullElse(sink, RunEventSink.NO_OP);
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            sink.emit(new RunEvent.ReasoningDelta(delta));
        }
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            sink.emit(new RunEvent.MessageDelta(delta));
        }
    }
}
