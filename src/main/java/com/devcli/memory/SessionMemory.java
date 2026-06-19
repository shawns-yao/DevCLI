package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 当前进程内的会话预摘要缓存。
 */
public class SessionMemory {
    private PreSummary preSummary;

    public synchronized void recordPreSummary(List<LlmClient.Message> coveredMessages, String summary) {
        if (coveredMessages == null || coveredMessages.isEmpty() || summary == null || summary.isBlank()) {
            return;
        }
        this.preSummary = new PreSummary(
                summary.trim(),
                coveredMessages.size(),
                TokenBudget.estimateMessagesTokens(coveredMessages),
                fingerprint(coveredMessages));
    }

    public synchronized Optional<PreSummary> findReusablePreSummary(List<LlmClient.Message> messages) {
        if (preSummary == null || messages == null || messages.isEmpty()) {
            return Optional.empty();
        }
        if (preSummary.messageCount != messages.size()) {
            return Optional.empty();
        }
        String currentFingerprint = fingerprint(messages);
        if (!preSummary.fingerprint.equals(currentFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(preSummary);
    }

    public synchronized Optional<PreSummary> currentPreSummary() {
        return Optional.ofNullable(preSummary);
    }

    public synchronized void clearPreSummary() {
        this.preSummary = null;
    }

    private static String fingerprint(List<LlmClient.Message> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (LlmClient.Message message : messages) {
                update(digest, message.role());
                update(digest, message.content());
                update(digest, message.reasoningContent());
                update(digest, message.toolCallId());
                if (message.toolCalls() != null) {
                    for (LlmClient.ToolCall toolCall : message.toolCalls()) {
                        update(digest, toolCall.id());
                        if (toolCall.function() != null) {
                            update(digest, toolCall.function().name());
                            update(digest, toolCall.function().arguments());
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    public record PreSummary(String summary, int messageCount, int tokenEstimate, String fingerprint) {
    }
}
