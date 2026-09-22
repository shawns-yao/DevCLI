package com.devcli.skill;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用受限 YAML 1.2 解析并强校验 SKILL.md frontmatter。 */
public final class SkillFrontmatterParser {
    private static final int MAX_FRONTMATTER_CODE_POINTS = 64 * 1024;
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "name", "description", "version", "author", "tags", "allowedTools",
            "context", "paths", "requiresTools", "requiresMcp", "dependsOn");
    private static final Set<String> LIST_FIELDS = Set.of(
            "tags", "allowedTools", "paths", "requiresTools", "requiresMcp", "dependsOn");
    private static final Set<String> STRING_FIELDS = Set.of(
            "name", "description", "version", "author", "context");
    private static final Load YAML = new Load(LoadSettings.builder()
            .setLabel("SKILL.md frontmatter")
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(MAX_FRONTMATTER_CODE_POINTS)
            .build());

    public record ParseResult(Map<String, Object> frontmatter, String body,
                              List<String> warnings, boolean valid) {
    }

    private SkillFrontmatterParser() {
    }

    public static ParseResult parse(String fullText) {
        if (fullText == null) {
            return invalid("", "SKILL.md 内容为 null");
        }
        String normalized = fullText.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith("---\n")) {
            return invalid(normalized, "缺少 frontmatter 起始标记 ---");
        }
        int endIdx = findFrontmatterEnd(normalized);
        if (endIdx < 0) {
            return invalid(normalized, "缺少 frontmatter 结束标记 ---");
        }
        String yamlText = normalized.substring(4, endIdx);
        String body = normalized.substring(endIdx + 4);
        if (body.startsWith("\n")) body = body.substring(1);

        List<String> warnings = new ArrayList<>();
        Object loaded;
        try {
            loaded = YAML.loadFromString(yamlText);
        } catch (RuntimeException e) {
            warnings.add("frontmatter YAML 无效: " + oneLine(e.getMessage()));
            return new ParseResult(Map.of(), body, List.copyOf(warnings), false);
        }
        if (!(loaded instanceof Map<?, ?> source)) {
            warnings.add("frontmatter 必须是对象");
            return new ParseResult(Map.of(), body, List.copyOf(warnings), false);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !ALLOWED_FIELDS.contains(key)) {
                warnings.add("frontmatter 包含不支持的字段: " + entry.getKey());
                continue;
            }
            Object value = entry.getValue();
            if (STRING_FIELDS.contains(key)) {
                if (!(value instanceof String text)) {
                    warnings.add("frontmatter 字段 '" + key + "' 必须是字符串");
                } else {
                    fields.put(key, text);
                }
            } else if (LIST_FIELDS.contains(key)) {
                List<String> list = stringList(value, key, warnings);
                if (list != null) fields.put(key, list);
            }
        }
        validateSchema(fields, warnings);
        return new ParseResult(Map.copyOf(fields), body,
                List.copyOf(warnings), warnings.isEmpty());
    }

    private static List<String> stringList(Object value, String key, List<String> warnings) {
        if (!(value instanceof List<?> values)) {
            warnings.add("frontmatter 字段 '" + key + "' 必须是字符串数组");
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                warnings.add("frontmatter 字段 '" + key + "' 只能包含非空字符串");
                return null;
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static void validateSchema(Map<String, Object> fields, List<String> warnings) {
        String name = string(fields.get("name"));
        String description = string(fields.get("description"));
        if (name.isBlank()) {
            warnings.add("frontmatter 缺少必填字段 name");
        } else if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            warnings.add("frontmatter name 必须使用 kebab-case");
        }
        if (description.isBlank()) {
            warnings.add("frontmatter 缺少必填字段 description");
        }
        String context = string(fields.get("context"));
        if (!context.isBlank() && !"inline".equalsIgnoreCase(context) && !"fork".equalsIgnoreCase(context)) {
            warnings.add("frontmatter context 只能是 inline 或 fork");
        }
    }

    private static String string(Object value) {
        return value instanceof String text ? text.trim() : "";
    }

    private static ParseResult invalid(String body, String warning) {
        return new ParseResult(Map.of(), body, List.of(warning), false);
    }

    private static int findFrontmatterEnd(String text) {
        int idx = 4;
        while (idx < text.length()) {
            int lineEnd = text.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = text.length();
            if (text.substring(idx, lineEnd).equals("---")) return idx;
            if (lineEnd == text.length()) break;
            idx = lineEnd + 1;
        }
        return -1;
    }

    private static String oneLine(String value) {
        if (value == null) return "unknown error";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "..." : compact;
    }
}
