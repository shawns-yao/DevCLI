package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryEntry;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.skill.SkillStateStore;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryHintTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitChromeRememberRequestStoresSitePreferenceInLongTermMemory() {
        String oldMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.toString());
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
                System.clearProperty("devcli.memory.dir");
            } else {
                System.setProperty("devcli.memory.dir", oldMemoryDir);
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
        String oldMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.resolve("memory").toString());
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
                System.clearProperty("devcli.memory.dir");
            } else {
                System.setProperty("devcli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void shouldMaintainSessionPreSummaryAfterLongTurn(@TempDir Path tempDir) {
        String oldMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.resolve("memory").toString());
        Agent agent = null;
        try {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "长会话回答", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "自动维护的会话预摘要", null, 20, 10)
            ));
            agent = new Agent(llmClient);

            agent.run("请记住这段上下文：" + "x".repeat(10_000));

            assertTrue(agent.getMemoryManager().getSessionMemory().currentPreSummary().isPresent());
            assertEquals("自动维护的会话预摘要",
                    agent.getMemoryManager().getSessionMemory().currentPreSummary().orElseThrow().summary());
            assertEquals(2, llmClient.messagesByCall.size(), "一次任务响应后应追加一次预摘要维护调用");
        } finally {
            if (agent != null) {
                agent.close();
            }
            if (oldMemoryDir == null) {
                System.clearProperty("devcli.memory.dir");
            } else {
                System.setProperty("devcli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void pathScopedSkillsAreIndexedOnlyWhenCurrentInputMentionsMatchingPath(@TempDir Path tempDir) throws IOException {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "java-review", "Java review", "src/**/*.java");
        writeSkill(skillsRoot, "docs-review", "Docs review", "docs/**/*.md");
        writeSkill(skillsRoot, "global-skill", "Always visible", null);

        SkillRegistry registry = new SkillRegistry(null, skillsRoot, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();

        String oldMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.resolve("memory").toString());
        Agent agent = null;
        try {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "done", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            tools.setSkillRegistry(registry);
            tools.setSkillContextBuffer(new SkillContextBuffer());
            agent = new Agent(llmClient, tools);
            agent.setSkillRegistry(registry);
            agent.setSkillContextBuffer(new SkillContextBuffer());

            agent.run("请检查 src/main/java/App.java");

            String systemPrompt = llmClient.messagesByCall.get(0).get(0).content();
            assertTrue(systemPrompt.contains("java-review"), systemPrompt);
            assertTrue(systemPrompt.contains("global-skill"), systemPrompt);
            assertFalse(systemPrompt.contains("docs-review"), systemPrompt);
        } finally {
            if (agent != null) {
                agent.close();
            }
            if (oldMemoryDir == null) {
                System.clearProperty("devcli.memory.dir");
            } else {
                System.setProperty("devcli.memory.dir", oldMemoryDir);
            }
        }
    }

    private static void writeSkill(Path root, String name, String description, String pathPattern) throws IOException {
        Path skillDir = root.resolve(name);
        Files.createDirectories(skillDir);
        String paths = pathPattern == null ? "" : "paths: [" + pathPattern + "]\n";
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n"
                        + "name: " + name + "\n"
                        + "description: " + description + "\n"
                        + paths
                        + "---\n"
                        + "body\n");
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
