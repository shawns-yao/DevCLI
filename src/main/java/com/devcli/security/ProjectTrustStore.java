package com.devcli.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** 项目信任只控制项目可执行资源加载，不提升工具权限。 */
public final class ProjectTrustStore {
    public enum Trust { TRUSTED, UNTRUSTED, UNKNOWN }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path file;

    public ProjectTrustStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public static ProjectTrustStore defaultStore() {
        return new ProjectTrustStore(Path.of(
                System.getProperty("user.home"), ".devcli", "project-trust.json"));
    }

    public synchronized Trust resolve(Path projectRoot, boolean interactive) {
        String override = System.getProperty("devcli.project.trust");
        if (override == null || override.isBlank()) override = System.getenv("DEVCLI_PROJECT_TRUST");
        if (override != null && !override.isBlank()) {
            if ("trusted".equalsIgnoreCase(override) || "true".equalsIgnoreCase(override)) {
                return Trust.TRUSTED;
            }
            if ("untrusted".equalsIgnoreCase(override) || "false".equalsIgnoreCase(override)) {
                return Trust.UNTRUSTED;
            }
        }
        Trust stored = read().getOrDefault(key(projectRoot), Trust.UNKNOWN);
        return stored == Trust.UNKNOWN && !interactive ? Trust.UNTRUSTED : stored;
    }

    public synchronized void set(Path projectRoot, Trust trust) throws IOException {
        Map<String, Trust> entries = new LinkedHashMap<>(read());
        entries.put(key(projectRoot), trust == null ? Trust.UNTRUSTED : trust);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entries);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, Trust> read() {
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            Map<String, Trust> result = new LinkedHashMap<>();
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    try {
                        result.put(entry.getKey(), Trust.valueOf(entry.getValue().asText()));
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String key(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().toString();
    }
}
