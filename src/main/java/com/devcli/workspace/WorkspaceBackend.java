package com.devcli.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 隔离工作区物化后端。
 */
public interface WorkspaceBackend {
    Materialization materialize(Path projectRoot, Path workspaceBase, Path workspacePath) throws IOException;

    record Materialization(Map<String, String> baselineHashes) {
        public Materialization {
            baselineHashes = baselineHashes == null ? Map.of() : Map.copyOf(baselineHashes);
        }
    }
}
