package com.devcli.runtime.api;

import com.devcli.agent.AgentTurnInbox;
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

    default QueueResult enqueueSteering(String threadId, String input) {
        return QueueResult.unsupported(AgentTurnInbox.Channel.STEERING);
    }

    default QueueResult enqueueFollowUp(String threadId, String input) {
        return QueueResult.unsupported(AgentTurnInbox.Channel.FOLLOW_UP);
    }

    default QueueResult clearQueue(String threadId) {
        return QueueResult.unsupported(AgentTurnInbox.Channel.FOLLOW_UP);
    }

    default boolean cancelCurrent(String threadId) {
        return false;
    }

    default void resetSession(String threadId) {
    }

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

    record QueueResult(boolean accepted, AgentTurnInbox.Channel channel,
                       String reason, int steeringPending, int followUpPending) {
        public QueueResult {
            channel = channel == null ? AgentTurnInbox.Channel.FOLLOW_UP : channel;
            reason = reason == null ? "" : reason;
            steeringPending = Math.max(0, steeringPending);
            followUpPending = Math.max(0, followUpPending);
        }

        public static QueueResult unsupported(AgentTurnInbox.Channel channel) {
            return new QueueResult(false, channel, "当前 Runtime runner 不支持会话队列", 0, 0);
        }

        public static QueueResult from(AgentTurnInbox.EnqueueResult result,
                                       AgentTurnInbox.Channel channel) {
            AgentTurnInbox.Snapshot snapshot = result.snapshot();
            return new QueueResult(result.accepted(), channel, result.reason(),
                    snapshot.steering().size(), snapshot.followUp().size());
        }
    }
}
