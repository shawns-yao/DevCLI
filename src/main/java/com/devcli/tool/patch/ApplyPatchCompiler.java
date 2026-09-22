package com.devcli.tool.patch;

import com.devcli.workspace.FileModeSnapshot;
import com.devcli.workspace.PatchSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 把解析后的 hunks 编译成 {@link PatchSet}。
 *
 * <p>关键设计：apply_patch 不新开写入路径。补丁在内存里算出每个文件的最终内容，
 * 组装成 {@code PatchSet.FileChange} 后交给既有应用流程，从而复用它的全量冲突预检、
 * 项目级锁、原子替换与失败回滚。这里只负责"算出内容 + 表达变更"，不负责落盘。</p>
 *
 * <p>与官方实现的刻意差异：官方逐个 hunk 直接写盘，中途失败只上报已提交部分；
 * 这里先全部算完再交给 PatchSet，失败可以整体回滚。</p>
 */
final class ApplyPatchCompiler {

    /** 编译结果：只包含真正产生内容差异的变更。 */
    record Compiled(List<PatchSet.FileChange> changes) {
        Compiled {
            changes = List.copyOf(changes);
        }

        boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    /**
     * 一次编译的公共上下文。
     *
     * @param root              项目根，PatchSet 需要项目相对路径
     * @param pathResolver      写路径解析（根围栏 + 受保护路径策略），通常是
     *                          {@code ToolContext::resolveSafeWritePath}；抛出的策略异常原样向上传递
     * @param maxWriteFileBytes 单文件上限，与 write_file / edit_file 同一口径
     */
    private record Scope(Path root, Function<String, Path> pathResolver, long maxWriteFileBytes) {
    }

    private ApplyPatchCompiler() {
    }

    static Compiled compile(List<PatchHunk> hunks, Path projectRoot,
                            Function<String, Path> pathResolver, long maxWriteFileBytes) {
        Scope scope = new Scope(projectRoot.toAbsolutePath().normalize(), pathResolver,
                maxWriteFileBytes);
        List<PatchSet.FileChange> changes = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        for (PatchHunk hunk : hunks) {
            // Java 17 不支持 switch 模式匹配，按 sealed 子类型分派。
            if (hunk instanceof PatchHunk.AddFile add) {
                addFile(scope, add, changes, touched);
            } else if (hunk instanceof PatchHunk.DeleteFile delete) {
                deleteFile(scope, delete, changes, touched);
            } else if (hunk instanceof PatchHunk.UpdateFile update) {
                updateFile(scope, update, changes, touched);
            } else {
                throw new PatchException("不支持的补丁类型: " + hunk.getClass().getSimpleName());
            }
        }
        return new Compiled(changes);
    }

    private static void addFile(Scope scope, PatchHunk.AddFile hunk,
                                List<PatchSet.FileChange> changes, Set<String> touched) {
        Target target = claim(scope, hunk.path(), touched);
        String content = String.join("\n", hunk.contents()) + "\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ensureWithinLimit(scope, hunk.path(), bytes.length);
        if (target.state() == FileState.REGULAR) {
            // Add File 落到已存在文件时退化为修改：保留 beforeHash 闸门，
            // 这样"期间被别的写入改过"仍会被判为冲突，而不是静默覆盖。
            String existing = readText(scope, target);
            if (existing.equals(content)) {
                return;
            }
            FileModeSnapshot mode = target.mode();
            changes.add(new PatchSet.FileChange(target.relativePath(), PatchSet.ChangeType.MODIFY,
                    PatchSet.hash(existing.getBytes(StandardCharsets.UTF_8)), PatchSet.hash(bytes),
                    bytes, mode, mode));
            return;
        }
        // beforeHash 传 null 即 PatchSet 的 MISSING_HASH：要求目标当前不存在。
        changes.add(new PatchSet.FileChange(target.relativePath(), PatchSet.ChangeType.ADD,
                null, PatchSet.hash(bytes), bytes, null, defaultNewFileMode()));
    }

