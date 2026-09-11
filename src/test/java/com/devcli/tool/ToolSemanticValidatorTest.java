package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSemanticValidatorTest {
    @Test
    void rejectsCrossFieldReadRangeBeforeProvider(@TempDir Path projectRoot) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());
            Files.writeString(projectRoot.resolve("README.md"), "content");

            ToolOutput output = registry.executeToolOutput("read_file",
                    "{\"path\":\"README.md\",\"start_line\":4,\"end_line\":2}");

            assertEquals(ToolErrorCode.SEMANTIC_VALIDATION_FAILED, output.errorCode());
            assertTrue(output.text().contains("end_line"), output.text());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rejectsMissingEditTargetBeforeHitl(@TempDir Path projectRoot) {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(projectRoot.toString());

            ToolOutput output = registry.executeToolOutput("edit_file",
                    "{\"path\":\"missing.txt\",\"old_string\":\"x\",\"new_string\":\"y\"}");

            assertEquals(ToolErrorCode.SEMANTIC_VALIDATION_FAILED, output.errorCode());
            assertTrue(output.text().contains("已存在"), output.text());
        }
    }

    @Test
    void rejectsCommandPolicyBeforeExecution() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput("execute_command",
                    "{\"command\":\"sudo rm -rf /\"}");

            assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode());
            assertTrue(output.text().contains("策略拒绝"), output.text());
        }
    }

    @Test
    void rejectsSchemaValidButBusinessInvalidSnapshotOffset() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput("revert_turn", "{\"offset\":0}");

            assertEquals(ToolErrorCode.SEMANTIC_VALIDATION_FAILED, output.errorCode());
            assertTrue(output.text().contains("offset"), output.text());
        }
    }

    @Test
    void customRuleValidatesBusinessPayloadAfterSchema() {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "lookup_user", "lookup", new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree("{\"type\":\"object\",\"required\":[\"user_id\"],\"properties\":{\"user_id\":{\"type\":\"string\"}}}"),
                    args -> "should not run"));
            registry.registerSemanticValidator("lookup_user", arguments ->
                    "missing".equals(arguments.path("user_id").asText())
                            ? ToolSemanticValidator.ValidationResult.semantic("user_id 不存在")
                            : ToolSemanticValidator.ValidationResult.ok());

            ToolOutput output = registry.executeToolOutput("lookup_user", "{\"user_id\":\"missing\"}");

            assertEquals(ToolErrorCode.SEMANTIC_VALIDATION_FAILED, output.errorCode());
            assertTrue(output.text().contains("user_id 不存在"), output.text());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
