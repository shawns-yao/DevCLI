package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient.Message;

import java.io.IOException;
import java.util.List;

/**
 * OpenAI provider 最小手工测试
 */
public class OpenAiManualTest {
    public static void main(String[] args) throws IOException {
        DevCliConfig config = new DevCliConfig();

        // 1. 测试 factory 能否识别 openai
        LlmClient client = LlmClientFactory.create("openai", config);
        if (client == null) {
            System.err.println("❌ LlmClientFactory.create(\"openai\") 返回 null");
            System.err.println("   .env 中可能缺少 OPENAI_API_KEY 或被注释");
            System.err.println("   当前 config.getApiKey(\"openai\") = " + config.getApiKey("openai"));
            System.exit(1);
        }

        System.out.println("✅ OpenAI provider 创建成功");
        System.out.println("   provider: " + client.getProviderName());
        System.out.println("   model: " + client.getModelName());
        System.out.println("   maxWindow: " + client.maxContextWindow());
        System.out.println("   cache: " + client.promptCacheMode());

        // 2. 测试真实 API 调用
        System.out.println("\n📡 测试 API 调用...");
        System.out.println("   apiUrl: " + ((com.devcli.llm.OpenAiClient)client).getApiUrl());
        List<Message> messages = List.of(
            Message.system("You are a helpful assistant."),
            Message.user("Say 'Hello from OpenAI' in exactly 4 words.")
        );

        LlmClient.ChatResponse response = client.chat(messages, null);

        System.out.println("✅ API 调用成功");
        System.out.println("   role: " + response.role());
        System.out.println("   content: " + response.content());
        System.out.println("   inputTokens: " + response.inputTokens());
        System.out.println("   outputTokens: " + response.outputTokens());
        System.out.println("   cachedInputTokens: " + response.cachedInputTokens());

        // 3. 测试第二轮 cache 命中
        System.out.println("\n📡 测试第二轮(观察 cache 命中)...");
        messages = List.of(
            Message.system("You are a helpful assistant."),
            Message.user("Say 'Hello from OpenAI' in exactly 4 words."),
            Message.assistant(response.content()),
            Message.user("Now say 'Goodbye' in 1 word.")
        );

        response = client.chat(messages, null);
        System.out.println("✅ 第二轮成功");
        System.out.println("   content: " + response.content());
        System.out.println("   inputTokens: " + response.inputTokens());
        System.out.println("   outputTokens: " + response.outputTokens());
        System.out.println("   cachedInputTokens: " + response.cachedInputTokens() + " (应 > 0 证明 cache 命中)");
    }
}
