package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryLifecycleRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void reactRunOpensAndClosesItsTaskProjection() {
        try (LongTermMemory longTermMemory = new LongTermMemory(tempDir.toFile());
             MemoryManager memoryManager = new MemoryManager(
                     new FinalAnswerClient(), 4_096, 128_000, longTermMemory);
             ToolRegistry registry = new ToolRegistry();
             Agent agent = new Agent(new FinalAnswerClient(), registry, memoryManager)) {
            registry.setProjectPath(tempDir.toString());

            agent.run("完成当前请求");

            assertTrue(memoryManager.getSessionMemory().snapshot().taskId().startsWith("react-run-"));
            assertTrue(memoryManager.getSessionMemory().snapshot().taskEnded());
        }
    }

    private static final class FinalAnswerClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "完成", List.of(), 10, 10);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() { return "test"; }

        @Override
        public String getProviderName() { return "test"; }

        @Override
        public int maxContextWindow() { return 128_000; }
    }
}
