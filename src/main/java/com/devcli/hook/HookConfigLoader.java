package com.devcli.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.devcli.security.ProjectTrustStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 加载用户级与项目级 Hook 配置，项目同 id 定义覆盖用户级。 */
public final class HookConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(HookConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HOOKS = 64;

    private HookConfigLoader() {
    }

    public static List<HookDefinition> load(Path projectRoot) {
        boolean trusted = ProjectTrustStore.defaultStore().resolve(
                projectRoot == null ? Path.of(".") : projectRoot, false)
                == ProjectTrustStore.Trust.TRUSTED;
        return load(projectRoot, trusted);
    }

    public static List<HookDefinition> load(Path projectRoot, boolean includeProjectHooks) {
        String configured = System.getProperty("devcli.hooks.file");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_HOOKS_FILE");
        }
        if (configured != null && !configured.isBlank()) {
            return loadFiles(List.of(Path.of(configured.trim())));
        }
        Path normalizedProject = projectRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        files.add(Path.of(System.getProperty("user.home"), ".devcli", "hooks.json"));
        if (includeProjectHooks) {
            files.add(normalizedProject.resolve(".devcli").resolve("hooks.json"));
        }
        return loadFiles(files);
    }

    static List<HookDefinition> loadFiles(List<Path> files) {
        Map<String, HookDefinition> merged = new LinkedHashMap<>();
        if (files == null) return List.of();
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) continue;
            try {
                Map<String, HookDefinition> candidate = new LinkedHashMap<>(merged);
                for (HookDefinition hook : parse(file)) {
                    candidate.put(hook.id(), hook);
                }
                if (candidate.size() > MAX_HOOKS) {
                    throw new IllegalArgumentException("Hook 数量超过上限 " + MAX_HOOKS);
                }
                merged = candidate;
            } catch (Exception e) {
                log.warn("忽略无效 Hook 配置: file={}, error={}", file, e.getMessage());
            }
        }
        return List.copyOf(merged.values());
    }

    private static List<HookDefinition> parse(Path file) throws Exception {
        JsonNode root = MAPPER.readTree(file.toFile());
        int schemaVersion = root == null ? 1 : root.path("schemaVersion").asInt(1);
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("不支持的 Hook schemaVersion: " + schemaVersion);
        }
        JsonNode hooks = root == null ? null : root.path("hooks");
        if (hooks == null || !hooks.isArray()) {
            throw new IllegalArgumentException("hooks 必须是数组");
        }
        List<HookDefinition> definitions = new ArrayList<>();
        for (int index = 0; index < hooks.size(); index++) {
            JsonNode item = hooks.get(index);
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException("hooks[" + index + "] 必须是对象");
            }
            definitions.add(new HookDefinition(
                    text(item, "id"),
                    item.path("name").asText(""),
                    HookEvent.parse(text(item, "event")),
                    item.path("enabled").asBoolean(true),
                    text(item, "tool"),
                    item.has("arguments") ? item.get("arguments") : MAPPER.createObjectNode(),
                    HookDefinition.FailureMode.parse(item.path("failureMode").asText("warn")),
                    item.path("allowSideEffects").asBoolean(false)));
        }
        return definitions;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
