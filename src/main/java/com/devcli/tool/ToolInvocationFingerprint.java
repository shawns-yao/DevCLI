package com.devcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ToolInvocationFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CASE_INSENSITIVE_FIELDS = Set.of(
            "query", "keyword", "mode", "provider", "server", "language");
    private static final Set<String> PATH_FIELDS = Set.of(
            "path", "file", "directory", "project_path", "cwd");

    private ToolInvocationFingerprint() {
    }

    public static String of(String toolName, String argumentsJson) {
        String normalizedName = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        try {
            JsonNode root = JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
            return normalizedName + "|" + JSON.writeValueAsString(canonicalize(root, ""));
        } catch (Exception ignored) {
            String fallback = argumentsJson == null ? "{}" : argumentsJson.replaceAll("\\s+", " ").trim();
            return normalizedName + "|" + fallback;
        }
    }

    private static String normalizePath(String value) {
        try {
            return java.nio.file.Path.of(value).normalize().toString().replace('\\', '/');
        } catch (Exception ignored) {
            return value.replace('\\', '/').replaceAll("/\\./", "/");
        }
    }

    private static JsonNode canonicalize(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) return JSON.nullNode();
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                result.set(name, canonicalize(node.get(name), name.toLowerCase(Locale.ROOT)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (JsonNode item : node) result.add(canonicalize(item, fieldName));
            return result;
        }
        if (node.isTextual()) {
            String value = Normalizer.normalize(node.asText(), Normalizer.Form.NFKC)
                    .replaceAll("\\s+", " ").trim();
            if (PATH_FIELDS.contains(fieldName)) {
                value = normalizePath(value);
            }
            if (CASE_INSENSITIVE_FIELDS.contains(fieldName)) {
                value = value.toLowerCase(Locale.ROOT);
            }
            return TextNode.valueOf(value);
        }
        return node;
    }
}
