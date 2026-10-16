package com.devcli.tool;

import com.devcli.tool.ToolContract.ToolAccessScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 项目绑定时冻结的委派角色目录：内置定义 < 用户定义 < 项目定义。 */
public final class DelegateRoleCatalog {
    public record Role(String name, String description, ToolAccessScope capability,
                       Set<String> allowedTools, String provider, String model, String prompt) {
        public boolean writes() { return capability == ToolAccessScope.ISOLATED_PROJECT; }
        public boolean allows(String tool) { return allowedTools.contains("*") || allowedTools.contains(tool); }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LoadSettings YAML_SETTINGS = LoadSettings.builder().setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0).setCodePointLimit(64 * 1024).build();
    private final Map<String, Role> roles;

    private DelegateRoleCatalog(Map<String, Role> roles) { this.roles = Map.copyOf(roles); }

    public static DelegateRoleCatalog load(Path projectRoot) {
        return load(Path.of(System.getProperty("user.home"), ".devcli", "agents"),
                projectRoot.resolve(".devcli/agents"));
    }

    public static DelegateRoleCatalog load(Path userDirectory, Path projectDirectory) {
        Map<String, Role> roles = new LinkedHashMap<>();
        try {
            for (String name : List.of("explorer", "worker", "reviewer")) {
                try (var input = DelegateRoleCatalog.class.getResourceAsStream("/agents/" + name + ".md")) {
                    roles.put(name, parse(name, new String(input.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
            readDirectory(userDirectory, roles);
            readDirectory(projectDirectory, roles);
            return new DelegateRoleCatalog(roles);
        } catch (IOException e) {
            throw new UncheckedIOException("读取委派角色定义失败", e);
        }
    }

    private static void readDirectory(Path directory, Map<String, Role> roles) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                String filename = file.getFileName().toString();
                String name = filename.substring(0, filename.length() - 3);
                roles.put(name, parse(name, Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
    }

    static Role parse(String filename, String document) {
        String text = document.replace("\r\n", "\n");
        int end = text.indexOf("\n---\n", 4);
        if (!text.startsWith("---\n") || end < 0) throw new IllegalArgumentException(filename + ": 缺少角色 frontmatter");
        JsonNode metadata = JSON.valueToTree(new Load(YAML_SETTINGS).loadFromString(text.substring(4, end)));
        Set<String> fields = Set.of("name", "description", "capability", "allowed_tools", "provider", "model");
        if (!metadata.isObject()) throw new IllegalArgumentException(filename + ": 角色元数据必须是对象");
        metadata.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new IllegalArgumentException(filename + ": 未知字段 " + field);
        });
        String name = requiredText(metadata, "name");
        if (!name.matches("[a-z][a-z0-9-]{0,63}") || !name.equals(filename)) {
            throw new IllegalArgumentException(filename + ": name 必须与文件名一致，仅允许小写字母、数字和连字符");
        }
        String declared = requiredText(metadata, "capability");
        if (!Set.of("READ_ONLY", "ISOLATED_PROJECT").contains(declared)) {
            throw new IllegalArgumentException(filename + ": capability 仅允许 READ_ONLY 或 ISOLATED_PROJECT");
        }
        JsonNode tools = metadata.path("allowed_tools");
        if (!tools.isArray() || tools.isEmpty()) throw new IllegalArgumentException(filename + ": allowed_tools 必须是非空数组");
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (JsonNode tool : tools) {
            if (!tool.isTextual() || tool.asText().isBlank()) throw new IllegalArgumentException(filename + ": 工具名必须是非空字符串");
            allowed.add(tool.asText());
        }
        String prompt = text.substring(end + 5).trim();
        if (prompt.isBlank()) throw new IllegalArgumentException(filename + ": 缺少角色提示词正文");
        return new Role(name, requiredText(metadata, "description"), ToolAccessScope.valueOf(declared),
                Set.copyOf(allowed), optionalText(metadata, "provider"), optionalText(metadata, "model"), prompt);
    }

    private static String requiredText(JsonNode metadata, String field) {
        String value = optionalText(metadata, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("角色字段不能为空: " + field);
        return value;
    }

    private static String optionalText(JsonNode metadata, String field) {
        JsonNode value = metadata.get(field);
        if (value == null) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("角色字段必须是字符串: " + field);
        return value.asText();
    }

    public Role require(String name) {
        Role role = roles.get(name);
        if (role == null) throw new IllegalArgumentException("未知委派角色: " + name);
        return role;
    }

    public List<Role> roles() { return roles.values().stream().sorted(java.util.Comparator.comparing(Role::name)).toList(); }
}
