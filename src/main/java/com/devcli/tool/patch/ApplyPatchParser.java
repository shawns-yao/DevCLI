package com.devcli.tool.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code *** Begin Patch} 文本解析为结构化 hunks。
 *
 * <p>实现官方 apply_patch 语法（Lark grammar）：</p>
 * <pre>
 * start: begin_patch environment_id? hunk+ end_patch
 * begin_patch: "*** Begin Patch" LF
 * end_patch:   "*** End Patch" LF?
 * hunk: add_hunk | delete_hunk | update_hunk
 * add_hunk:    "*** Add File: " filename LF add_line+
 * delete_hunk: "*** Delete File: " filename LF
 * update_hunk: "*** Update File: " filename LF change_move? change?
 * change_move: "*** Move to: " filename LF
 * change: (change_context | change_line)+ eof_line?
 * change_context: ("@@" | "@@ " /(.+)/) LF
 * change_line:    ("+" | "-" | " ") /(.+)/ LF
 * eof_line:       "*** End of File" LF
 * </pre>
 *
 * <p>与官方一致：解析阶段不接触文件系统。比官方严格一点：无法识别的行直接报错并给出行号，
 * 而不是猜一个宽松解释——猜错会静默改坏文件，报错只会让模型重发一次。</p>
 */
final class ApplyPatchParser {

    static final String BEGIN_MARKER = "*** Begin Patch";
    static final String END_MARKER = "*** End Patch";
    private static final String ADD_FILE_MARKER = "*** Add File:";
    private static final String DELETE_FILE_MARKER = "*** Delete File:";
    private static final String UPDATE_FILE_MARKER = "*** Update File:";
    private static final String MOVE_TO_MARKER = "*** Move to:";
    private static final String ENVIRONMENT_ID_MARKER = "*** Environment ID:";
    private static final String END_OF_FILE_MARKER = "*** End of File";

    private ApplyPatchParser() {
    }

