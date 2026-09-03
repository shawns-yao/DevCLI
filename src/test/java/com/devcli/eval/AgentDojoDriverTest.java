package com.devcli.eval;

import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentDojoDriverTest {
    @Test
    void utilityCaseUsesExplicitNoInjectionSentinels() {
        assertTrue(AgentDojoDriver.isUtilityCase("none", "none"));
        assertFalse(AgentDojoDriver.isUtilityCase("injection_task_0", "direct"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentDojoDriver.isUtilityCase("none", "direct"));
    }

    @Test
    void acceptedModelsIncludeLunaAndTerraOnly() {
        assertTrue(AgentDojoDriver.isAcceptedModel("gpt-5.6-luna"));
        assertTrue(AgentDojoDriver.isAcceptedModel("gpt-5.6-terra"));
        assertFalse(AgentDojoDriver.isAcceptedModel("gpt-4o"));
    }

    @Test
    void parsesFinalizePayloadWithLiteralNewlineInNestedJson() throws Exception {
        String malformed = "{\"utility\":true,\"tool_calls\":[{\"args\":{\"body\":\"line one\nline two\"}}]}";
        assertTrue(AgentDojoDriver.parseToolJson(malformed).path("utility").asBoolean());
        assertEquals("line one\nline two",
                AgentDojoDriver.parseToolJson(malformed).path("tool_calls").get(0)
                        .path("args").path("body").asText());
    }

    @Test
    void onlyEnvironmentGuidanceIsExternalFailure() {
        assertTrue(AgentDojoDriver.isExternalFailureGuidance("ENVIRONMENT_FAILURE", "服务不可用"));
        assertFalse(AgentDojoDriver.isExternalFailureGuidance("EXECUTION_FAILURE", "任务未完成"));
        assertTrue(AgentDojoDriver.isExternalFailureGuidance("EXECUTION_FAILURE", "503 upstream"));
    }

    @Test
    void normalAnswerTextIsNotExternalFailure() {
        assertFalse(AgentDojoDriver.isExternalFailureText("Networking Event on May 26th"));
    }

    @Test
    void treatmentMustDeclareApprovalPolicy() {
        assertEquals("none", AgentDojoDriver.approvalPolicy("baseline", ""));
        assertEquals("terminal", AgentDojoDriver.approvalPolicy("treatment", "terminal"));
        assertEquals("auto-approve", AgentDojoDriver.approvalPolicy("treatment", "auto-approve"));
        assertThrows(IllegalArgumentException.class, () -> AgentDojoDriver.approvalPolicy("treatment", ""));
        assertThrows(IllegalArgumentException.class, () -> AgentDojoDriver.approvalPolicy("treatment", "allow-all-typo"));
    }
    @Test
    void retainedToolsStillDiscoverDeferredMcpTools() {
        try (ToolRegistry registry = new ToolRegistry()) {
            String name = "mcp__demo__lookup";
            registry.registerMcpTool(new McpToolDescriptor("demo", "lookup", name,
                    "Lookup catalog entries", JsonNodeFactory.instance.objectNode()), args -> "ok");
            AgentDojoDriver.retainBenchmarkTools(registry, new HashSet<>(Set.of(name)));
            assertTrue(registry.hasTool("search_tools"));
            assertTrue(registry.hasTool("read_tool_result"));
            assertFalse(registry.hasTool("execute_command"));
            assertFalse(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals(name)));
            registry.executeTool("search_tools", "{\"query\":\"lookup\"}");
            assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals(name)));
        }
    }

    @Test
    void existingRunDirectoryIsNeverReused(@TempDir Path root) throws Exception {
        Path run = root.resolve("batch/condition");
        AgentDojoDriver.prepareOutputDirectory(run);
        Path sentinel = run.resolve("result.json");
        Files.writeString(sentinel, "original");
        assertThrows(FileAlreadyExistsException.class, () -> AgentDojoDriver.prepareOutputDirectory(run));
        assertEquals("original", Files.readString(sentinel));
    }
}
