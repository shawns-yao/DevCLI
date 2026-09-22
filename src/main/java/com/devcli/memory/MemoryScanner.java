package com.devcli.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 记忆目录扫描器。
 *
 * <p>把目录里的主题记忆文件按修改时间倒序列出，供索引生成与 LLM 选择器使用。
 * 只认 {@code .md}，并排除索引文件本身（{@link MemoryIndex#INDEX_FILE_NAME}）——
 * 索引是目录的目录，不是一条记忆。
 *
 * <p>有界：最多返回 {@link #MAX_FILES} 条。旧版把「全库扫描 → 按时间截断 → 再过滤作用域」
 * 串成三段，导致项目记忆被全局记忆挤掉候选位；现在作用域由目录决定，
 * 扫描的输入已经是单作用域目录，截断不会再跨作用域误伤。
 */
public final class MemoryScanner {

    /** 单次扫描返回的文件数上限。 */
    public static final int MAX_FILES = 200;
    /** 递归扫描的目录深度上限，避免误指向仓库根时扫穿整棵树。 */
    public static final int MAX_DEPTH = 4;

    private MemoryScanner() {
    }

    /**
     * 扫描目录内的主题记忆文件，按修改时间倒序，最多 {@link #MAX_FILES} 条。
     *
     * @return 文件路径列表；目录不存在时返回空列表
     */
    public static List<Path> scan(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, MAX_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(MemoryScanner::isTopicFile)
                    .forEach(found::add);
        } catch (IOException ignored) {
            return List.of();
        }
        found.sort(Comparator.comparingLong(MemoryScanner::lastModified).reversed());
        return found.size() <= MAX_FILES ? List.copyOf(found) : List.copyOf(found.subList(0, MAX_FILES));
    }

    /**
     * 扫描并解析文件头，得到可直接用于索引与选择的轻量列表。
     */
    public static List<TopicMemory> scanHeads(Path dir) {
        List<TopicMemory> result = new ArrayList<>();
        for (Path file : scan(dir)) {
            TopicMemory memory = TopicMemory.readHead(file);
            if (memory != null) result.add(memory);
        }
        return result;
    }

    /**
     * 是否为可识别的主题记忆文件。
     */
    public static boolean isTopicFile(Path file) {
        if (file == null) return false;
        String fileName = file.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) return false;
        return !MemoryIndex.INDEX_FILE_NAME.equalsIgnoreCase(fileName);
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
