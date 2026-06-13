package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentSeedHistoryTest {

    @Test
    void seedHistoryInsertsAfterSystemMessage() {
        try (Agent agent = new Agent(new GLMClient("test-key"))) {
            agent.seedHistory(List.of(
                    LlmClient.Message.user("第一轮输入"),
                    LlmClient.Message.assistant("第一轮回复")));

            List<LlmClient.Message> history = agent.conversationHistorySnapshot();
            assertEquals(3, history.size());
            assertEquals("system", history.get(0).role());
            assertEquals("user", history.get(1).role());
            assertEquals("第一轮输入", history.get(1).content());
            assertEquals("assistant", history.get(2).role());
        }
    }

    @Test
    void seedHistoryIgnoredWhenConversationAlreadyStarted() {
        try (Agent agent = new Agent(new GLMClient("test-key"))) {
            agent.seedHistory(List.of(LlmClient.Message.user("a"), LlmClient.Message.assistant("b")));
            // 第二次注入应被忽略，防止历史插入到进行中的上下文中间
            agent.seedHistory(List.of(LlmClient.Message.user("c"), LlmClient.Message.assistant("d")));

            assertEquals(3, agent.conversationHistorySnapshot().size());
        }
    }

    @Test
    void seedHistoryToleratesNullAndEmpty() {
        try (Agent agent = new Agent(new GLMClient("test-key"))) {
            agent.seedHistory(null);
            agent.seedHistory(List.of());

            assertEquals(1, agent.conversationHistorySnapshot().size());
        }
    }
}