    static List<PatchHunk> parse(String patch) {
        List<String> lines = normalize(patch);
        if (lines.isEmpty()) {
            throw new PatchException("补丁为空");
        }
        int end = lines.size() - 1;
        if (!lines.get(0).strip().equals(BEGIN_MARKER)) {
            throw new PatchException("补丁第一行必须是 '" + BEGIN_MARKER + "'，实际为: " + lines.get(0));
        }
        if (!lines.get(end).strip().equals(END_MARKER)) {
            throw new PatchException("补丁最后一行必须是 '" + END_MARKER + "'，实际为: " + lines.get(end));
        }

        List<PatchHunk> hunks = new ArrayList<>();
        int index = 1;
        while (index < end) {
            String raw = lines.get(index);
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                index++;
                continue;
            }
            if (trimmed.startsWith(ENVIRONMENT_ID_MARKER)) {
                throw new PatchException("不支持 '" + ENVIRONMENT_ID_MARKER
                        + "'（本工具只在本地项目内应用补丁），请移除该行后重试");
            }
            if (!isHunkStart(trimmed)) {
                throw new PatchException("第 " + (index + 1) + " 行无法识别: " + raw
                        + "（应为 '*** Add File:'、'*** Delete File:' 或 '*** Update File:'）");
            }
            index = parseHunk(lines, index, end, hunks);
        }
        return hunks;
    }

    private static int parseHunk(List<String> lines, int index, int end, List<PatchHunk> hunks) {
        String trimmed = lines.get(index).strip();
        if (trimmed.startsWith(ADD_FILE_MARKER)) {
            return parseAddFile(lines, index, end, hunks);
        }
        if (trimmed.startsWith(DELETE_FILE_MARKER)) {
            hunks.add(new PatchHunk.DeleteFile(requirePath(trimmed, DELETE_FILE_MARKER, index)));
            return index + 1;
        }
        return parseUpdateFile(lines, index, end, hunks);
    }

    private static int parseAddFile(List<String> lines, int index, int end, List<PatchHunk> hunks) {
        String path = requirePath(lines.get(index).strip(), ADD_FILE_MARKER, index);
        index++;
        List<String> contents = new ArrayList<>();
        while (index < end && !isHunkStart(lines.get(index).strip())) {
            String raw = lines.get(index);
            if (raw.strip().isEmpty()) {
                index++;
                continue;
            }
            if (!raw.startsWith("+")) {
                throw new PatchException("第 " + (index + 1) + " 行无效: " + raw
                        + "（Add File 的内容行必须以 '+' 开头，空行请写单独的 '+'）");
            }
            contents.add(raw.substring(1));
            index++;
        }
        if (contents.isEmpty()) {
            throw new PatchException("Add File 未包含任何内容行: " + path);
        }
        hunks.add(new PatchHunk.AddFile(path, contents));
        return index;
    }

    private static int parseUpdateFile(List<String> lines, int index, int end, List<PatchHunk> hunks) {
        String path = requirePath(lines.get(index).strip(), UPDATE_FILE_MARKER, index);
        index++;

        String movePath = null;
        if (index < end && lines.get(index).strip().startsWith(MOVE_TO_MARKER)) {
            movePath = requirePath(lines.get(index).strip(), MOVE_TO_MARKER, index);
            index++;
        }

        List<PatchHunk.UpdateFileChunk> chunks = new ArrayList<>();
        ChunkBuilder current = null;
        while (index < end && !isHunkStart(lines.get(index).strip())) {
            String raw = lines.get(index);
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                index++;
                continue;
            }
            if (trimmed.equals("@@") || trimmed.startsWith("@@ ")) {
                if (current != null) {
                    chunks.add(current.build());
                }
                current = new ChunkBuilder(trimmed.length() > 2 ? trimmed.substring(3) : null);
                index++;
                continue;
            }
            if (trimmed.equals(END_OF_FILE_MARKER)) {
                if (current == null) {
                    throw new PatchException("第 " + (index + 1) + " 行 '" + END_OF_FILE_MARKER
                            + "' 之前没有任何修改行");
                }
                current.endOfFile = true;
                index++;
                continue;
            }
            if (current == null) {
                // 官方语法允许省略首个 '@@'：直接以 change_line 开头即隐式首块。
                current = new ChunkBuilder(null);
            }
            if (raw.startsWith("+")) {
                current.newLines.add(raw.substring(1));
            } else if (raw.startsWith("-")) {
                current.oldLines.add(raw.substring(1));
            } else if (raw.startsWith(" ")) {
                String context = raw.substring(1);
                current.oldLines.add(context);
                current.newLines.add(context);
            } else {
                throw new PatchException("第 " + (index + 1) + " 行无效: " + raw
                        + "（修改行必须以 '+'、'-'、' ' 开头，或使用 '@@' 分隔修改块）");
            }
            index++;
        }
        if (current != null) {
            chunks.add(current.build());
        }
        if (chunks.isEmpty()) {
            throw new PatchException("Update File 未包含任何修改块: " + path);
        }
        hunks.add(new PatchHunk.UpdateFile(path, movePath, chunks));
        return index;
    }

    private static boolean isHunkStart(String trimmed) {
        return trimmed.startsWith(ADD_FILE_MARKER)
                || trimmed.startsWith(DELETE_FILE_MARKER)
                || trimmed.startsWith(UPDATE_FILE_MARKER);
    }

    private static String requirePath(String trimmed, String marker, int index) {
        String path = trimmed.substring(marker.length()).strip();
        if (path.isEmpty()) {
            throw new PatchException("第 " + (index + 1) + " 行缺少路径: " + trimmed);
        }
        return path;
    }

    /**
     * 统一行尾、去掉 BOM 与首尾空行，并兼容 heredoc 包裹。
     *
     * <p>heredoc 兼容来自官方实现：部分模型会把补丁写成
     * {@code <<'EOF' ... EOF} 的 shell 形式，而工具调用参数并不是 shell，需要剥掉包裹层。</p>
     */
    private static List<String> normalize(String patch) {
        String text = patch == null ? "" : patch;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        lines = stripBlankEdges(lines);
        if (lines.size() >= 4 && isHeredocStart(lines.get(0))
                && lines.get(lines.size() - 1).stripTrailing().endsWith("EOF")) {
            List<String> inner = stripBlankEdges(lines.subList(1, lines.size() - 1));
            if (!inner.isEmpty() && inner.get(0).strip().equals(BEGIN_MARKER)) {
                return inner;
            }
        }
        return lines;
    }

    private static boolean isHeredocStart(String line) {
        String trimmed = line.strip();
        return trimmed.equals("<<EOF") || trimmed.equals("<<'EOF'") || trimmed.equals("<<\"EOF\"");
    }

    private static List<String> stripBlankEdges(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isBlank()) {
            to--;
        }
        return new ArrayList<>(lines.subList(from, to));
    }

    private static final class ChunkBuilder {
        private final String changeContext;
        private final List<String> oldLines = new ArrayList<>();
        private final List<String> newLines = new ArrayList<>();
        private boolean endOfFile;

        private ChunkBuilder(String changeContext) {
            this.changeContext = changeContext;
        }

        private PatchHunk.UpdateFileChunk build() {
            return new PatchHunk.UpdateFileChunk(changeContext, oldLines, newLines, endOfFile);
        }
    }
}
