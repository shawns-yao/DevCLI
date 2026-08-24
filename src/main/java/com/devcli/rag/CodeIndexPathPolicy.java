package com.devcli.rag;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** 代码索引构建与实时文件监听共用的路径筛选契约。 */
final class CodeIndexPathPolicy {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "node_modules", "target", "build", ".git", ".idea", "dist", "out",
            ".next", ".nuxt", ".cache", ".pytest_cache", ".mypy_cache", "__pycache__");
    private static final Set<String> INDEXED_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h",
            ".md", ".xml", ".properties", ".yaml", ".yml", ".json", ".sh",
            ".gradle", ".kt");

    private CodeIndexPathPolicy() {
    }

    static boolean isExcludedDirectory(Path directory) {
        if (directory == null || directory.getFileName() == null) return false;
        return EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString());
    }

    static boolean isIndexableFile(Path file) {
        if (file == null || file.getFileName() == null) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return INDEXED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
