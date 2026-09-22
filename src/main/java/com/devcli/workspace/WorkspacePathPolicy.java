package com.devcli.workspace;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class WorkspacePathPolicy {
    private static final Set<String> EXCLUDED_ROOTS = Set.of(
            ".git", ".devcli", ".idea", "target", "build", "dist",
            "node_modules", "Temp", "Log");

    private WorkspacePathPolicy() {
    }

    static Set<String> excludedRoots() {
        return EXCLUDED_ROOTS;
    }

    static boolean isExcluded(Path root, Path workspaceBase, Path path) {
        Path normalizedRoot = normalize(root);
        Path normalized = normalize(path);
        if (!normalized.startsWith(normalizedRoot)) {
            return true;
        }
        if (workspaceBase != null && normalized.startsWith(normalize(workspaceBase))) {
            return true;
        }
        Path relative = normalizedRoot.relativize(normalized);
        if (relative.getNameCount() == 0) {
            return false;
        }
        for (Path segment : relative) {
            if (EXCLUDED_ROOTS.contains(segment.toString())) {
                return true;
            }
        }
        return isSensitiveFile(relative);
    }

    /**
     * 默认不把常见本地凭据带入隔离工作区或 PatchSet。
     *
     * <p>名单与 {@link com.devcli.policy.SensitivePathPolicy} 共用一份实现；物化传
     * {@code allowTemplates=false}，因此 {@code .env.example} 也不进工作区，保持既有行为。</p>
     */
    static boolean isSensitiveFile(Path relative) {
        return com.devcli.policy.SensitivePathPolicy.isSensitiveFile(relative, false);
    }

    static String relativePath(Path root, Path file) {
        Path normalizedRoot = normalize(root);
        Path normalizedFile = normalize(file);
        if (!normalizedFile.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("path is outside project root");
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }

    static Path normalize(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        return path.toAbsolutePath().normalize();
    }
}
