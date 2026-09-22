package com.devcli.policy;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目相对路径 glob 集合。
 *
 * <p>任务级授权范围与委派写白名单共用同一套匹配语义，避免出现两份会漂移的 glob 实现。</p>
 */
public final class WriteGlobSet {

    /** 覆盖整个项目的通配 glob。 */
    public static final String WHOLE_PROJECT = "**";

    private WriteGlobSet() {
    }

    public static boolean isEmpty(List<String> globs) {
        return globs == null || globs.isEmpty();
    }

    /**
     * 校验并规范化单个 glob：拒绝绝对路径与 `..` 跳出项目的写法。
     *
     * @return 规范化后的 glob；非法时抛 {@link IllegalArgumentException}
     */
    public static String requireProjectRelativeGlob(String raw) {
        String pattern = normalizePattern(raw);
        if (pattern == null) {
            throw new IllegalArgumentException("授权路径不能为空");
        }
        if (pattern.startsWith("/") || pattern.startsWith("\\")
                || pattern.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("授权路径必须是项目相对路径: " + raw);
        }
        for (String segment : pattern.replace('\\', '/').split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("授权路径不能跳出项目根: " + raw);
            }
        }
        return pattern;
    }

    /** 项目相对路径是否落在任一 glob 内；空集合表示不匹配任何路径。 */
    public static boolean matches(List<String> globs, String projectRelativePath) {
        if (isEmpty(globs) || projectRelativePath == null || projectRelativePath.isBlank()) {
            return false;
        }
        Path relativePath = Path.of(projectRelativePath);
        for (String pattern : globs) {
            if (matchesOne(pattern, relativePath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesOne(String pattern, Path relativePath) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String relative = relativePath.toString().replace('\\', '/');
        // Java glob 的 `**` 不跨多级目录，`dir/**` 不会匹配 `dir/a/b.txt`。
        // 这里显式把 `dir/**` 定义为「该目录及其所有后代」，与用户和文档的预期一致。
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return relative.equals(prefix) || relative.startsWith(prefix + "/");
        }
        if (WHOLE_PROJECT.equals(pattern)) {
            return true;
        }
        try {
            return FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern.replace('/', java.io.File.separatorChar))
                    .matches(relativePath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String normalizePattern(String raw) {
        if (raw == null) {
            return null;
        }
        String pattern = raw.trim().replace('\\', '/');
        while (pattern.startsWith("./")) {
            pattern = pattern.substring(2);
        }
        return pattern.isBlank() ? null : pattern;
    }
}
