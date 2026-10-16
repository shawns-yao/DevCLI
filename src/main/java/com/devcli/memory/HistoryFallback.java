package com.devcli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.stream.Collectors;
import com.devcli.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import static com.devcli.memory.ConversationHistoryCompactor.*;
import static com.devcli.memory.HistoryWindow.*;

/** 保留既有失败截断行为，统一保存边界与恢复工作计划。 */
final class HistoryFallback {
    private final ConversationHistoryCompactor context;

    HistoryFallback(ConversationHistoryCompactor context) { this.context = context; }

    record Boundary(CompactionSourceCursor sourceCursor, String snapshotRef, String snapshotChecksum) { }

    private static final Logger log = LoggerFactory.getLogger(HistoryFallback.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 降级截断策略：压缩失败 3 次后的兜底方案。
     * 根据目标 token 动态计算需要删除的消息数量，保留 system 和最近上下文。
     *
     * @param history       对话历史
     * @param triggerTokens 触发压缩的 token 阈值
     * @return 是否成功截断
     */
    boolean fallbackTruncate(List<LlmClient.Message> history, int triggerTokens) {
        if (history == null || history.isEmpty()) return false;

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        if (history.size() <= systemEnd + 1) {
            // 只有 system 或 system + 1条消息，无法截断
            return false;
        }

        // 目标：降到 trigger * 0.7（留 30% 安全余量）
        int targetTokens = (int) (triggerTokens * 0.7);
        int currentTokens = TokenBudget.estimateMessagesTokens(history);

        if (currentTokens <= targetTokens) {
            // 已经在安全范围内，无需截断
            return false;
        }

        // Bug #4 修复：按 user 边界对齐删除，确保 tool_call/tool_result 不分离
        // 策略：从头开始找 user 消息边界，删除到该边界之前的所有消息
        int deleteUpTo = systemEnd;  // 默认从 system 后开始
        int accumulatedTokens = currentTokens;

        for (int i = systemEnd; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                // 计算删除到这个 user 之前的 token 减少量
                int tokensToRemove = 0;
                for (int j = systemEnd; j < i; j++) {
                    tokensToRemove += TokenBudget.estimateMessagesTokens(List.of(history.get(j)));
                }
                if (accumulatedTokens - tokensToRemove <= targetTokens) {
                    // 删除到这个 user 之前可以达到目标
                    deleteUpTo = i;
                    break;
                }
            }
        }

        // 至少保留 system + 3 条消息（降级标记 + assistant + 1条用户消息）
        int minKeep = 3;

        // 兜底：没有任何 user 边界能降到目标（例如尾部存在超大单条消息）时仍尽力删，
        // 删到保留最近 minKeep 条的最近 user 边界，避免熔断/冷却期裸奔撞窗口。
        if (deleteUpTo == systemEnd && history.size() > systemEnd + minKeep) {
            int keepFrom = history.size() - minKeep;
            while (keepFrom > systemEnd && !"user".equals(history.get(keepFrom).role())) {
                keepFrom--;
            }
            deleteUpTo = keepFrom;
        }

        int toRemove = deleteUpTo - systemEnd;
        int originalToRemove = toRemove;
        toRemove = Math.min(toRemove, history.size() - systemEnd - minKeep);

        // Bug #4 残留修复：如果 minKeep cap 改变了 toRemove，回退到最近的 user 边界
        if (toRemove < originalToRemove) {
            int cappedEnd = systemEnd + toRemove;
            for (int i = cappedEnd; i > systemEnd; i--) {
                if ("user".equals(history.get(i).role())) {
                    toRemove = i - systemEnd;
                    break;
                }
            }
        }

        if (toRemove <= 0) {
            // 无法删除足够的消息达到目标，说明最近几条消息就很大
            log.warn("fallbackTruncate: 无法删除足够消息降到目标 token，当前 {} 目标 {}",
                currentTokens, targetTokens);
            return false;
        }

        Boundary boundary = context.prepareFallbackBoundary(history.size());

        List<LlmClient.Message> preserved = new ArrayList<>();

        // 保留 system 消息
        for (int i = 0; i < systemEnd; i++) {
            preserved.add(history.get(i));
        }

        // 插入降级标记；仍使用结构化边界，让 checkpoint/resume 能识别降级结果。
        int keptMessages = history.size() - systemEnd - toRemove;
        CompactionSourceCursor sourceCursor = boundary.sourceCursor();
        String sourceHash = sourceCursor.eventEnd() > 0 && !"none".equalsIgnoreCase(sourceCursor.sourceHash())
                ? sourceCursor.sourceHash() : historyFingerprint(history);
        CompactBoundaryMetadata fallbackMetadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "fallback", currentTokens,
                0, history.size(), 0, keptMessages, 0, List.of(), "none", "none", true,
                0, 0, "fallback", sourceHash,
                sourceCursor.eventEnd() > 0 ? 0 : systemEnd,
                sourceCursor.eventEnd() > 0 ? 0 : deleteUpTo,
                "none", sourceCursor.eventStart(), sourceCursor.eventEnd(),
                boundary.snapshotRef(), boundary.snapshotChecksum());
        preserved.add(LlmClient.Message.internalUser(
            SUMMARY_MARKER + fallbackMetadata.renderBoundaryBlock() + "\n"
            + "[上下文压缩降级] 由于压缩连续失败，早期对话已截断。"
            + "当前保留最近 " + keptMessages + " 条消息。"
        ));
        preserved.add(LlmClient.Message.assistant(
            "了解，早期上下文已截断。我会基于当前保留的上下文继续工作。"
        ));

