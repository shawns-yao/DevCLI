package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;

import java.util.Arrays;
import java.util.List;

/** 接收强类型运行事件的最小出口。 */
@FunctionalInterface
public interface RunEventSink {
    RunEventSink NO_OP = event -> { };

    void emit(RunEvent event);

    static RunEventSink composite(RunEventSink... sinks) {
        if (sinks == null || sinks.length == 0) return NO_OP;
        List<RunEventSink> active = Arrays.stream(sinks)
                .filter(sink -> sink != null && sink != NO_OP)
                .toList();
        if (active.isEmpty()) return NO_OP;
        if (active.size() == 1) return active.get(0);
        return event -> {
            for (RunEventSink sink : active) {
                sink.emit(event);
            }
        };
    }

    static RunEventSink fromStreamListener(LlmClient.StreamListener listener) {
        if (listener == null || listener == LlmClient.StreamListener.NO_OP) return NO_OP;
        return event -> {
            if (event instanceof RunEvent.ReasoningDelta reasoning) {
                listener.onReasoningDelta(reasoning.content());
            } else if (event instanceof RunEvent.MessageDelta message) {
                listener.onContentDelta(message.content());
            }
        };
    }
}
