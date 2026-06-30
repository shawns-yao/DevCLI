package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AnthropicClientVolcanoTest {

    @Test
    @Disabled("手动联调真实 LLM 配置时启用，默认回归不访问外部服务")
    void testVolcanoGlm52() throws Exception {
        DevCliConfig config = DevCliConfig.load();
        LlmClient client = LlmClientFactory.create("anthropic", config);
        assertNotNull(client, "Anthropic client should be created");

        System.out.println("Testing client: " + client.getProviderName() + " / " + client.getModelName());
        System.out.println("API URL: " + ((AnthropicClient) client).getApiUrl());

        LlmClient.ChatResponse response = client.chat(
                List.of(LlmClient.Message.user("你好，请用一句话回复")),
                null
        );

        assertNotNull(response, "Response should not be null");
        System.out.println("Response: " + response.content());
        System.out.println("Tokens: input=" + response.inputTokens() + ", output=" + response.outputTokens());
    }
}
