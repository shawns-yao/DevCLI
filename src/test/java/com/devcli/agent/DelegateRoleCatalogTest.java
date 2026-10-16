package com.devcli.agent;

import com.devcli.tool.DelegateRoleCatalog;

import com.devcli.event.RunEventSink;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.devcli.agent.AgentDelegationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DelegateRoleCatalogTest {
    @TempDir Path project;

    private static String definition(String name, String capability, String tools, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\ncapability: " + capability
                + "\nallowed_tools: " + tools + "\n---\n只完成明确的任务，不扩大范围。";
    }

    @Test
    void projectDefinitionsOverrideUserDefinitionsAndPlannerIsOptional() throws Exception {
        Path user = Files.createDirectories(project.resolve("user"));
        Path local = Files.createDirectories(project.resolve("local"));
        Files.writeString(user.resolve("planner.md"), definition("planner", "READ_ONLY", "[read_file]", "用户规划建议"));
        Files.writeString(local.resolve("planner.md"), definition("planner", "READ_ONLY", "[list_dir]", "项目规划建议"));
        var defaults = DelegateRoleCatalog.load(project.resolve("empty-user"), project.resolve("empty-project"));
        assertEquals(java.util.List.of("explorer", "reviewer", "worker"), defaults.roles().stream().map(DelegateRoleCatalog.Role::name).toList());
        var role = DelegateRoleCatalog.load(user, local).require("planner");
        assertEquals("项目规划建议", role.description());
        assertEquals(java.util.Set.of("list_dir"), role.allowedTools());
        Files.writeString(local.resolve("admin.md"), definition("admin", "FULL", "[\"*\"]", "越权"));
        assertThrows(IllegalArgumentException.class, () -> DelegateRoleCatalog.load(user, local));
    }

    @Test
    void customWriterUsesIsolationAndRejectsBackgroundBeforeCallingModel() throws Exception {
        Path definitions = Files.createDirectories(project.resolve(".devcli/agents"));
        Files.writeString(definitions.resolve("formatter.md"), definition("formatter", "ISOLATED_PROJECT", "[write_file]", "只做机械格式修改"));
        var client = new ScriptedClient(call("write_file", "{\"path\":\"result.txt\",\"content\":\"formatted\"}"), answer("done"));
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(project.toString());
            registry.removeTool("search_code");
            var session = new DelegationSession(registry, ignored -> client, new AgentBudget(10000, 3, 20), "stable", RunEventSink.NO_OP);
            var denied = session.execute(brief("role", "formatter", "task", "format", "run_in_background", "true"), ToolExecutionContext.current("bg"));
            assertEquals(ToolErrorCode.POLICY_DENIED, denied.errorCode());
            assertTrue(client.requests.isEmpty());
            var done = session.execute(brief("role", "formatter", "task", "format"), ToolExecutionContext.current("sync"));
            assertTrue(done.isSuccess(), done.text());
            assertEquals("formatted", Files.readString(project.resolve("result.txt")));
            assertTrue(client.tools.getFirst().stream().allMatch(tool -> tool.name().equals("write_file")));
            registry.runWithDelegation(session, () -> {
                assertTrue(registry.getToolDefinitions().stream().filter(tool -> tool.name().equals("delegate_task"))
                        .findFirst().orElseThrow().description().contains("只做机械格式修改"));
                return null;
            });
        }
    }

    @Test
    void roleNameCannotGrantWritePermission() throws Exception {
        Path definitions = Files.createDirectories(project.resolve(".devcli/agents"));
        Files.writeString(definitions.resolve("worker.md"), definition("worker", "READ_ONLY", "[\"*\"]", "只读 worker"));
        var client = new ScriptedClient(call("write_file", "{\"path\":\"forbidden.txt\",\"content\":\"no\"}"), answer("done"));
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.setProjectPath(project.toString());
            var output = new DelegationSession(registry, ignored -> client, new AgentBudget(10000, 3, 20), "stable", RunEventSink.NO_OP)
                    .execute(brief("role", "worker", "task", "inspect"), ToolExecutionContext.current("readonly"));
            assertTrue(output.text().contains("CAPABILITY_DENIED"), output.text());
            assertFalse(Files.exists(project.resolve("forbidden.txt")));
            assertThrows(com.devcli.policy.PolicyException.class, () -> registry.resolveSafeWritePath(".devcli/agents/worker.md"));
        }
    }
}
