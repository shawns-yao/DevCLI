package com.devcli.event;

import com.devcli.llm.LlmClient;

import java.util.Arrays;
import java.util.List;

/** 接收强类型运行事件的最小出口。 */
@FunctionalInterface
public interface RunEventSink {
    RunEventSink NO_OP = event -> { };

    void emit(RunEvent event);

    /** 事实记录失败必须退出执行，不能作为可重试的工具失败继续调用模型。 */
    final class PersistenceFailure extends RuntimeException {
        public PersistenceFailure(RuntimeException cause) {
            super("会话事件写入失败: " + cause.getMessage(), cause);
        }
    }

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
