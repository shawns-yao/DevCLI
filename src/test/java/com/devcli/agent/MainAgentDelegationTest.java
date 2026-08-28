package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.LongTermMemoryStore;
import com.devcli.memory.MemoryEntry;
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
                AgentDelegationTest.call("delegate_task", "{\"role\":\"planner\",\"task\":\"plan only\"}"),
                AgentDelegationTest.answer("child plan"), AgentDelegationTest.answer("parent result"));
        try (ToolRegistry registry = new ToolRegistry();
             LongTermMemory memory = new LongTermMemory(new EmptyStore(), project.resolve("memory"))) {
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

    private static final class EmptyStore implements LongTermMemoryStore {
        @Override public List<MemoryEntry> loadAll() { return List.of(); }
        @Override public boolean upsert(MemoryEntry entry) { return true; }
        @Override public boolean isPersistent() { return false; }
        @Override public void delete(String id) { }
        @Override public void clear() { }
        @Override public void close() { }
    }
}
