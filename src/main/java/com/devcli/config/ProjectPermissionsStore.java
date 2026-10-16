package com.devcli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** 用户侧项目授权：项目自身的文件不能声明自身可信。 */
public final class ProjectPermissionsStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final ObjectNode projects;

    public ProjectPermissionsStore(Path file) throws IOException {
        this.file = file;
        projects = Files.exists(file) ? JSON.readValue(file.toFile(), ObjectNode.class) : JSON.createObjectNode();
    }

    public static ProjectPermissionsStore openDefault() throws IOException {
        return new ProjectPermissionsStore(Path.of(System.getProperty("user.home"), ".devcli", "project-permissions.json"));
    }

    public static String key(Path project) throws IOException {
        return project.toRealPath().toString();
    }

    public synchronized boolean hasDecision(String project) { return projects.has(project); }
    public synchronized boolean isTrusted(String project) { return projects.path(project).path("trusted").asBoolean(); }

    public synchronized List<String> allowRules(String project) {
        List<String> rules = new ArrayList<>();
        projects.path(project).path("permissions").path("allow").forEach(rule -> rules.add(rule.asText()));
        return List.copyOf(rules);
    }

    public synchronized void decideTrust(String project, boolean trusted) throws IOException {
        ObjectNode updated = projects.deepCopy();
        updated.withObjectProperty(project).put("trusted", trusted);
        save(updated);
    }

    public synchronized void allow(String project, String rule) throws IOException {
        ObjectNode updated = projects.deepCopy();
        var allow = updated.withObjectProperty(project).withObjectProperty("permissions").withArray("allow");
        if (allowRules(project).contains(rule)) return;
        allow.add(rule);
        save(updated);
    }

    private void save(ObjectNode updated) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "project-permissions-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), updated);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            projects.removeAll();
            projects.setAll(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
