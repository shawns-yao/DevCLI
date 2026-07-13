package com.devcli.workspace;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PatchSetMemoryProcess {
    private PatchSetMemoryProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path project = Files.createDirectories(root.resolve("project"));
        Path workspaces = Files.createDirectories(root.resolve("workspaces"));
        try (RandomAccessFile file = new RandomAccessFile(
                project.resolve("large.bin").toFile(), "rw")) {
            file.setLength(64L * 1024L * 1024L);
        }
        try (IsolatedWorkspace workspace =
                     IsolatedWorkspace.create(project, workspaces, "memory")) {
            if (!workspace.createPatchSet().isEmpty()) {
                throw new IllegalStateException("unchanged workspace produced patch");
            }
        }
    }
}
