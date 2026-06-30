package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiClientPingTest {

    @Test
    @Disabled("手动联调真实 LLM 配置时启用，默认回归不访问外部服务")
    void pingOpenAiEndpointWithRealConfig() throws Exception {
        DevCliConfig config = DevCliConfig.load();
        LlmClient client = LlmClientFactory.create("openai", config);
        assertNotNull(client, "OpenAI client should be created from .env config");

        System.out.println("Testing OpenAI client with:");
        System.out.println("  Provider: " + client.getProviderName());
        System.out.println("  Model: " + client.getModelName());
        System.out.println("  Max window: " + client.maxContextWindow());

        LlmClient.ChatResponse response = client.chat(List.of(
                LlmClient.Message.system("只回复一个字：OK"),
                LlmClient.Message.user("ping")
        ), null);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.content(), "Content should not be null");
        assertTrue(!response.content().isBlank(), "Response should not be empty");

        System.out.println("Response received: " + response.content());
    }
}
