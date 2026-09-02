package com.devcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

public final class McpSchemaSanitizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DESCRIPTION_CHARS = 1000;

    private McpSchemaSanitizer() {
    }

    public static JsonNode sanitize(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.putObject("properties");
            return fallback;
        }
        JsonNode copy = schema.deepCopy();
        JsonNode cleaned = clean(copy);
        if (!cleaned.isObject()) {
            ObjectNode fallback = MAPPER.createObjectNode();
            fallback.put("type", "object");
            fallback.set("description", cleaned);
            return fallback;
        }
        ObjectNode obj = (ObjectNode) cleaned;
        if (!obj.has("type")) {
            obj.put("type", "object");
        }
        if (!obj.has("properties")) {
            obj.putObject("properties");
        }
        return obj;
    }

    private static JsonNode clean(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.remove("$schema");
            object.remove("$id");
            object.remove("$ref");

            // Preserve unions: nullable scalar fields must not become object-only parameters.

            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if ("description".equals(field.getKey()) && child.isTextual()) {
                    object.put("description", truncateDescription(child.asText()));
                } else {
                    clean(child);
                }
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (JsonNode child : array) {
                clean(child);
            }
        }
        return node;
    }

    private static String truncateDescription(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_CHARS) {
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_CHARS) + "...";
    }
}
