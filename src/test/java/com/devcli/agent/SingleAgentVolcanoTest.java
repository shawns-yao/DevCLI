package com.devcli.agent;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SingleAgentVolcanoTest {

    @Test
    @Disabled("手动联调真实 LLM 配置时启用，默认回归不访问外部服务")
    void testSimpleTaskWithVolcanoGlm() throws Exception {
        DevCliConfig config = DevCliConfig.load();
        LlmClient llmClient = LlmClientFactory.create("anthropic", config);
        assertNotNull(llmClient, "LLM client should be created");
        System.out.println("Client: " + llmClient.getProviderName() + " / " + llmClient.getModelName());

        ToolRegistry toolRegistry = new ToolRegistry();

        try (Agent agent = new Agent(llmClient, toolRegistry)) {
            // 简单任务：输出工具列表
            String task = "请列出你可用的所有工具名称";
            System.out.println("Task: " + task);

            String result = agent.run(task);
            System.out.println("Result: " + result);
            assertNotNull(result, "Result should not be null");
        }
    }
}
