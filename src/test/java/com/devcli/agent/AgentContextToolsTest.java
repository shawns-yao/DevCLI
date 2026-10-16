package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentContextToolsTest {
    @TempDir Path directory;

    @Test
    void memoryIndexDoesNotCallSelectorAndTopicCanBeReadOnDemand() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = client(calls);
        try (var memory = new MemoryManager(client, 2000, 32000, new LongTermMemory(directory));
             var registry = new ToolRegistry()) {
            memory.storeTopic("global", "deployment", "部署约束", "project", "发布前检查产物", null, true);
            AgentRuntimeSupport.bindMemory(registry, memory);
            String context = memory.buildContextForQuery("部署", 2000);
            assertEquals(0, calls.get(), "记忆索引不得触发额外模型请求");
            assertTrue(context.contains("deployment"), context);
            assertFalse(context.contains("发布前检查产物"), context);
            var result = registry.executeToolOutput("read_memory", "{\"name\":\"deployment.md\",\"scope\":\"global\"}");
            assertTrue(result.isSuccess(), result.text());
            assertTrue(result.text().contains("发布前检查产物"), result.text());
            assertFalse(registry.executeToolOutput("read_memory", "{\"name\":\"../AGENTS.md\"}").isSuccess());
        }
    }

    @Test
    void agentMaintainsOneWorkPlanAndCompactionRestoresTheSamePlan() {
        try (var memory = new MemoryManager(client(new AtomicInteger()), 2000, 32000,
                new LongTermMemory(directory)); var registry = new ToolRegistry()) {
            AgentRuntimeSupport.bindMemory(registry, memory);
            var result = registry.executeToolOutput("update_plan", """
                    {"goal":"修复发布流程","steps":[{"step":"核对入口","status":"completed"},
                    {"step":"修改实现","status":"in_progress"},{"step":"验证结果","status":"pending"}]}
                    """);
            assertTrue(result.isSuccess(), result.text());
            String stored = memory.getSessionMemory().snapshot().workState().get("agent_work_plan");
            assertNotNull(stored);
            assertTrue(memory.buildSessionMemorySection().contains(stored));
            assertTrue(memory.buildPostCompactRestoreSection().contains(stored));
            String restored = AgentRuntimeSupport.buildPostCompactRestoreSection(
                    memory.buildPostCompactRestoreSection(), registry, null);
            assertTrue(restored.contains(stored), "通用恢复预算不得裁剪工作计划");
            var rejected = registry.executeToolOutput("update_plan", """
                    {"goal":"修复发布流程","steps":[{"step":"第一项","status":"in_progress"},
                    {"step":"第二项","status":"in_progress"}]}
                    """);
            assertFalse(rejected.isSuccess(), rejected.text());
            assertEquals(stored, memory.getSessionMemory().snapshot().workState().get("agent_work_plan"));
            memory.getSessionMemory().clear();
            memory.restoreWorkPlan(List.of(LlmClient.Message.internalUser(restored)));
            assertEquals(stored, memory.getSessionMemory().snapshot().workState().get("agent_work_plan"));
            registry.restrictForDelegation();
            assertTrue(registry.getToolDefinitions().stream().noneMatch(t ->
                    t.name().equals("update_plan") || t.name().equals("read_memory")));
        }
    }

    private static LlmClient client(AtomicInteger calls) {
        return new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                calls.incrementAndGet();
                return AgentDelegationTest.answer("{\"selected_memories\":[]}");
            }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                return chat(messages, tools);
            }
            @Override public String getModelName() { return "stub"; }
            @Override public String getProviderName() { return "test"; }
        };
    }
}
