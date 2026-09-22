package com.devcli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 记忆索引 {@code MEMORY.md} 的读写。
 *
 * <p>索引是「目录的目录」：每个主题记忆占一行，内容是文件名 + 时间 + 名称 + 描述。
 * 它有两个读者——人（直接打开看有什么记忆）和模型（注入上下文时先给索引，再按需读全文）。
 *
 * <p>注入时有界：只取前 {@link #MAX_LINES} 行或 {@link #MAX_BYTES} 字节，先到先算，
 * 并在末尾追加一句截断提示。旧版没有这个边界，索引长起来会静默吃掉上下文预算。
 */
public final class MemoryIndex {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndex.class);

    /** 索引文件名。 */
    public static final String INDEX_FILE_NAME = "MEMORY.md";
    /** 注入上下文时最多取的行数。 */
    public static final int MAX_LINES = 200;
    /** 注入上下文时最多取的字节数。 */
    public static final int MAX_BYTES = 25_000;
    /** 截断提示，紧跟被截断的索引之后。 */
    public static final String TRUNCATION_NOTICE =
            "[MEMORY.md 已截断至前 200 行 —— 需要完整内容请用 Read 工具读取索引文件]";
    /** 按本轮上下文预算裁剪后的提示。 */
    public static final String BUDGET_NOTICE =
            "[长期记忆索引已按本轮记忆预算截断 —— 需要完整目录请用 Read 工具读取索引文件]";

    private static final String HEADER = "# 长期记忆索引\n";

    private MemoryIndex() {
    }

    /**
     * 读取索引文本（已截断）。文件不存在时返回空串。
     */
    public static String read(Path dir) {
        Path indexFile = fileOf(dir);
        if (indexFile == null || !Files.isRegularFile(indexFile)) return "";
        String raw;
        try {
            raw = Files.readString(indexFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取记忆索引失败 {}: {}", indexFile, e.getMessage());
            return "";
        }
        return truncate(raw);
    }

    /**
     * 截断到前 {@link #MAX_LINES} 行或 {@link #MAX_BYTES} 字节，必要时追加提示。
     */
    public static String truncate(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String text = raw.replace("\r\n", "\n");
        String[] lines = text.split("\n", -1);
        boolean truncated = lines.length > MAX_LINES;
        String candidate = truncated
                ? String.join("\n", java.util.Arrays.copyOf(lines, MAX_LINES))
                : text;
        if (candidate.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            candidate = truncateToBytes(candidate, MAX_BYTES);
            truncated = true;
        }
        return truncated ? candidate.strip() + "\n\n" + TRUNCATION_NOTICE : candidate.strip();
    }

    /**
     * 按 token 预算裁剪索引文本：整行保留，放不下的行丢弃并追加预算提示。
     *
     * <p>{@link #MAX_LINES} / {@link #MAX_BYTES} 是索引自身的绝对上限，
     * 与本轮分配给记忆的 token 预算无关——两者可以差一个数量级。
     * 注入前必须再过这一道，否则索引会静默吃掉远超预算的上下文。
     */
    public static String fitToTokens(String text, int maxTokens) {
        if (text == null || text.isBlank() || maxTokens <= 0) return "";
        if (MemoryEntry.estimateTokens(text) <= maxTokens) return text.strip();
        String suffix = "\n\n" + BUDGET_NOTICE;
        // estimateTokens 对拼接是次可加的（各分段之和 ≥ 整体），
        // 所以「逐行预算之和」是整段正文的保守上界，先扣掉提示自身额度再裁行
        int lineBudget = maxTokens - MemoryEntry.estimateTokens(suffix) - 1;
        if (lineBudget <= 0) return "";
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.equals(TRUNCATION_NOTICE) || stripped.equals(BUDGET_NOTICE)) continue;
            int lineTokens = MemoryEntry.estimateTokens(line + "\n");
            if (used + lineTokens > lineBudget && kept.length() > 0) break;
            kept.append(line).append('\n');
            used += lineTokens;
        }
        String result = kept.toString().strip();
        return result.isEmpty() ? "" : result + suffix;
    }

    /**
     * 用给定主题记忆集合重写索引（按修改时间倒序）。
     */
    public static void write(Path dir, List<TopicMemory> topics) {
        if (dir == null) return;
        List<TopicMemory> ordered = new ArrayList<>(topics == null ? List.of() : topics);
        ordered.sort(Comparator.comparing(TopicMemory::updatedAt).reversed());
        if (ordered.isEmpty()) {
            deleteIndex(dir);
            return;
        }
        StringBuilder builder = new StringBuilder(HEADER);
        for (TopicMemory topic : ordered) {
            builder.append(topic.manifestLine()).append('\n');
        }
        writeRaw(dir, builder.toString());
    }

    /** 索引文件路径。 */
    public static Path fileOf(Path dir) {
        return dir == null ? null : dir.resolve(INDEX_FILE_NAME);
    }

    private static void writeRaw(Path dir, String content) {
        Path indexFile = fileOf(dir);
        if (indexFile == null) return;
        try {
            Files.createDirectories(dir);
            if (content == null || content.isBlank()) {
                Files.deleteIfExists(indexFile);
                return;
            }
            if (Files.isRegularFile(indexFile)
                    && content.equals(Files.readString(indexFile, StandardCharsets.UTF_8))) {
                return;
            }
            Path temp = Files.createTempFile(dir, ".memory-index-", ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                moveAtomically(temp, indexFile);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.warn("写入记忆索引失败 {}: {}", indexFile, e.getMessage());
        }
    }

    private static void deleteIndex(Path dir) {
        Path index = fileOf(dir);
        if (index == null) return;
        try {
            Files.deleteIfExists(index);
        } catch (IOException e) {
            log.warn("删除空记忆索引失败 {}: {}", index, e.getMessage());
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String truncateToBytes(String text, int maxBytes) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (text.substring(0, mid).getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low);
    }
}
