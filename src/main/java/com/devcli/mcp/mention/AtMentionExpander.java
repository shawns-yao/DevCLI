package com.devcli.mcp.mention;

import com.devcli.context.ContextInputSnapshotStore;
import com.devcli.mcp.McpServerManager;
import com.devcli.memory.MemoryEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AtMentionExpander {
    private static final int MAX_INLINE_RESOURCE_CHARS = 200_000;
    private static final int MAX_REFERENCE_PREVIEW_CHARS = 1_600;
    private static final int REFERENCE_METADATA_TOKENS = 180;

    private final McpServerManager serverManager;
    private final ContextInputSnapshotStore snapshotStore;

    public AtMentionExpander(McpServerManager serverManager) {
        this(serverManager, Path.of("."));
    }

    public AtMentionExpander(McpServerManager serverManager, Path projectRoot) {
        this.serverManager = serverManager;
        this.snapshotStore = new ContextInputSnapshotStore(projectRoot);
    }

    public String expand(String input) {
        return expand(input, Integer.MAX_VALUE);
    }

    public String expand(String input, int remainingTokens) {
        List<AtMentionParser.MentionToken> tokens = AtMentionParser.parse(input);
        if (tokens.isEmpty()) {
            return input;
        }

        StringBuilder expanded = new StringBuilder(input);
        int remaining = Math.max(0, remainingTokens);
        List<String> replacements = new ArrayList<>(tokens.size());
        for (AtMentionParser.MentionToken token : tokens) {
            String replacement = expandToken(token, remaining);
            remaining = Math.max(0, remaining - MemoryEntry.estimateTokens(replacement));
            replacements.add(replacement);
        }
        for (int i = tokens.size() - 1; i >= 0; i--) {
            AtMentionParser.MentionToken token = tokens.get(i);
            expanded.replace(token.start(), token.end(), replacements.get(i));
        }
        return expanded.toString();
    }

    private String expandToken(AtMentionParser.MentionToken token, int remainingTokens) {
        try {
            McpServerManager.ResourceReadResult result =
                    serverManager.readResourceForMention(token.serverName(), token.uri());
            String content = result.content();
            String mimeType = result.mimeType() == null || result.mimeType().isBlank()
                    ? "text/plain"
                    : result.mimeType();
            String inline = "<resource server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) +
                    "\" mimeType=\"" + escapeXml(mimeType) + "\">\n" +
                    content + "\n</resource>";
            if (content.length() <= MAX_INLINE_RESOURCE_CHARS
                    && MemoryEntry.estimateTokens(inline) <= Math.max(0, remainingTokens)) {
                return inline;
            }
            ContextInputSnapshotStore.Snapshot snapshot = snapshotStore.store(
                    resourceFileName(token), content.getBytes(StandardCharsets.UTF_8));
            int previewBudget = Math.max(0, remainingTokens - REFERENCE_METADATA_TOKENS);
            String preview = preview(content,
                    Math.min(MAX_REFERENCE_PREVIEW_CHARS, previewBudget * 4));
            StringBuilder reference = new StringBuilder("<file_reference source_type=\"mcp_resource\"")
                    .append(" server=\"").append(escapeXml(token.serverName()))
                    .append("\" uri=\"").append(escapeXml(token.uri()))
                    .append("\" mime_type=\"").append(escapeXml(mimeType))
                    .append("\" stored_path=\"").append(escapeXml(snapshot.storedPath()))
                    .append("\" sha256=\"").append(snapshot.sha256())
                    .append("\" size_bytes=\"").append(snapshot.sizeBytes())
                    .append("\" evidence_required=\"true\">\n")
                    .append("<summary>MCP Resource 因上下文预算不足，仅注入本地快照引用</summary>\n");
            if (!preview.isBlank()) {
                reference.append("<preview>\n").append(preview).append("\n</preview>\n");
            }
            return reference.append("</file_reference>").toString();
        } catch (Exception e) {
            return token.raw() + "\n<resource_error server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) + "\">" +
                    escapeXml(e.getMessage()) + "</resource_error>";
        }
    }

    private static String resourceFileName(AtMentionParser.MentionToken token) {
        String uri = token.uri() == null ? "resource" : token.uri();
        int slash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
        String leaf = slash >= 0 ? uri.substring(slash + 1) : uri;
        return leaf.isBlank() ? token.serverName() + "-resource.txt" : leaf;
    }

    private static String preview(String content, int maxChars) {
        if (content == null || content.isBlank() || maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        int head = maxChars / 2;
        int tail = maxChars - head;
        return content.substring(0, head)
                + "\n[中间内容已省略，可读取 stored_path 获取完整证据]\n"
                + content.substring(content.length() - tail);
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
