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
        String sha256
) implements ToolSideChannel {
    public ToolResultArtifact {
        classification = classification == null ? "" : classification;
        originalChars = Math.max(0L, originalChars);
        originalBytes = Math.max(0L, originalBytes);
        previewChars = Math.max(0, previewChars);
        artifactRef = artifactRef == null ? "" : artifactRef;
        nextCursor = nextCursor == null ? "" : nextCursor;
        sha256 = sha256 == null ? "" : sha256;
    }
}