    private static void deleteFile(Scope scope, PatchHunk.DeleteFile hunk,
                                   List<PatchSet.FileChange> changes, Set<String> touched) {
        Target target = claim(scope, hunk.path(), touched);
        if (target.state() != FileState.REGULAR) {
            throw new PatchException("Delete File 目标不存在或不是普通文件: " + hunk.path());
        }
        byte[] existing = readBytes(scope, target);
        // afterHash 传 null 即 PatchSet 的 MISSING_HASH：要求目标被删除。
        changes.add(new PatchSet.FileChange(target.relativePath(), PatchSet.ChangeType.DELETE,
                PatchSet.hash(existing), null, new byte[0], target.mode(), null));
    }

    private static void updateFile(Scope scope, PatchHunk.UpdateFile hunk,
                                   List<PatchSet.FileChange> changes, Set<String> touched) {
        Target target = claim(scope, hunk.path(), touched);
        if (target.state() != FileState.REGULAR) {
            throw new PatchException("Update File 目标不存在或不是普通文件: " + hunk.path()
                    + "（新建文件请使用 '*** Add File:'）");
        }
        String original = readText(scope, target);
        TextFile text = TextFile.parse(original);
        List<String> updatedLines = computeReplacements(text.lines(), target.relativePath(),
                hunk.chunks());
        String updated = text.withLines(updatedLines).render();
        byte[] updatedBytes = updated.getBytes(StandardCharsets.UTF_8);
        ensureWithinLimit(scope, target.relativePath(), updatedBytes.length);

        if (hunk.movePath() == null) {
            if (updated.equals(original)) {
                // 内容未变化不产生变更：不推进 generation，也不污染 RAG dirty 集。
                return;
            }
            FileModeSnapshot mode = target.mode();
            changes.add(new PatchSet.FileChange(target.relativePath(), PatchSet.ChangeType.MODIFY,
                    PatchSet.hash(original.getBytes(StandardCharsets.UTF_8)), PatchSet.hash(updatedBytes),
                    updatedBytes, mode, mode));
            return;
        }

        Target destination = claim(scope, hunk.movePath(), touched);
        changes.add(new PatchSet.FileChange(target.relativePath(), PatchSet.ChangeType.DELETE,
                PatchSet.hash(original.getBytes(StandardCharsets.UTF_8)), null, new byte[0],
                target.mode(), null));
        if (destination.state() == FileState.REGULAR) {
            String existing = readText(scope, destination);
            FileModeSnapshot mode = destination.mode();
            changes.add(new PatchSet.FileChange(destination.relativePath(), PatchSet.ChangeType.MODIFY,
                    PatchSet.hash(existing.getBytes(StandardCharsets.UTF_8)), PatchSet.hash(updatedBytes),
                    updatedBytes, mode, mode));
        } else {
            changes.add(new PatchSet.FileChange(destination.relativePath(), PatchSet.ChangeType.ADD,
                    null, PatchSet.hash(updatedBytes), updatedBytes, null, defaultNewFileMode()));
        }
    }

