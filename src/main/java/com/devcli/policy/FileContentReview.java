package com.devcli.policy;

/** A requested file range and its immutable source snapshot. Offsets are UTF-16, end-exclusive. */
public record FileContentReview(String path, String snapshot, int start, int end) {
    public FileContentReview {
        java.util.Objects.checkFromToIndex(start, end, snapshot.length());
    }

    public String content() {
        return snapshot.substring(start, end);
    }

    public SensitiveContentPolicy.Inspection inspect() {
        var inspection = SensitiveContentPolicy.inspectRange(snapshot, start, end);
        if (!inspection.sensitive()) return inspection;
        String safePath = SensitiveContentPolicy.inspect(path).sanitized();
        return inspection.withSource(safePath + " · 读取第 " + lineAt(start) + "–"
                + lineAt(Math.max(start, end - 1)) + " 行");
    }

    private int lineAt(int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (snapshot.charAt(index) == '\n') line++;
        }
        return line;
    }

    @Override
    public String toString() {
        return "FileContentReview[start=" + start + ", end=" + end + "]";
    }
}
