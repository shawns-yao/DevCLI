package com.devcli.workspace;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

final class WorkspaceBackendFactory {
    static final String BACKEND_PROPERTY = "devcli.workspace.backend";
    static final String BACKEND_ENV = "DEVCLI_WORKSPACE_BACKEND";

    private WorkspaceBackendFactory() {
    }

    static WorkspaceBackend create(Path projectRoot) {
        String mode = resolveMode(System.getProperties(), System.getenv());
        return switch (mode) {
            case "copy" -> new CopyWorkspaceBackend();
            case "cow" -> new FileSystemCowWorkspaceBackend();
            case "git" -> new GitWorktreeBackend();
            case "auto" -> GitWorktreeBackend.supports(projectRoot)
                    ? new GitWorktreeBackend()
                    : new FileSystemCowWorkspaceBackend();
            default -> throw new IllegalArgumentException("unsupported workspace backend: " + mode);
        };
    }

    static String resolveMode(Properties properties, Map<String, String> environment) {
        String value = properties == null ? null : properties.getProperty(BACKEND_PROPERTY);
        if (value == null || value.isBlank()) {
            value = environment == null ? null : environment.get(BACKEND_ENV);
        }
        return value == null || value.isBlank()
                ? "auto"
                : value.trim().toLowerCase(Locale.ROOT);
    }

}
