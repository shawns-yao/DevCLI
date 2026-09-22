package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainAgentDelegationTest {
    @TempDir Path project;

    @Test
    void defaultAgentDelegatesThenContinuesWithoutLeakingItsConversation() {
        var client = new AgentDelegationTest.ScriptedClient(
                AgentDelegationTest.call("delegate_task", """
                        {"role":"planner","task":"plan only","deliverable":"A bounded plan",
                         "task_spec":{"execution_kind":"agent_loop","parent_dependency":"none",
                         "inputs":"Requirements supplied in this task","scope":"Planning only",
                         "done_condition":"Return steps and acceptance criteria"}}
                        """),
                AgentDelegationTest.answer("child plan"), AgentDelegationTest.answer("parent result"));
        try (ToolRegistry registry = new ToolRegistry();
             LongTermMemory memory = new LongTermMemory(project.resolve("memory"))) {
            registry.setProjectPath(project.toString());
            MemoryManager manager = new MemoryManager(client, 1000, 128000, memory);
            try (Agent agent = new Agent(client, registry, manager)) {
                agent.seedHistory(List.of(LlmClient.Message.user("private previous conversation"),
                        LlmClient.Message.assistant(null, "previous answer")));
                String output = agent.run("private parent requirement");
                assertTrue(output.contains("parent result"), output);
                assertEquals(3, client.requests.size());
                assertEquals(2, client.requests.get(1).size());
                assertFalse(client.requests.get(1).stream().anyMatch(m -> m.content().contains("private")));
                assertTrue(client.tools.getFirst().stream().anyMatch(t -> t.name().equals("delegate_task")));
                assertTrue(client.tools.get(1).stream().noneMatch(t -> t.name().equals("delegate_task")));
                assertTrue(client.requests.get(2).stream().anyMatch(m -> "tool".equals(m.role()) && m.content().contains("child plan")));
            }
        }
    }

    @Test
    void failedChildReturnsObservedEvidenceToParent() throws Exception {
        java.nio.file.Files.writeString(project.resolve("note.txt"), "observed root cause");
        var client = new AgentDelegationTest.ScriptedClient(
                AgentDelegationTest.call("delegate_task", """
                        {"role":"explorer","task":"inspect note.txt","deliverable":"Observed causes",
                         "task_spec":{"execution_kind":"agent_loop","parent_dependency":"none",
                         "inputs":"note.txt","scope":"Read-only investigation",
                         "done_condition":"Return findings with evidence"}}
                        """),
                AgentDelegationTest.call("read_file", "{\"path\":\"note.txt\"}"),
                AgentDelegationTest.answer("parent continues using partial findings"));
        client.beforeResponse = () -> {
            if (client.requests.size() == 3) throw new java.io.IOException("child provider failed");
        };
        try (ToolRegistry registry = new ToolRegistry();
             LongTermMemory memory = new LongTermMemory(project.resolve("memory"))) {
            registry.setProjectPath(project.toString());
            try (Agent agent = new Agent(client, registry,
                    new MemoryManager(client, 1000, 128000, memory))) {
                assertTrue(agent.run("Investigate note.txt").contains("parent continues"));
                var toolMessages = client.requests.getLast().stream()
                        .filter(message -> "tool".equals(message.role())).toList();
                assertTrue(toolMessages.stream().anyMatch(message ->
                        message.content().contains("observed root cause")
                                && message.content().contains("PARTIAL")
                                && message.content().contains("failed")));
            }
        }
    }

    @Test
    void rejectedDelegationReturnsToParentWithoutStartingChild() {
        for (String contract : List.of(
                """
                {"execution_kind":"single_tool","parent_dependency":"none",
                 "inputs":"README.md","scope":"read only","done_condition":"Return content"}
                """,
                """
                {"execution_kind":"agent_loop","parent_dependency":"frequent",
                 "inputs":"README.md","scope":"read only","done_condition":"Return findings"}
                """,
                """
                {"execution_kind":"agent_loop","parent_dependency":"none",
                 "inputs":"README.md","scope":" ","done_condition":"Return findings"}
                """)) {
            var client = new AgentDelegationTest.ScriptedClient(
                    AgentDelegationTest.call("delegate_task",
                            "{\"role\":\"explorer\",\"task\":\"Inspect the project\","
                                    + "\"deliverable\":\"Findings\",\"task_spec\":" + contract + "}"),
                    AgentDelegationTest.answer("parent handles task"));
            try (ToolRegistry registry = new ToolRegistry();
                 LongTermMemory memory = new LongTermMemory(project.resolve("memory"))) {
                registry.setProjectPath(project.toString());
                MemoryManager manager = new MemoryManager(client, 1000, 128000, memory);
                try (Agent agent = new Agent(client, registry, manager)) {
                    assertEquals("parent handles task", agent.run("Inspect the project"));
                    assertEquals(2, client.requests.size());
                    assertTrue(client.requests.get(1).stream().anyMatch(m ->
                            "tool".equals(m.role()) && (m.content().contains("委派策略拒绝")
                                    || m.content().contains("工具参数校验失败"))));
                    assertTrue(client.tools.get(1).stream().anyMatch(t -> t.name().equals("delegate_task")));
                }
            }
        }
    }

}
