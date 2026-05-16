package com.paicli.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSchemaValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsValidRequiredArguments() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","required":["query"],"properties":{"query":{"type":"string"},"limit":{"type":"integer"}}}
                        """),
                MAPPER.readTree("{\"query\":\"paicli\",\"limit\":3}")
        );

        assertTrue(result.valid());
    }

    @Test
    void rejectsMissingRequiredArguments() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","required":["query"],"properties":{"query":{"type":"string"}}}
                        """),
                MAPPER.readTree("{}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.query is required"));
    }

    @Test
    void rejectsTypeMismatch() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","properties":{"limit":{"type":"integer"},"enabled":{"type":"boolean"}}}
                        """),
                MAPPER.readTree("{\"limit\":\"3\",\"enabled\":\"true\"}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.limit must be integer"));
        assertTrue(result.message().contains("$.enabled must be boolean"));
    }
}
