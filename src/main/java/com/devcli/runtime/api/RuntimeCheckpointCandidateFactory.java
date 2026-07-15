package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 从已完成 turn 的 Agent 历史中提取可持久化压缩检查点。
 */
public final class RuntimeCheckpointCandidateFactory {
    private RuntimeCheckpointCandidateFactory() {
    }

    public static Optional<TurnRunner.CheckpointCandidate> fromHistory(
            List<LlmClient.Message> history,
            boolean compacted) {
        if (!compacted || history == null || history.isEmpty()) return Optional.empty();
        for (int index = history.size() - 1; index >= 0; index--) {
            LlmClient.Message message = history.get(index);
            if (message == null || !"user".equals(message.role())) continue;
            Optional<CompactBoundaryMetadata> metadata =
                    CompactBoundaryMetadata.parseFromSummaryMessage(message.content());
            if (metadata.isEmpty()) continue;

            List<LlmClient.Message> checkpointMessages = new ArrayList<>();
            for (int i = index; i < history.size(); i++) {
                LlmClient.Message candidate = history.get(i);
                if (candidate == null || "system".equals(candidate.role())) continue;
                checkpointMessages.add(candidate.withoutImageContent().withoutReasoningContent());
            }
            if (checkpointMessages.isEmpty()) return Optional.empty();
            return Optional.of(new TurnRunner.CheckpointCandidate(
                    checkpointMessages,
                    message.content(),
                    metadata.get()));
        }
        return Optional.empty();
    }
}
