package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryEntry;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryHintTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitChromeRememberRequestStoresSitePreferenceInLongTermMemory() {
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        Agent agent = null;
        try {
            StubGLMClient llmClient = new StubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "已打开链接。", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "已记住。", null, 20, 10)
            ));
            agent = new Agent(llmClient);

            agent.run("打开 https://www.yuque.com/itwanger/gykdzg 这个语雀文档");
            assertEquals(0, agent.getMemoryManager().getLongTermMemory().size());

            agent.run("你可以直接复用我已经登录的Chrome，记一下");

            List<String> facts = agent.getMemoryManager().getLongTermMemory().getAll().stream()
                    .map(MemoryEntry::getContent)
                    .toList();
            assertTrue(facts.contains("访问 yuque.com（语雀）时优先复用用户已登录的 Chrome 登录态。"));
        } finally {
            if (agent != null) {
                agent.close();
            }
            if (oldMemoryDir == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void shouldRefreshWorkingMemoryIntoSystemPromptBeforeNextToolIteration(@TempDir Path tempDir) {
        Path sampleFile = tempDir.resolve("sample.txt");
        try {
            java.nio.file.Files.writeString(sampleFile, "react-working-memory-evidence");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        Agent agent = null;
        try {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    new LlmClient.ChatResponse(
                            "assistant",
                            "",
                            List.of(new LlmClient.ToolCall(
                                    "call_1",
                                    new LlmClient.ToolCall.Function(
                                            "read_file",
                                            "{\"path\":\"" + sampleFile.toString().replace("\\", "\\\\") + "\"}"
                                    )
                            )),
                            20,
                            10
                    ),
                    new LlmClient.ChatResponse("assistant", "已完成", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            agent = new Agent(llmClient, tools);

            agent.run("读取 sample.txt");

            assertTrue(llmClient.messagesByCall.size() >= 2);
            String secondSystem = llmClient.messagesByCall.get(1).get(0).content();
            assertTrue(secondSystem.contains("Working Memory"), secondSystem);
            assertTrue(secondSystem.contains("react-working-memory-evidence"), secondSystem);
        } finally {
            if (agent != null) {
                agent.close();
            }
            if (oldMemoryDir == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", oldMemoryDir);
            }
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }

    private static final class RecordingStubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingStubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
