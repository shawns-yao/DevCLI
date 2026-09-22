package com.devcli.memory;

import java.nio.file.Path;

/**
 * 长期记忆作用域。
 *
 * <p>作用域即目录，只有两级：
 * <ul>
 *   <li>{@link #GLOBAL} —— 跨项目可见，写 {@link MemoryPaths#globalMemoryDir()}</li>
 *   <li>{@link #PROJECT} —— 只在当前项目可见，写 {@link MemoryPaths#projectMemoryDir(String)}</li>
 * </ul>
 *
 * <p>旧版的 {@code USER} / {@code REPOSITORY} / {@code SYMBOL} 三级细粒度作用域已移除：
 * 它们没有任何生产路径会写入，只会让同一份记忆在多个作用域各存一份，
 * 再由检索期做布尔过滤——这是「过时事实自我强化」的温床。
 */
public enum MemoryScope {
    GLOBAL,
    PROJECT;

    /**
     * 解析作用域名；未知值拒绝，避免项目事实因拼写错误被扩大为全局记忆。
     */
    public static MemoryScope of(String value) {
        if (value == null || value.isBlank()) return GLOBAL;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "PROJECT", "REPOSITORY", "LOCAL" -> PROJECT;
            case "GLOBAL", "USER" -> GLOBAL;
            default -> throw new IllegalArgumentException("未知记忆作用域: " + value);
        };
    }

    /**
     * 该作用域对应的记忆目录。
     *
     * <p>必须传 {@code memoryRoot}：只靠 {@link MemoryPaths#memoryRoot()} 的全局默认值
     * 会绕过实例自己的根目录，测试注入的临时目录和自定义 {@code DEVCLI_MEMORY_DIR} 都会失配。
     *
     * @param projectPath 项目根路径，{@link #PROJECT} 时必需
     * @return 目录；{@link #PROJECT} 且项目路径缺失时返回 {@code null}
     */
    public Path dir(Path memoryRoot, String projectPath) {
        return this == GLOBAL
                ? MemoryPaths.globalMemoryDir(memoryRoot)
                : MemoryPaths.projectMemoryDir(memoryRoot, projectPath);
    }
}
