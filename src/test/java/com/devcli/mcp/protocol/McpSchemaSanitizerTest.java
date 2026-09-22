package com.devcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpSchemaSanitizerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void removesUnsupportedJsonSchemaFields() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "$id": "tool",
                  "$ref": "#/$defs/X",
                  "type": "object",
                  "properties": {
                    "path": {"type": "string", "$ref": "#/$defs/Path"}
                  }
                }
                """);

        JsonNode cleaned = McpSchemaSanitizer.sanitize(raw);

        assertFalse(cleaned.has("$schema"));
        assertFalse(cleaned.has("$id"));
        assertFalse(cleaned.has("$ref"));
        assertFalse(cleaned.path("properties").path("path").has("$ref"));
    }

    @Test
    void preservesUnionConstraintsInsteadOfInventingObjectType() throws Exception {
        JsonNode raw = MAPPER.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "value": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                    "count": {"oneOf": [{"type": "integer"}, {"type": "string"}]}
                  }
                }
                """);

        JsonNode cleaned = McpSchemaSanitizer.sanitize(raw);

        assertEquals(raw.path("properties"), cleaned.path("properties"));
        assertFalse(cleaned.path("properties").path("value").has("type"));
    }

    @Test
    void truncatesLongDescriptions() throws Exception {
        String longText = "x".repeat(1200);
        JsonNode raw = MAPPER.readTree("""
                {"type":"object","description":"%s","properties":{}}
                """.formatted(longText));

        JsonNode cleaned = McpSchemaSanitizer.sanitize(raw);

        assertTrue(cleaned.path("description").asText().endsWith("..."));
        assertTrue(cleaned.path("description").asText().length() < longText.length());
    }
}
