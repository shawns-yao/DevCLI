package com.devcli.tool;

/**
 * 被折叠工具结果的可恢复元数据。原始内容保存在受控运行时目录，模型展示文本只保留预览。
 */
public record ToolResultArtifact(
        String classification,
        long originalChars,
        long originalBytes,
        int previewChars,
        String artifactRef,
        String nextCursor,
        String sha256,
        String toolCallId,
        String status,
        String errorCode,
        int exitCode,
        long elapsedMillis
) implements ToolSideChannel {
    public ToolResultArtifact {
        classification = classification == null ? "" : classification;
        originalChars = Math.max(0L, originalChars);
        originalBytes = Math.max(0L, originalBytes);
        previewChars = Math.max(0, previewChars);
        artifactRef = artifactRef == null ? "" : artifactRef;
        nextCursor = nextCursor == null ? "" : nextCursor;
        sha256 = sha256 == null ? "" : sha256;
        toolCallId = toolCallId == null ? "" : toolCallId;
        status = status == null ? "" : status;
        errorCode = errorCode == null ? "" : errorCode;
        exitCode = Math.max(Integer.MIN_VALUE, exitCode);
        elapsedMillis = Math.max(0L, elapsedMillis);
    }

    public ToolResultArtifact(String classification, long originalChars, long originalBytes,
                              int previewChars, String artifactRef, String nextCursor,
                              String sha256) {
        this(classification, originalChars, originalBytes, previewChars, artifactRef,
                nextCursor, sha256, "", "", "", Integer.MIN_VALUE, 0L);
    }
}