        // 保留尾部消息
        preserved.addAll(history.subList(systemEnd + toRemove, history.size()));

        String projection = preserved.stream().map(m -> m.content() == null ? "" : m.content())
                .collect(Collectors.joining("\n"));
        String protectedFacts = context.repairFallbackProjection(projection);
        if (!protectedFacts.isBlank()) {
            preserved.set(systemEnd, LlmClient.Message.internalUser(
                    preserved.get(systemEnd).content() + protectedFacts));
        }
        while (TokenBudget.estimateMessagesTokens(preserved) > targetTokens
                && removeEarliestFallbackTurn(preserved, systemEnd)) {
            // Snapshot metadata is cheap compared with retaining an over-budget window.
        }
        String plan = AgentWorkPlan.extract(context.buildPostCompactRestoreContext());
        if (!plan.isEmpty()) preserved.add(LlmClient.Message.internalUser(AgentWorkPlan.render(plan)));
        preserved.addAll(systemEnd + 2, UserMessageRetention.select(history.subList(systemEnd, systemEnd + toRemove),
                targetTokens - TokenBudget.estimateMessagesTokens(preserved)));
        int fallbackProjectionHashIndex = systemEnd;
        if (fallbackProjectionHashIndex < preserved.size()) {
            LlmClient.Message marker = preserved.get(fallbackProjectionHashIndex);
            String body = marker.content() == null ? "" : marker.content();
            String projectionHash = ContextProjectionBuilder.fingerprintOf(
                    preserved.stream().filter(message -> !"system".equals(message.role()))
                            .map(message -> message.withoutImageContent().withoutReasoningContent()).toList());
            CompactBoundaryMetadata updated = new CompactBoundaryMetadata(
                    fallbackMetadata.compactType(), fallbackMetadata.trigger(), fallbackMetadata.mode(),
                    fallbackMetadata.preTokens(), TokenBudget.estimateMessagesTokens(preserved),
                    fallbackMetadata.originalMessages(), preserved.size(), fallbackMetadata.retainedMessages(),
                    body.length(), fallbackMetadata.loadedSkills(), fallbackMetadata.ragEpoch(),
                    fallbackMetadata.mcpToolSnapshot(), fallbackMetadata.postCompactRestoreEnabled(),
                    fallbackMetadata.protectedConstraints(), fallbackMetadata.restoredConstraints(),
                    fallbackMetadata.semanticGuardStatus(), fallbackMetadata.sourceHash(),
                    fallbackMetadata.sourceStart(), fallbackMetadata.sourceEnd(), projectionHash,
                    fallbackMetadata.sourceEventStart(), fallbackMetadata.sourceEventEnd(),
                    fallbackMetadata.snapshotRef(), fallbackMetadata.snapshotChecksum());
            String summaryBody = CompactBoundaryMetadata.stripBoundaryBlock(
                    body.substring(SUMMARY_MARKER.length()).trim());
            preserved.set(fallbackProjectionHashIndex, LlmClient.Message.internalUser(
                    SUMMARY_MARKER + updated.renderBoundaryBlock() + "\n" + summaryBody));
        }
        int candidateTokens = TokenBudget.estimateMessagesTokens(preserved);
        if (candidateTokens >= currentTokens) {
            log.warn("fallbackTruncate: protected facts do not reduce the source window; retaining source");
            return false;
        }
        history.clear();
        history.addAll(preserved);

        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        log.warn("fallbackTruncate: removed {} early messages ({}->{} tokens), kept {} recent messages",
            toRemove, currentTokens, afterTokens, keptMessages);

        return true;
    }
}
