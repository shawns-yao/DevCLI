package com.devcli.tool.provider;

import com.devcli.policy.PolicyException;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.FileReadPage;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolResultArtifactStore;
import com.devcli.workspace.WriteGateResult;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class FileToolProvider implements ToolProvider {
    static final int DEFAULT_MAX_RETURN_TOKENS = 4_000;
    static final int MAX_RETURN_CHARS = DEFAULT_MAX_RETURN_TOKENS;
    static final int MAX_LINE_WINDOW = 400;
    private static final int MAX_DIRECTORY_ENTRIES = 500;
    private static final int MAX_DIRECTORY_RESULT_CHARS = 4_000;
    private static final String DIRECTORY_TRUNCATION_HEADER =
            "目录内容已截断（最多 " + MAX_DIRECTORY_ENTRIES + " 项 / "
                    + MAX_DIRECTORY_RESULT_CHARS + " 字符）:\n";

    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "read_file",
                "分页读取项目内文件。行范围 start_line/end_line（1-based 闭区间）与字符范围 offset/limit（字符偏移与字符数）二选一，优先使用 start_line/end_line，切勿同时提供两组；默认最多返回约 4000 Token，next_cursor 可继续读取",
                context.createToolParameters(
                        new ToolParameter("path", "string", "文件路径", true),
                        new ToolParameter("start_line", "integer", "起始行，1-based", false),
                        new ToolParameter("end_line", "integer", "结束行，1-based 且包含该行", false),
                        new ToolParameter("offset", "integer", "字符偏移，0-based", false),
                        new ToolParameter("limit", "integer", "最多返回字符数，上限 4000", false),
                        new ToolParameter("cursor", "string", "上次返回的 next_cursor", false)
                ),
                args -> readFile(context, args)
        ));

        context.registerTool(ToolRegistry.Tool.structured(
                "read_tool_result",
                "通过 result_ref 分页读取被截断或折叠的完整工具结果",
                context.createToolParameters(
                        new ToolParameter("result_ref", "string", "工具结果引用", true),
                        new ToolParameter("offset", "integer", "字符偏移，0-based", false),
                        new ToolParameter("limit", "integer", "最多返回字符数", false)
                ),
                FileToolProvider::readToolResult
        ));

        context.registerTool(ToolRegistry.Tool.contextualStructured(
                "write_file",
                "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                context.createToolParameters(
                        new ToolParameter("path", "string", "文件路径", true),
                        new ToolParameter("content", "string", "文件内容", true)
                ),
                (args, executionContext) -> {
                    executionContext.throwIfCancelled();
                    String path = args.get("path");
                    String content = args.get("content") == null ? "" : args.get("content");
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > context.maxWriteFileBytes()) {
                        throw new PolicyException("写入内容 " + contentBytes + " 字节超过 "
                                + (context.maxWriteFileBytes() / 1024 / 1024) + "MB 上限");
                    }
                    Path safe = context.resolveSafePath(path);
                    String activeStep = context.currentResourceLeaseStep();
                    if (activeStep != null && !activeStep.isBlank()) {
                        context.acquireWriteLease(activeStep, safe);
                        if (!context.isWriteLeaseValid(activeStep, safe)) {
                            throw new PolicyException("写入冲突: 租约已失效，文件 " + path
                                    + " 可能正在被其他任务写入");
                        }
                    }
                    String before = null;
                    try {
                        if (Files.exists(safe) && Files.isRegularFile(safe)) {
                            before = Files.readString(safe);
                        }
                    } catch (Exception ignored) {
                        // 二进制 / 大文件 / 编码错读不出来时，前文当 null 处理（diff 退化为长度提示）
                    }
                    if (java.util.Objects.equals(before, content)) {
                        return ToolOutput.success("文件内容未变化，无需写入: " + path);
                    }
                    // 过期写入屏障：本步骤读过该文件、期间内容变了，说明要基于旧版本写回，
                    // 直接写会静默覆盖对方改动。抛策略异常让模型看到可执行的恢复动作（重读后重写）。
                    WriteGateResult writeGate = context.validateWrite(activeStep, safe, before);
                    if (!writeGate.isAllowed()) {
                        return ToolOutput.rejected(ToolErrorCode.STALE_CONTEXT,
                                writeGate.reason(), true);
                    }
                    try {
                        executionContext.throwIfCancelled();
                        Path parent = safe.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(safe, content);
                        context.recordFileWrite(path, safe, before, content, activeStep);
                        executionContext.throwIfCancelled();
                        return ToolOutput.success("文件已写入: " + path);
                    } catch (Exception e) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "写入文件失败: " + e.getMessage(), false);
                    }
                },
                -1
        ));

        context.registerTool(ToolRegistry.Tool.contextualStructured(
                "edit_file",
                "对已存在文件做精确字符串替换，适合小范围修改而无需重写整个文件。先用 read_file 核对原文；old_string 必须与文件逐字一致（含缩进/空白/换行）且在文件中唯一出现，new_string 为替换内容（可为空串表示删除）",
                context.createToolParameters(
                        new ToolParameter("path", "string", "文件路径", true),
                        new ToolParameter("old_string", "string", "被替换的原文，必须在文件中唯一匹配", true),
                        ToolParameter.requiredStringAllowingEmpty("new_string", "替换后的文本")
                ),
                (args, executionContext) -> {
                    executionContext.throwIfCancelled();
                    String path = args.get("path");
                    String oldString = args.get("old_string");
                    String newString = args.get("new_string") == null ? "" : args.get("new_string");
                    if (oldString == null || oldString.isEmpty()) {
                        return invalid("old_string 不能为空");
                    }
                    Path safe = context.resolveSafePath(path);
                    if (!Files.exists(safe) || !Files.isRegularFile(safe)) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "文件不存在或不是普通文件，无法编辑: " + path + "（新建文件请用 write_file）", true);
                    }
                    long sourceBytes;
                    try {
                        sourceBytes = Files.size(safe);
                    } catch (Exception e) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "读取文件大小失败: " + e.getMessage(), false);
                    }
                    if (sourceBytes > context.maxWriteFileBytes()) {
                        throw new PolicyException("待编辑文件 " + sourceBytes + " 字节超过 "
                                + (context.maxWriteFileBytes() / 1024 / 1024) + "MB 上限");
                    }
                    String before;
                    try {
                        before = Files.readString(safe);
                    } catch (Exception e) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "读取文件失败: " + e.getMessage(), false);
                    }
                    // Windows 工作区文件可能为 CRLF，而模型经 JSON 给出的 old/new_string 一般是 LF；
                    // 先按文件自身行尾对齐再逐字匹配，避免跨平台行尾差异导致永远匹配不上而反复熔断
                    String fileEol = before.contains("\r\n") ? "\r\n" : "\n";
                    String oldAligned = alignEol(oldString, fileEol);
                    String newAligned = alignEol(newString, fileEol);
                    int occurrences = countOccurrences(before, oldAligned);
                    if (occurrences == 0) {
                        return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                                "old_string 未在文件中匹配到。请重新 read_file 核对确切文本（含缩进/空白/换行）后再编辑", true);
                    }
                    if (occurrences > 1) {
                        return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                                "old_string 在文件中出现 " + occurrences
                                        + " 次，必须唯一。请带上更多前后文使其唯一，或分多次精确编辑", true);
                    }
                    String content = before.replace(oldAligned, newAligned);
                    if (content.equals(before)) {
                        return ToolOutput.success("文件内容未变化，无需写入: " + path);
                    }
                    int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
                    if (contentBytes > context.maxWriteFileBytes()) {
                        throw new PolicyException("编辑后内容 " + contentBytes + " 字节超过 "
                                + (context.maxWriteFileBytes() / 1024 / 1024) + "MB 上限");
                    }
                    String activeStep = context.currentResourceLeaseStep();
                    if (activeStep != null && !activeStep.isBlank()) {
                        context.acquireWriteLease(activeStep, safe);
                        if (!context.isWriteLeaseValid(activeStep, safe)) {
                            throw new PolicyException("写入冲突: 租约已失效，文件 " + path
                                    + " 可能正在被其他任务写入");
                        }
                    }
                    WriteGateResult writeGate = context.validateWrite(activeStep, safe, before);
                    if (!writeGate.isAllowed()) {
                        return ToolOutput.rejected(ToolErrorCode.STALE_CONTEXT,
                                writeGate.reason(), true);
                    }
                    try {
                        executionContext.throwIfCancelled();
                        Files.writeString(safe, content);
                        context.recordFileWrite(path, safe, before, content, activeStep);
                        executionContext.throwIfCancelled();
                        return ToolOutput.success("文件已精确修改: " + path + "（唯一替换 1 处，"
                                + oldString.length() + " -> " + newString.length() + " 字符）");
                    } catch (Exception e) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "编辑写入失败: " + e.getMessage(), false);
                    }
                },
                -1
        ));

        context.registerTool(ToolRegistry.Tool.structured(
                "list_dir",
                "列出目录内容（仅限项目根目录之内）",
                context.createToolParameters(new ToolParameter("path", "string", "目录路径", true)),
                args -> {
                    Path safe = context.resolveSafePath(args.get("path"));
                    try {
                        File[] files = safe.toFile().listFiles();
                        if (files == null) {
                            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                    "目录为空或不存在", false);
                        }
                        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        boolean truncated = false;
                        int emitted = 0;
                        for (File f : files) {
                            String line = (f.isDirectory() ? "[D] " : "[F] ")
                                    + f.getName() + "\n";
                            if (emitted >= MAX_DIRECTORY_ENTRIES
                                    || sb.length() + line.length() > MAX_DIRECTORY_RESULT_CHARS
                                    - DIRECTORY_TRUNCATION_HEADER.length()
                                    + "目录内容:\n".length()) {
                                truncated = true;
                                break;
                            }
                            sb.append(line);
                            emitted++;
                        }
                        if (truncated) {
                            sb.replace(0, "目录内容:\n".length(), DIRECTORY_TRUNCATION_HEADER);
                        }
                        return ToolOutput.success(sb.toString());
                    } catch (Exception e) {
                        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "列出目录失败: " + e.getMessage(), false);
                    }
                }
        ));
    }

    private static ToolOutput readFile(ToolContext context, Map<String, String> args) {
        Path safe = context.resolveSafePath(args.get("path"));
        try {
            boolean explicitLines = hasValue(args, "start_line") || hasValue(args, "end_line");
            boolean explicitChars = hasValue(args, "offset") || hasValue(args, "limit");
            String cursor = value(args, "cursor");
            if (!cursor.isBlank()) {
                if (explicitLines || explicitChars) {
                    return invalid("cursor 不能与 start_line/end_line/offset/limit 同时使用");
                }
                if (cursor.startsWith("line:")) {
                    args = new java.util.LinkedHashMap<>(args);
                    args.put("start_line", cursor.substring("line:".length()));
                    explicitLines = true;
                } else {
                    args = new java.util.LinkedHashMap<>(args);
                    args.put("offset", cursor);
                    explicitChars = true;
                }
            }
            boolean ignoredCharWindow = false;
            if (explicitLines && explicitChars) {
                // 容错：模型同时给出行范围与字符偏移时优先稳定的行范围、忽略字符组，
                // 避免冗余可选参数被硬 reject 后反复重试、触发停滞熔断
                ignoredCharWindow = true;
            }

            ReadPage page = explicitLines
                    ? readLinePage(safe, args)
                    : readCharacterPage(safe, args);
            // 完整页继续保留 Java 符号级基线；分页页只登记磁盘整文件指纹。
            context.recordFileRead(safe, page.completeFile() ? page.content() : null,
                    context.currentResourceLeaseStep());
            FileReadPage metadata = new FileReadPage(
                    args.get("path"), page.mode(), page.offset(), page.startLine(), page.endLine(),
                    page.content().length(), page.nextCursor(), page.hasMore(), Files.size(safe));
            String rendered = renderReadPage(page);
            if (ignoredCharWindow) {
                rendered = "提示: 同时提供了行范围与 offset/limit，已优先使用 start_line/end_line 并忽略 offset/limit，后续请勿两组同传。\n"
                        + rendered;
            }
            return ToolOutput.success(rendered).withSideChannel(metadata);
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "读取文件失败: " + e.getMessage(), false);
        }
    }

    private static ToolOutput readToolResult(Map<String, String> args) {
        try {
            String resultRef = value(args, "result_ref");
            if (resultRef.isBlank()) return invalid("result_ref 不能为空");
            long offset = optionalLong(args, "offset", 0L, 0L, Long.MAX_VALUE);
            int limit = (int) optionalLong(args, "limit",
                    ToolResultArtifactStore.DEFAULT_PAGE_CHARS,
                    1L, ToolResultArtifactStore.MAX_PAGE_CHARS);
            ToolResultArtifactStore.ArtifactPage page =
                    ToolResultArtifactStore.read(resultRef, offset, limit);
            String next = page.nextCursor().isBlank() ? "none" : page.nextCursor();
            return ToolOutput.success(page.content()
                    + "\n\n[tool_result page: result_ref=" + resultRef
                    + ", offset=" + page.offset()
                    + ", returned_chars=" + page.content().length()
                    + ", next_cursor=" + next
                    + ", has_more=" + page.hasMore() + "]");
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "读取工具结果失败: " + e.getMessage(), false);
        }
    }

    private static ReadPage readCharacterPage(Path path, Map<String, String> args) throws Exception {
        long offset = optionalLong(args, "offset", 0L, 0L, Long.MAX_VALUE);
        int limit = (int) optionalLong(args, "limit", MAX_RETURN_CHARS, 1L, MAX_RETURN_CHARS);
        StringBuilder content = new StringBuilder(limit + 1);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            long skipped = skipFully(reader, offset);
            if (skipped < offset) {
                return new ReadPage("chars", "", offset, 0, 0, "", false, false);
            }
            char[] buffer = new char[Math.min(4_096, limit + 1)];
            while (content.length() <= limit) {
                int remaining = limit + 1 - content.length();
                int read = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                content.append(buffer, 0, read);
            }
        }
        boolean hasMore = content.length() > limit;
        if (hasMore) content.setLength(limit);
        String next = hasMore ? Long.toString(offset + content.length()) : "";
        return new ReadPage("chars", content.toString(), offset, 0, 0, next, hasMore,
                offset == 0 && !hasMore);
    }

    private static ReadPage readLinePage(Path path, Map<String, String> args) throws Exception {
        int startLine = (int) optionalLong(args, "start_line", 1L, 1L, Integer.MAX_VALUE);
        int requestedEnd = (int) optionalLong(args, "end_line",
                Math.min((long) Integer.MAX_VALUE, (long) startLine + MAX_LINE_WINDOW - 1),
                startLine, Integer.MAX_VALUE);
        int effectiveEnd = Math.min(requestedEnd, startLine + MAX_LINE_WINDOW - 1);
        StringBuilder content = new StringBuilder();
        long globalOffset = 0L;
        int line = 1;
        int lastLine = 0;
        boolean hasMore = false;
        String next = "";
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (true) {
                if (line >= startLine && content.length() >= MAX_RETURN_CHARS) {
                    int peek = reader.read();
                    if (peek >= 0) {
                        hasMore = true;
                        next = Long.toString(globalOffset);
                    }
                    break;
                }
                int current = reader.read();
                if (current < 0) break;
                globalOffset++;
                if (line < startLine) {
                    if (current == '\n') line++;
                    continue;
                }
                if (line > effectiveEnd) {
                    hasMore = true;
                    next = "line:" + line;
                    break;
                }
                content.append((char) current);
                lastLine = line;
                if (current == '\n') {
                    line++;
                    if (line > effectiveEnd) {
                        int peek = reader.read();
                        if (peek >= 0) {
                            hasMore = true;
                            next = "line:" + line;
                        }
                        break;
                    }
                }
            }
        }
        return new ReadPage("lines", content.toString(), 0L,
                startLine, lastLine, next, hasMore, startLine == 1 && !hasMore);
    }

    private static String renderReadPage(ReadPage page) {
        String next = page.nextCursor().isBlank() ? "none" : page.nextCursor();
        String range = "lines".equals(page.mode())
                ? ", start_line=" + page.startLine() + ", end_line=" + page.endLine()
                : ", offset=" + page.offset();
        return "文件内容:\n" + page.content()
                + "\n\n[read_file page: mode=" + page.mode()
                + range
                + ", returned_chars=" + page.content().length()
                + ", max_return_tokens=" + DEFAULT_MAX_RETURN_TOKENS
                + ", next_cursor=" + next
                + ", has_more=" + page.hasMore() + "]";
    }

    private static long skipFully(Reader reader, long offset) throws Exception {
        long skipped = 0L;
        while (skipped < offset) {
            long current = reader.skip(offset - skipped);
            if (current > 0) {
                skipped += current;
            } else if (reader.read() >= 0) {
                skipped++;
            } else {
                break;
            }
        }
        return skipped;
    }

    private static long optionalLong(Map<String, String> args, String name, long defaultValue,
                                     long minimum, long maximum) {
        String raw = value(args, name);
        if (raw.isBlank()) return defaultValue;
        long parsed;
        try {
            parsed = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " 必须位于 [" + minimum + ", " + maximum + "]");
        }
        return parsed;
    }

    private static boolean hasValue(Map<String, String> args, String name) {
        return !value(args, name).isBlank();
    }

    private static String alignEol(String text, String fileEol) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", fileEol);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static String value(Map<String, String> args, String name) {
        String value = args == null ? null : args.get(name);
        return value == null ? "" : value.trim();
    }

    private static ToolOutput invalid(String message) {
        return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                "工具参数无效: " + message, true);
    }

    private record ReadPage(String mode, String content, long offset,
                            int startLine, int endLine, String nextCursor, boolean hasMore,
                            boolean completeFile) {
    }
}
