package com.devcli.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Skill 启用状态持久化。
 *
 * 设计：仅持久化 disabled 列表，启用为隐式默认——这样新加的 skill 不会被遗漏。
 *
 * 文件不存在或解析失败一律视为空 disabled，并在 stderr 警告，不阻塞主流程。
 */
public final class SkillStateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public SkillStateStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized Set<String> disabled() {
        return readTextSet("disabled");
    }

    public synchronized boolean isProjectDirectoryTrusted(Path directory) {
        return trustedProjectDirectoryFingerprints().contains(projectDirectoryFingerprint(directory));
    }

    public synchronized void trustProjectDirectory(Path directory) {
        updateProjectDirectoryTrust(directory, true);
    }

    public synchronized void untrustProjectDirectory(Path directory) {
        updateProjectDirectoryTrust(directory, false);
    }

    public synchronized Set<String> trustedProjectDirectoryFingerprints() {
        return readTextSet("trustedProjectDirectories");
    }

    private Set<String> readTextSet(String field) {
        if (!Files.exists(file)) {
            return Set.of();
        }
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return Set.of();
            }
            ObjectNode root = (ObjectNode) MAPPER.readTree(content);
            Set<String> result = new LinkedHashSet<>();
            if (root.has(field) && root.get(field).isArray()) {
                root.get(field).forEach(node -> {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        result.add(node.asText());
                    }
                });
            }
            return result;
        } catch (Exception e) {
            System.err.println("⚠️ skills.json 解析失败，忽略禁用列表: " + e.getMessage());
            return Set.of();
        }
    }

    public synchronized void disable(String name) {
        Set<String> set = new LinkedHashSet<>(disabled());
        set.add(name);
        write(set, trustedProjectDirectoryFingerprints());
    }

    public synchronized void enable(String name) {
        Set<String> set = new LinkedHashSet<>(disabled());
        set.remove(name);
        write(set, trustedProjectDirectoryFingerprints());
    }

    private void updateProjectDirectoryTrust(Path directory, boolean trusted) {
        String fingerprint = projectDirectoryFingerprint(directory);
        Set<String> trustedDirectories = new LinkedHashSet<>(trustedProjectDirectoryFingerprints());
        if (trusted) {
            trustedDirectories.add(fingerprint);
        } else {
            trustedDirectories.remove(fingerprint);
        }
        write(disabled(), trustedDirectories);
    }

    private void write(Set<String> disabled, Set<String> trustedProjectDirectories) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.putPOJO("disabled", disabled);
            root.putPOJO("trustedProjectDirectories", trustedProjectDirectories);
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("⚠️ skills.json 写入失败: " + e.getMessage());
        }
    }

    private static String projectDirectoryFingerprint(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("project skill directory 不能为空");
        }
        Path canonical;
        try {
            canonical = directory.toRealPath();
        } catch (IOException ignored) {
            canonical = directory.toAbsolutePath().normalize();
        }
        String normalized = canonical.toString().replace('\\', '/');
        if (isWindows()) {
            normalized = normalized.toLowerCase(java.util.Locale.ROOT);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