    /**
     * 计算修改后的行列表。
     *
     * <p>块必须按文件顺序排列，{@code lineIndex} 单调前进；这是官方语义，
     * 顺序错乱的补丁会被拒绝而不是猜一个位置。</p>
     */
    private static List<String> computeReplacements(List<String> originalLines, String path,
                                                    List<PatchHunk.UpdateFileChunk> chunks) {
        List<Replacement> replacements = new ArrayList<>();
        int lineIndex = 0;
        for (PatchHunk.UpdateFileChunk chunk : chunks) {
            if (chunk.changeContext() != null) {
                int context = SeekSequence.seek(originalLines, List.of(chunk.changeContext()),
                        lineIndex, false);
                if (context < 0) {
                    throw new PatchException("在 " + path + " 中找不到定位上下文 '"
                            + chunk.changeContext() + "'");
                }
                lineIndex = context + 1;
            }
            if (chunk.oldLines().isEmpty()) {
                // 官方语义：没有原文行时在文件末尾插入，而不是在 lineIndex 处插入。
                replacements.add(new Replacement(originalLines.size(), 0, chunk.newLines()));
                continue;
            }
            List<String> pattern = chunk.oldLines();
            List<String> replacement = chunk.newLines();
            int found = SeekSequence.seek(originalLines, pattern, lineIndex, chunk.endOfFile());
            if (found < 0 && !pattern.isEmpty() && pattern.get(pattern.size() - 1).isEmpty()) {
                // 补丁末行的空串代表文件末尾换行，文件行列表里没有这个哨兵，去掉后重试。
                pattern = pattern.subList(0, pattern.size() - 1);
                if (!replacement.isEmpty() && replacement.get(replacement.size() - 1).isEmpty()) {
                    replacement = replacement.subList(0, replacement.size() - 1);
                }
                found = SeekSequence.seek(originalLines, pattern, lineIndex, chunk.endOfFile());
            }
            if (found < 0) {
                throw new PatchException("在 " + path + " 中找不到期望的原文:\n"
                        + String.join("\n", chunk.oldLines())
                        + "\n（文件可能已被改动，请重新 read_file 核对当前内容后重发补丁）");
            }
            replacements.add(new Replacement(found, pattern.size(), replacement));
            lineIndex = found + pattern.size();
        }
        replacements.sort(Comparator.comparingInt(Replacement::start));
        return applyReplacements(originalLines, replacements);
    }

    /** 必须按下标降序应用，否则先做的替换会移动后面替换的位置。 */
    private static List<String> applyReplacements(List<String> originalLines,
                                                  List<Replacement> replacements) {
        List<String> lines = new ArrayList<>(originalLines);
        for (int index = replacements.size() - 1; index >= 0; index--) {
            Replacement replacement = replacements.get(index);
            for (int removed = 0; removed < replacement.oldLength(); removed++) {
                if (replacement.start() < lines.size()) {
                    lines.remove(replacement.start());
                }
            }
            lines.addAll(replacement.start(), replacement.newLines());
        }
        return lines;
    }

    private static Target claim(Scope scope, String rawPath, Set<String> touched) {
        Path absolute = resolve(scope, rawPath);
        String relative = scope.root().relativize(absolute).toString().replace('\\', '/');
        if (relative.isEmpty()) {
            throw new PatchException("补丁路径指向项目根目录: " + rawPath);
        }
        if (!touched.add(relative)) {
            // 同一路径在补丁里出现两次时，两次变更的基线都取自应用前状态，
            // PatchSet 预检会双双通过，然后后者静默覆盖前者。
            throw new PatchException("补丁重复声明了同一路径: " + relative
                    + "（每个路径在同一补丁里只能出现一次）");
        }
        return new Target(relative, absolute, state(absolute));
    }

    private static Path resolve(Scope scope, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new PatchException("补丁路径为空");
        }
        Path safe = scope.pathResolver().apply(rawPath);
        if (safe == null) {
            throw new PatchException("补丁路径无法解析: " + rawPath);
        }
        Path absolute = safe.toAbsolutePath().normalize();
        if (!absolute.startsWith(scope.root())) {
            throw new PatchException("补丁路径不在项目根目录内: " + rawPath);
        }
        return absolute;
    }

