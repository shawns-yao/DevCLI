package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliConversationBranchManagerTest {

    @Test
    void createsAndSwitchesInProcessHistorySnapshots() {
        Agent agent = new Agent(new StubLlmClient());
        try {
            agent.seedHistory(List.of(LlmClient.Message.user("主分支约束")));
            CliConversationBranchManager manager = new CliConversationBranchManager(agent);

            assertEquals("已创建分支: feature-a", manager.create("feature-a"));
            agent.seedHistory(List.of(LlmClient.Message.user("主分支后续")));
            assertEquals("已切换到分支: feature-a", manager.use("feature-a"));
            assertEquals("feature-a", manager.currentBranch());
            assertEquals(2, agent.getConversationHistory().size());
            assertTrue(agent.getConversationHistory().stream()
                    .anyMatch(message -> "主分支约束".equals(message.content())));
        } finally {
            agent.close();
        }
    }

    @Test
    void rejectsInvalidBranchNames() {
        Agent agent = new Agent(new StubLlmClient());
        try {
            CliConversationBranchManager manager = new CliConversationBranchManager(agent);

            assertThrows(IllegalArgumentException.class, () -> manager.create("含空格"));
            assertEquals("未找到分支: missing", manager.use("missing"));
        } finally {
            agent.close();
        }
    }

    private static final class StubLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant", "", List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
