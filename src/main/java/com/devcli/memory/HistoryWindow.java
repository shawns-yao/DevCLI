package com.devcli.memory;
import static com.devcli.memory.CompactionPaths.sha256;

import com.devcli.llm.LlmClient;
import java.util.HashSet;
import java.util.List;
import static com.devcli.memory.ConversationHistoryCompactor.*;

/** 历史窗口的安全消息边界、摘要定位与指纹计算。 */
final class HistoryWindow {
    private HistoryWindow() { }

    static int splitBeforeRecentTurns(List<LlmClient.Message> history, int systemEnd, int keep) {
        if (keep == 0) return history.size();
        int remaining = keep;
        for (int i = history.size() - 1; i >= systemEnd; i--) {
            LlmClient.Message message = history.get(i);
            if ("user".equals(message.role()) && message.source() == LlmClient.MessageSource.USER
                    && --remaining == 0) return i;
        }
        return systemEnd;
    }

    static boolean hasCompleteToolBatches(List<LlmClient.Message> history) {
        var pending = new HashSet<String>();
        for (LlmClient.Message message : history) {
            if ("tool".equals(message.role())) {
                if (!pending.remove(message.toolCallId())) return false;
            } else {
                if (!pending.isEmpty()) return false;
                if (message.toolCalls() != null) {
                    for (LlmClient.ToolCall call : message.toolCalls()) {
                        if (call == null || call.id() == null || !pending.add(call.id())) return false;
                    }
                }
            }
        }
        return pending.isEmpty();
    }

    static int findSplitIdxByTokenBudget(List<LlmClient.Message> history,
                                                  int systemEnd, int retainTokens) {
        int accumulated = 0;
        for (int i = history.size() - 1; i >= systemEnd; i--) {
            LlmClient.Message m = history.get(i);
            accumulated += TokenBudget.estimateMessagesTokens(List.of(m));
            if ("user".equals(m.role()) && accumulated >= retainTokens) {
                // 当前 user 进保留区；如果它就是 systemEnd 之后第一个 user，splitIdx == systemEnd
                // 说明整段除 system 外都要保留，调用方会 skip。否则正常压缩。
                return i;
            }
        }
        // 累计不够 retain，且没找到任何 user：返回 systemEnd 让调用方跳过
        return systemEnd;
    }

    /**
     * A single user turn can contain an unbounded sequence of assistant/tool batches.
     * When there is no later user boundary, compact only after a complete tool result
     * batch so the projection can replace the removed request with the summary user turn.
     */
    static int findSplitIdxByCompletedToolBatch(List<LlmClient.Message> history,
                                                        int systemEnd, int retainTokens) {
        int lastCompletedBatch = systemEnd;
        for (int i = systemEnd + 1; i < history.size(); i++) {
            LlmClient.Message previous = history.get(i - 1);
            if (!"tool".equals(previous.role()) || previous.toolCallId() == null) {
                continue;
            }
            if (i < history.size() && "tool".equals(history.get(i).role())) {
                continue;
            }
            lastCompletedBatch = i;
            if (estimateRangeTokens(history, i, history.size()) <= retainTokens) {
                return i;
            }
        }
        return lastCompletedBatch;
    }

    /**
     * user 边界对齐会带来尾部超预算场景。只移动安全的 user 边界，绝不通过规则截断
     * user / assistant 或最近工具结果；仍然超限时交给后续模型语义压缩和窗口保护处理。
     */
    static int fitRecentTailWithinTokenBudget(List<LlmClient.Message> history,
                                               int systemEnd,
                                               int splitIdx,
                                               int retainTokens) {
        if (splitIdx <= systemEnd) {
            return splitIdx;
        }
        while (estimateRangeTokens(history, splitIdx, history.size()) > retainTokens) {
            int nextUser = findNextUserBoundary(history, splitIdx + 1);
            if (nextUser < 0) {
                break;
            }
            splitIdx = nextUser;
        }

        return splitIdx;
    }

    static int findNextUserBoundary(List<LlmClient.Message> history, int start) {
        for (int i = Math.max(0, start); i < history.size(); i++) {
            if ("user".equals(history.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    static int estimateRangeTokens(List<LlmClient.Message> history, int start, int end) {
        if (start >= end) {
            return 0;
        }
        return TokenBudget.estimateMessagesTokens(history.subList(start, end));
    }

    /**
     * 检测 history 里是否已有上一轮压缩留下的摘要消息（"[已压缩的历史对话摘要]" 起头的 user）。
     * 返回该摘要消息的位置和文本，没有返回 null。
     * <p>识别规则：systemEnd 之后第一条 role=user 且 content 以 SUMMARY_MARKER 起头。
     * 紧随其后通常是 assistant("好的，已了解...")，但不强制。
     */
    static PreviousSummary detectPreviousSummary(List<LlmClient.Message> history, int systemEnd) {
        if (history.size() <= systemEnd) return null;
        LlmClient.Message first = history.get(systemEnd);
        if (!"user".equals(first.role())) return null;
        String content = first.content();
        if (content == null) return null;
        // 新结构化标记优先;旧中文标记仅为识别历史会话/旧检查点回放的存量摘要。
        String matchedMarker = content.startsWith(SUMMARY_MARKER) ? SUMMARY_MARKER
                : content.startsWith(LEGACY_SUMMARY_MARKER) ? LEGACY_SUMMARY_MARKER
                : null;
        if (matchedMarker == null) return null;
        String summaryText = CompactBoundaryMetadata.stripBoundaryBlock(
                content.substring(matchedMarker.length()).trim());
        // endIdx 跳过摘要 user + 紧随的 assistant 确认（如果有）
        int endIdx = systemEnd + 1;
        if (endIdx < history.size() && "assistant".equals(history.get(endIdx).role())) {
            endIdx++;
        }
        return new PreviousSummary(systemEnd, endIdx, summaryText);
    }

    record PreviousSummary(int startIdx, int endIdx, String summaryText) {
    }

    static boolean removeEarliestFallbackTurn(List<LlmClient.Message> messages,
                                                       int systemEnd) {
        int first = systemEnd + 2; // marker + assistant acknowledgement
        if (first >= messages.size()) return false;
        int nextUser = first + 1;
        while (nextUser < messages.size()
                && !"user".equals(messages.get(nextUser).role())) {
            nextUser++;
        }
        if (nextUser >= messages.size()) return false;
        messages.subList(first, nextUser).clear();
        return true;
    }

    static String historyFingerprint(List<LlmClient.Message> history) {
        StringBuilder value = new StringBuilder();
        for (LlmClient.Message message : history) {
            value.append(message.role()).append('\n')
                    .append(message.source()).append('\n')
                    .append(message.content()).append('\n')
                    .append(message.reasoningContent()).append('\n')
                    .append(message.toolCallId()).append('\n')
                    .append(message.imagePartCount()).append('\n')
                    .append(message.toolCalls()).append('\n');
        }
        return sha256(value.toString());
    }

    /** 压缩边界只记录被摘要覆盖的原文范围，便于持久检查点和诊断校验。 */
    static String messageRangeFingerprint(List<LlmClient.Message> history,
                                                  int start,
                                                  int end) {
        if (history == null || history.isEmpty()) {
            return sha256("");
        }
        int safeStart = Math.max(0, Math.min(start, history.size()));
        int safeEnd = Math.max(safeStart, Math.min(end, history.size()));
        return historyFingerprint(history.subList(safeStart, safeEnd));
    }
}
