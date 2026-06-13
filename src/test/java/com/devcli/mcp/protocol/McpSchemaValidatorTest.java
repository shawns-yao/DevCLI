package com.devcli.mcp.protocol;

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
                MAPPER.readTree("{\"query\":\"devcli\",\"limit\":3}")
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

    @Test
    void rejectsEnumMismatch() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","properties":{"mode":{"type":"string","enum":["auto","general"]}}}
                        """),
                MAPPER.readTree("{\"mode\":\"unknown\"}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.mode must be one of [auto, general]"));
    }

    @Test
    void rejectsUnexpectedPropertiesWhenAdditionalPropertiesIsFalse() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","additionalProperties":false,"properties":{"query":{"type":"string"}}}
                        """),
                MAPPER.readTree("{\"query\":\"devcli\",\"extra\":\"hallucinated\"}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.extra is not allowed"));
    }

    @Test
    void rejectsBlankRequiredStringWhenMinLengthIsSet() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","properties":{"query":{"type":"string","minLength":1}}}
                        """),
                MAPPER.readTree("{\"query\":\"   \"}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.query must not be blank"));
    }

    @Test
    void rejectsStringPatternMismatch() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","properties":{"path":{"type":"string","pattern":"^src/.+\\\\.java$"}}}
                        """),
                MAPPER.readTree("{\"path\":\"README.md\"}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.path"));
        assertTrue(result.message().contains("pattern"));
    }

    @Test
    void rejectsIntegerBelowMinimum() throws Exception {
        McpSchemaValidator.ValidationResult result = McpSchemaValidator.validate(
                MAPPER.readTree("""
                        {"type":"object","properties":{"top_k":{"type":"integer","minimum":1}}}
                        """),
                MAPPER.readTree("{\"top_k\":0}")
        );

        assertFalse(result.valid());
        assertTrue(result.message().contains("$.top_k"));
        assertTrue(result.message().contains("minimum"));
    }
}
