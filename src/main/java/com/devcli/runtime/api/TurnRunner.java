package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEventSink;

import java.util.List;
import java.util.Objects;

/**
 * Runtime API turn 执行器：与 {@code TaskRunner} 的区别是带 threadId 和运行事件 sink，
 * 让执行侧按 thread 重放历史，并把模型流与工具事件写入统一协议。
 */
@FunctionalInterface
public interface TurnRunner {
    TurnResult run(String threadId, String input, RunEventSink eventSink) throws Exception;

    record TurnResult(String output, CheckpointCandidate checkpoint) {
        public TurnResult {
            output = output == null ? "" : output;
        }

        public static TurnResult completed(String output) {
            return new TurnResult(output, null);
        }
    }

    record CheckpointCandidate(
            List<LlmClient.Message> messages,
            String summary,
            CompactBoundaryMetadata metadata) {
        public CheckpointCandidate {
            messages = messages == null ? List.of() : List.copyOf(messages);
            summary = summary == null ? "" : summary;
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }
}
