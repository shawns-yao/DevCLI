package com.devcli.tool;

/** read_file 分页结果的结构化元数据。 */
public record FileReadPage(
        String path,
        String mode,
        long offset,
        int startLine,
        int endLine,
        int returnedChars,
        String nextCursor,
        boolean hasMore,
        long fileBytes
) implements ToolSideChannel {
    public FileReadPage {
        path = path == null ? "" : path;
        mode = mode == null ? "" : mode;
        offset = Math.max(0L, offset);
        startLine = Math.max(0, startLine);
        endLine = Math.max(0, endLine);
        returnedChars = Math.max(0, returnedChars);
        nextCursor = nextCursor == null ? "" : nextCursor;
        fileBytes = Math.max(0L, fileBytes);
    }
}
