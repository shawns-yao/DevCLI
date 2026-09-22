package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministically rebuilds the model-visible context window from its projections.
 * It owns no state and never calls an LLM.
 */
public final class ContextProjectionBuilder {

    public List<LlmClient.Message> build(List<LlmClient.Message> systemMessages,
                                         String summary,
                                         String restoreContext,
                                         List<LlmClient.Message> recentMessages) {
        return project(systemMessages, summary, restoreContext, recentMessages).messages();
    }

    /**
     * 构建模型窗口并返回可审计的投影信息。
     * <p>消息列表是新建的不可变快照；调用方修改输入列表不会改变已经构建的投影。
     * fingerprint 只描述最终模型可见消息，不承担任务状态或摘要事实源职责。
     */
    public Projection project(List<LlmClient.Message> systemMessages,
                              String summary,
                              String restoreContext,
                              List<LlmClient.Message> recentMessages) {
        List<LlmClient.Message> projection = new ArrayList<>();
        List<LlmClient.Message> stableSystem = copyMessages(systemMessages);
        List<LlmClient.Message> tail = copyMessages(recentMessages);
        projection.addAll(stableSystem);
        projection.add(LlmClient.Message.internalUser(
                ConversationHistoryCompactor.SUMMARY_MARKER
                        + (summary == null ? "" : summary.trim())));
        projection.add(LlmClient.Message.assistant("OK."));
        if (restoreContext != null && !restoreContext.isBlank()) {
            projection.add(LlmClient.Message.internalUser(
                    ConversationHistoryCompactor.POST_COMPACT_RESTORE_MARKER
                            + restoreContext.trim()));
            projection.add(LlmClient.Message.assistant("OK."));
        }
        if (recentMessages != null) {
            projection.addAll(tail);
        }
        List<LlmClient.Message> snapshot = List.copyOf(projection);
        return new Projection(snapshot, stableSystem.size(), tail.size(),
                sha256(summary == null ? "" : summary.trim()),
                sha256(restoreContext == null ? "" : restoreContext.trim()),
                fingerprintOf(snapshot));
    }

    private static List<LlmClient.Message> copyMessages(List<LlmClient.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<LlmClient.Message> copy = new ArrayList<>(messages.size());
        for (LlmClient.Message message : messages) {
            if (message != null) {
                copy.add(message);
            }
        }
        return List.copyOf(copy);
    }

    /** 对最终窗口做稳定指纹；压缩边界的诊断元数据不影响投影内容指纹。 */
    public static String fingerprintOf(List<LlmClient.Message> messages) {
        StringBuilder value = new StringBuilder();
        if (messages == null) {
            return sha256("");
        }
        for (LlmClient.Message message : messages) {
            if (message == null) {
                continue;
            }
            value.append(message.role()).append('\n')
                    .append(message.source()).append('\n')
                    .append(canonicalContent(message)).append('\n')
                    .append(message.reasoningContent()).append('\n')
                    .append(message.toolCallId()).append('\n')
                    .append(message.imagePartCount()).append('\n')
                    .append(message.toolCalls()).append('\n');
        }
        return sha256(value.toString());
    }

    private static String canonicalContent(LlmClient.Message message) {
        String content = message.content();
        if (content == null || !"user".equals(message.role())) {
            return content;
        }
        if (content.startsWith(ConversationHistoryCompactor.SUMMARY_MARKER)) {
            String body = content.substring(ConversationHistoryCompactor.SUMMARY_MARKER.length()).trim();
            return ConversationHistoryCompactor.SUMMARY_MARKER
                    + CompactBoundaryMetadata.stripBoundaryBlock(body);
        }
        return content;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record Projection(List<LlmClient.Message> messages,
                             int systemMessageCount,
                             int recentMessageCount,
                             String summaryHash,
                             String restoreContextHash,
                             String fingerprint) {
        public Projection {
            messages = messages == null ? List.of() : List.copyOf(messages);
            systemMessageCount = Math.max(0, systemMessageCount);
            recentMessageCount = Math.max(0, recentMessageCount);
            summaryHash = summaryHash == null ? "" : summaryHash;
            restoreContextHash = restoreContextHash == null ? "" : restoreContextHash;
            fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }
}
