package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.agent.PlanExecuteAgent;
import com.devcli.agent.StructuredExecution;
import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;

class MainPlanAgentFactoryTest {

    @Test
    void planModeReusesReactToolRegistryAndMemoryManager() throws Exception {
        LlmClient llmClient = new GLMClient("test-key");
        ToolRegistry sharedToolRegistry = new ToolRegistry();
        Agent reactAgent = new Agent(llmClient, sharedToolRegistry);
        MemoryManager sharedMemoryManager = reactAgent.getMemoryManager();

        StructuredExecution execution = Main.createStructuredExecution(
                llmClient,
                reactAgent,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel(),
                System.out
        );

        assertSame(sharedToolRegistry, readField(execution, "toolRegistry"));
        assertSame(sharedMemoryManager, readField(execution, "memoryManager"));
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
