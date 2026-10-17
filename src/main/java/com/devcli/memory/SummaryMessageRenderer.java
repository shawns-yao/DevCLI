package com.devcli.memory;

import com.devcli.llm.LlmClient;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import static com.devcli.memory.ConversationHistoryCompactor.*;

/** 摘要来源消息的有界文本、图片元数据与工具引用呈现。 */
final class SummaryMessageRenderer {

    String renderMessageForSummary(LlmClient.Message message) {
        if (message == null) return "";
        StringBuilder rendered = new StringBuilder()
                .append(message.role().toUpperCase(Locale.ROOT)).append(": ");
        String content = message.content() == null ? "" : message.content();
        boolean reducible = "tool".equalsIgnoreCase(message.role())
                || message.toolCallId() != null && !message.toolCallId().isBlank()
                || DeterministicContentReducer.classify(content) != DeterministicContentReducer.Kind.TEXT;
        if (content.length() > 12_000 && reducible) {
            String toolCallId = message.toolCallId() == null || message.toolCallId().isBlank()
                    ? "history-" + Integer.toHexString(System.identityHashCode(message))
                    : message.toolCallId();
            try {
                DeterministicContentReducer.Reduced reduced =
                        DeterministicContentReducer.reduceAndStore(content, 6_000, toolCallId);
                rendered.append("[content_kind=").append(reduced.kind())
                        .append(", original_chars=").append(reduced.originalChars())
                        .append(", reference=").append(reduced.reference()).append("]\n")
                        .append(reduced.preview());
                appendMetadata(rendered, reduced.metadata());
            } catch (IOException e) {
                rendered.append(content);
            }
        } else {
            rendered.append(content);
        }
        if (message.toolCalls() != null) {
            for (LlmClient.ToolCall call : message.toolCalls()) {
                if (call == null || call.function() == null) continue;
                rendered.append("\n  TOOL_CALL ").append(call.function().name())
                        .append(": ").append(call.function().arguments());
            }
        }
        appendImageMetadata(rendered, message);
        return "tool".equals(message.role())
                ? com.devcli.util.UntrustedContent.toolResult(null, message.toolCallId(), rendered.toString())
                : rendered.toString();
    }

    void appendImageMetadata(StringBuilder rendered, LlmClient.Message message) {
        if (!message.hasContentParts()) return;
        for (LlmClient.ContentPart part : message.contentParts()) {
            if (part == null || !part.isImage()) continue;
            if (part.imageReference() != null) {
                rendered.append('\n').append(part.imageReference().placeholder());
                continue;
            }
            rendered.append("\n[image_metadata");
            if (part.mimeType() != null && !part.mimeType().isBlank()) {
                rendered.append(" mime=").append(part.mimeType());
            }
            if (part.imageBase64() != null && !part.imageBase64().isBlank()) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(part.imageBase64());
                    rendered.append(" bytes=").append(bytes.length)
                            .append(" sha256=").append(sha256Bytes(bytes));
                } catch (IllegalArgumentException invalidBase64) {
                    rendered.append(" bytes=invalid");
                }
            } else if (part.imageUrl() != null && !part.imageUrl().isBlank()) {
                rendered.append(" source=").append(part.imageUrl());
            }
            rendered.append("]");
        }
    }

    static void appendMetadata(StringBuilder rendered, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return;
        rendered.append("\n[content_metadata ");
        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> rendered.append(entry.getKey()).append('=').append(entry.getValue()).append(' '));
        rendered.append(']');
    }

    static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
