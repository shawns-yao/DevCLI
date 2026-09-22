package com.devcli.memory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 长期记忆的物理目录约定。
 *
 * <p>作用域由**目录**表达，而不是条目元数据：全局记忆落在
 * {@code <memoryRoot>/global}，项目记忆落在 {@code <memoryRoot>/projects/<项目键>/memory}。
 * 一个目录只属于一个作用域，检索时不需要读取条目字段再做布尔过滤，
 * 也就不存在「跨作用域条目互相挤占候选位」的问题。
 *
 * <p>根目录优先级：{@code -Ddevcli.memory.dir} &gt; {@code DEVCLI_MEMORY_DIR} &gt;
 * {@code ~/.devcli/memory}。沿用旧配置键是为了不打断既有测试与本地部署，
 * 但子目录布局与旧版平铺 {@code *.md} 不兼容。
 */
public final class MemoryPaths {

    /** 记忆根目录的系统属性键。 */
    public static final String MEMORY_DIR_PROPERTY = "devcli.memory.dir";
    /** 记忆根目录的环境变量键。 */
    public static final String MEMORY_DIR_ENV = "DEVCLI_MEMORY_DIR";

    /** 全局记忆子目录名。 */
    public static final String GLOBAL_DIR_NAME = "global";
    /** 项目记忆父目录名。 */
    public static final String PROJECTS_DIR_NAME = "projects";
    /** 单个项目下的记忆目录名。 */
    public static final String PROJECT_MEMORY_DIR_NAME = "memory";

    private MemoryPaths() {
    }

    /**
     * 记忆根目录。
     */
    public static Path memoryRoot() {
        String configured = System.getProperty(MEMORY_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MEMORY_DIR_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".devcli", "memory")
                .toAbsolutePath().normalize();
    }

    /**
     * 全局长期记忆目录，对所有项目可见。
     */
    public static Path globalMemoryDir() {
        return globalMemoryDir(memoryRoot());
    }

    /** 指定记忆根目录下的全局记忆目录。 */
    public static Path globalMemoryDir(Path root) {
        Path base = root == null ? memoryRoot() : root.toAbsolutePath().normalize();
        return base.resolve(GLOBAL_DIR_NAME);
    }

    /**
     * 指定项目的长期记忆目录。
     *
     * @param projectPath 项目根路径；为空时返回 {@code null}（调用方据此退化为「只有全局记忆」）
     */
    public static Path projectMemoryDir(String projectPath) {
        return projectMemoryDir(memoryRoot(), projectPath);
    }

    /** 指定记忆根目录下的项目记忆目录。 */
    public static Path projectMemoryDir(Path root, String projectPath) {
        String key = projectKey(projectPath);
        if (key.isEmpty()) return null;
        Path base = root == null ? memoryRoot() : root.toAbsolutePath().normalize();
        return base.resolve(PROJECTS_DIR_NAME).resolve(key).resolve(PROJECT_MEMORY_DIR_NAME);
    }

    /**
     * 项目键：由项目绝对路径压缩而来，作为目录名。
     *
     * @return 压缩后的键；输入为空时返回空串
     */
    public static String projectKey(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) return "";
        return compressPath(Path.of(projectPath.trim()).toAbsolutePath().normalize().toString());
    }

    /**
     * 把绝对路径压成单层目录名：分隔符与盘符冒号折成 {@code -}，折叠重复连字符并去掉首尾连字符。
     *
     * <p>额外做小写化——Windows / macOS 的默认文件系统不区分大小写，
     * 若不做小写化，{@code C:\Foo} 与 {@code c:\foo} 会落到两个目录，
     * 同一项目出现两份记忆。代价是在大小写敏感的 Linux 上，
     * 仅大小写不同的两个项目会共用目录；长期记忆本就是用户可见的纯文本，可手工纠正。
     */
    public static String compressPath(String path) {
        if (path == null || path.isBlank()) return "";
        String compressed = path.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[/\\\\:]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return compressed;
    }
}
