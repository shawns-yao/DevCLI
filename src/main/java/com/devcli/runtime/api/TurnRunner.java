package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import com.devcli.runtime.event.RunEventSink;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
            CompactBoundaryMetadata metadata,
            List<MessageTreeNode> messageTree) {
        public CheckpointCandidate {
            messages = messages == null ? List.of() : List.copyOf(messages);
            summary = summary == null ? "" : summary;
            metadata = Objects.requireNonNull(metadata, "metadata");
            messageTree = messageTree == null ? linearTree(messages) : List.copyOf(messageTree);
        }

        public CheckpointCandidate(List<LlmClient.Message> messages,
                                   String summary,
                                   CompactBoundaryMetadata metadata) {
            this(messages, summary, metadata, null);
        }

        private static List<MessageTreeNode> linearTree(List<LlmClient.Message> messages) {
            if (messages == null || messages.isEmpty()) {
                return List.of();
            }
            List<MessageTreeNode> nodes = new java.util.ArrayList<>(messages.size());
            String parentId = "";
            for (int index = 0; index < messages.size(); index++) {
                LlmClient.Message message = messages.get(index);
                String role = message == null || message.role() == null ? "" : message.role();
                String content = message == null || message.content() == null ? "" : message.content();
                String id = "msg_" + UUID.nameUUIDFromBytes(
                        (index + "\u0000" + role + "\u0000" + content)
                                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
                nodes.add(new MessageTreeNode(id, parentId, role, index));
                parentId = id;
            }
            return List.copyOf(nodes);
        }
    }

    record MessageTreeNode(String id, String parentId, String role, int index) {
        public MessageTreeNode {
            id = id == null ? "" : id.trim();
            parentId = parentId == null ? "" : parentId.trim();
            role = role == null ? "" : role.trim();
            index = Math.max(0, index);
        }
    }
}
