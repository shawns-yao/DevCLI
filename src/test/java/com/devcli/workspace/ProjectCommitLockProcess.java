package com.devcli.workspace;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectCommitLockProcess {
    private ProjectCommitLockProcess() {
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]);
        Path marker = Path.of(args[1]);
        ProjectCommitCoordinator.withProjectLock(project, () -> {
            Files.writeString(marker, "acquired");
            return null;
        });
    }
}
