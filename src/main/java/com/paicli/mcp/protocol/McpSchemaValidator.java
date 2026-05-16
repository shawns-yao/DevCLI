package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MCP tool arguments validator for the JSON-schema subset PaiCLI sends to LLMs.
 */
public final class McpSchemaValidator {

    private McpSchemaValidator() {
    }

    public static ValidationResult validate(JsonNode schema, JsonNode arguments) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return ValidationResult.ok();
        }
        JsonNode sanitized = McpSchemaSanitizer.sanitize(schema);
        List<String> errors = new ArrayList<>();
        validateObject(sanitized, arguments, "$", errors);
        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.invalid(String.join("; ", errors));
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (!value.isObject()) {
            errors.add(path + " must be object");
            return;
        }

        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode requiredName : required) {
                if (requiredName.isTextual() && !value.has(requiredName.asText())) {
                    errors.add(path + "." + requiredName.asText() + " is required");
                }
            }
        }

        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (!value.has(name)) {
                continue;
            }
            validateType(field.getValue(), value.get(name), path + "." + name, errors);
        }
    }

    private static void validateType(JsonNode schema, JsonNode value, String path, List<String> errors) {
        String type = schema.path("type").asText("");
        if (type.isBlank()) {
            return;
        }
        switch (type) {
            case "string" -> require(value.isTextual(), path, "string", errors);
            case "number" -> require(value.isNumber(), path, "number", errors);
            case "integer" -> require(value.isIntegralNumber(), path, "integer", errors);
            case "boolean" -> require(value.isBoolean(), path, "boolean", errors);
            case "array" -> require(value.isArray(), path, "array", errors);
            case "object" -> {
                if (!value.isObject()) {
                    errors.add(path + " must be object");
                    return;
                }
                validateObject(schema, value, path, errors);
            }
            default -> {
                // Unknown schema keywords from third-party MCP servers are treated as hints.
            }
        }
    }

    private static void require(boolean condition, String path, String expected, List<String> errors) {
        if (!condition) {
            errors.add(path + " must be " + expected);
        }
    }

    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message == null ? "" : message);
        }
    }
}