    private static FileState state(Path path) {
        if (Files.isSymbolicLink(path)) {
            return FileState.OTHER;
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return FileState.ABSENT;
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ? FileState.REGULAR : FileState.OTHER;
    }

    private static byte[] readBytes(Scope scope, Target target) {
        ensureWithinLimit(scope, target.relativePath(), size(target));
        try {
            return Files.readAllBytes(target.absolutePath());
        } catch (IOException e) {
            throw new PatchException("读取文件失败: " + target.relativePath() + " — " + e.getMessage(), e);
        }
    }

    private static String readText(Scope scope, Target target) {
        ensureWithinLimit(scope, target.relativePath(), size(target));
        try {
            return Files.readString(target.absolutePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PatchException("读取文件失败（补丁只支持 UTF-8 文本）: "
                    + target.relativePath() + " — " + e.getMessage(), e);
        }
    }

    private static long size(Target target) {
        try {
            return Files.size(target.absolutePath());
        } catch (IOException e) {
            // 取不到大小交给后续读取报错，不在这里编造理由。
            return -1L;
        }
    }

    /** 与 write_file / edit_file 同一口径的单文件上限，读之前先判，避免为超大文件做解码。 */
    private static void ensureWithinLimit(Scope scope, String path, long bytes) {
        if (bytes > scope.maxWriteFileBytes()) {
            throw new PatchException("文件 " + path + " 为 " + bytes + " 字节，超过 "
                    + (scope.maxWriteFileBytes() / 1024 / 1024) + "MB 上限");
        }
    }

    private static FileModeSnapshot defaultNewFileMode() {
        return DefaultNewFileMode.VALUE;
    }

    /**
     * 新建文件的权限。
     *
     * <p>PatchSet 用临时文件原子替换目标，而 JDK 的临时文件在 POSIX 上是
     * {@code rw-------}；若不给 afterMode，新建文件会比 {@code write_file} 创建的更严，
     * 可能导致沙箱里以其他 UID 运行的构建读不到文件。这里探一次进程默认建文件权限
     * （受 umask 影响，所以不能硬编码 0644），探针放在系统临时目录，不触碰项目。</p>
     */
    private static final class DefaultNewFileMode {
        private static final FileModeSnapshot VALUE = probe();

        private static FileModeSnapshot probe() {
            Path directory = null;
            Path probe = null;
            try {
                directory = Files.createTempDirectory("devcli-file-mode-");
                probe = Files.createFile(directory.resolve("probe"));
                return FileModeSnapshot.capture(probe);
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    if (probe != null) {
                        Files.deleteIfExists(probe);
                    }
                    if (directory != null) {
                        Files.deleteIfExists(directory);
                    }
                } catch (Exception ignored) {
                    // 探针清理失败不影响补丁应用
                }
            }
        }
    }

    private enum FileState {
        ABSENT,
        REGULAR,
        OTHER
    }

    private record Target(String relativePath, Path absolutePath, FileState state) {
        FileModeSnapshot mode() {
            try {
                return FileModeSnapshot.capture(absolutePath);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private record Replacement(int start, int oldLength, List<String> newLines) {
        Replacement {
            newLines = List.copyOf(newLines);
        }
    }

    /**
     * 按行承载的文件内容。
     *
     * <p>内部统一按 LF 处理，渲染时还原文件原有行尾。这样既避免把 Windows 的 CRLF
     * 文件整篇改成 LF（官方默认行为），也不需要逐行跟踪终止符。</p>
     */
    private record TextFile(List<String> lines, String eol, boolean trailingNewline) {
        TextFile {
            lines = List.copyOf(lines);
        }

        static TextFile parse(String content) {
            String eol = detectEol(content);
            String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
            boolean trailingNewline = normalized.endsWith("\n");
            List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
            if (trailingNewline && !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                // 末尾换行不产生额外行，与 diff 的行数语义一致。
                lines.remove(lines.size() - 1);
            }
            return new TextFile(lines, eol, trailingNewline);
        }

        TextFile withLines(List<String> updated) {
            return new TextFile(updated, eol, trailingNewline);
        }

        String render() {
            String joined = String.join("\n", lines);
            if (trailingNewline) {
                joined = joined + "\n";
            }
            return "\n".equals(eol) ? joined : joined.replace("\n", eol);
        }

        private static String detectEol(String content) {
            int crlf = 0;
            int lf = 0;
            for (int index = 0; index < content.length(); index++) {
                if (content.charAt(index) != '\n') {
                    continue;
                }
                if (index > 0 && content.charAt(index - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
            return crlf > lf ? "\r\n" : "\n";
        }
    }
}
