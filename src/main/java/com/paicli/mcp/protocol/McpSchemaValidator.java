package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * MCP tool arguments validator for the JSON-schema subset PaiCLI sends to LLMs.
 */
public final class McpSchemaValidator {
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    private McpSchemaValidator() {
    }

    public static ValidationResult validate(JsonNode schema, JsonNode arguments) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return ValidationResult.ok();
        }
        JsonNode sanitized = McpSchemaSanitizer.sanitize(schema);
        List<String> errors = new ArrayList<>();
        validateWithJsonSchemaLibrary(sanitized, arguments, errors);
        validateObject(sanitized, arguments, "$", errors);
        if (errors.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.invalid(String.join("; ", errors));
    }

    private static void validateWithJsonSchemaLibrary(JsonNode schema, JsonNode arguments, List<String> errors) {
        try {
            JsonSchema jsonSchema = SCHEMA_FACTORY.getSchema(schema);
            Set<ValidationMessage> messages = jsonSchema.validate(arguments);
            for (ValidationMessage message : messages) {
                String formatted = formatLibraryMessage(message);
                if (!formatted.isBlank() && !errors.contains(formatted)) {
                    errors.add(formatted);
                }
            }
        } catch (Exception e) {
            // 第三方 MCP schema 可能包含库暂不支持的方言；本地轻量校验继续兜底，不因 schema 方言阻断工具。
        }
    }

    private static String formatLibraryMessage(ValidationMessage message) {
        if (message == null) {
            return "";
        }
        String path = message.getInstanceLocation() == null ? "$" : message.getInstanceLocation().toString();
        if (path.isBlank() || "/".equals(path)) {
            path = "$";
        } else if (path.startsWith("$")) {
            // keep as-is
        } else {
            path = "$" + path.replace('/', '.');
        }
        String keyword = message.getType();
        Object[] arguments = message.getArguments();
        String details = arguments == null || arguments.length == 0
                ? message.getMessage()
                : Arrays.stream(arguments).map(String::valueOf).collect(Collectors.joining(", "));
        if (keyword == null || keyword.isBlank()) {
            return path + " " + details;
        }
        if (details == null || details.isBlank()) {
            return path + " violates " + keyword;
        }
        return path + " violates " + keyword + ": " + details;
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
        if (schema.path("additionalProperties").isBoolean()
                && !schema.path("additionalProperties").asBoolean()) {
            Iterator<String> fieldNames = value.fieldNames();
            while (fieldNames.hasNext()) {
                String actualName = fieldNames.next();
                if (!properties.has(actualName)) {
                    errors.add(path + "." + actualName + " is not allowed");
                }
            }
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
            case "string" -> {
                require(value.isTextual(), path, "string", errors);
                if (value.isTextual()) {
                    validateStringConstraints(schema, value.asText(), path, errors);
                }
            }
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
        validateEnum(schema, value, path, errors);
    }

    private static void validateStringConstraints(JsonNode schema, String value, String path, List<String> errors) {
        JsonNode minLength = schema.path("minLength");
        if (minLength.isIntegralNumber() && minLength.asInt() >= 1 && value.trim().isEmpty()) {
            errors.add(path + " must not be blank");
        }
    }

    private static void validateEnum(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode enumNode = schema.path("enum");
        if (!enumNode.isArray() || enumNode.isEmpty()) {
            return;
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode item : enumNode) {
            if (item.isTextual() || item.isNumber() || item.isBoolean()) {
                allowed.add(item.asText());
            }
        }
        if (allowed.isEmpty()) {
            return;
        }
        String actual = value.isTextual() || value.isNumber() || value.isBoolean() ? value.asText() : value.toString();
        if (!allowed.contains(actual)) {
            errors.add(path + " must be one of [" + String.join(", ", allowed) + "]");
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
