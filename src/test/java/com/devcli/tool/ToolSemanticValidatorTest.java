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

    @Test
    void rejectsDelegationWithBlankContractFieldBeforeAdmission() {
        // 契约字段的非空由工具 schema 承担，错误码是参数错误而非策略拒绝，
        // 模型据此能区分「参数写错了」与「这个任务不该委派」。详见 ADR 0010。
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput("delegate_task",
                    "{\"role\":\"explorer\",\"task\":\"Inspect\",\"deliverable\":\"Findings\","
                            + "\"task_spec\":{\"execution_kind\":\"agent_loop\",\"inputs\":\"Fixture\","
                            + "\"scope\":\" \",\"done_condition\":\"Report\"}}");

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, output.errorCode());
            assertTrue(output.text().contains("scope"), output.text());
        }
    }

    @Test
    void rejectsWorkerDelegationWithoutWriteScope() {
        // worker 写入范围是隔离工作区与运行时租约的必要输入。该字段对 worker 是条件必填，
        // schema 无法表达，因此由本层承担；它属于结构校验而非准入判据。详见 ADR 0010。
        try (ToolRegistry registry = new ToolRegistry()) {
            ToolOutput output = registry.executeToolOutput("delegate_task",
                    "{\"role\":\"worker\",\"task\":\"Edit one file\",\"deliverable\":\"Patch\","
                            + "\"task_spec\":{\"execution_kind\":\"agent_loop\",\"inputs\":\"Fixture\","
                            + "\"scope\":\"One file\",\"done_condition\":\"Report\"}}");

            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, output.errorCode());
            assertTrue(output.text().contains("allowed_write_paths"), output.text());
        }
    }
}
