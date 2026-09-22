package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 隔离工作区物化后端。
 */
public interface WorkspaceBackend {
    Materialization materialize(Path projectRoot, Path workspaceBase, Path workspacePath) throws IOException;

    default void cleanup(Path projectRoot, Path workspaceBase, Path workspacePath) throws IOException {
        WorkspaceCleanupPolicy.deleteWorkspace(workspaceBase, workspacePath);
    }

    /**
     * 物化结果。
     *
     * @param baselineHashes 子工作区相对路径 → 内容哈希
     * @param overlayBytes   为让子工作区看到父工作区当前状态而额外复制的字节数
     *                       （worktree 的叠加成本）；整树克隆类后端为 0
     */
    record Materialization(Map<String, String> baselineHashes, long overlayBytes) {
        public Materialization {
            baselineHashes = baselineHashes == null ? Map.of() : Map.copyOf(baselineHashes);
            overlayBytes = Math.max(0L, overlayBytes);
        }

        /** 兼容不产生叠加成本的既有调用方。 */
        public Materialization(Map<String, String> baselineHashes) {
            this(baselineHashes, 0L);
        }
    }
}
