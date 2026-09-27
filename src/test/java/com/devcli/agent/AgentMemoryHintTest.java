package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
import com.devcli.memory.LongTermMemory;
import com.devcli.memory.MemoryManager;
import com.devcli.memory.RollingSummary;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryHintTest {

    @TempDir
    Path tempDir;

    @Test
    void plainAssistantClaimCannotBypassSaveMemoryTool() {
        String oldMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.toString());
        Agent agent = null;
        try {
            StubGLMClient llmClient = new StubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "已打开链接。", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "已记住。", null, 20, 10)
            ));
            agent = new Agent(llmClient);

            agent.run("打开 https://www.yuque.com/example/docs 这个语雀文档");
            assertEquals(0, agent.getMemoryManager().getLongTermMemory().size());

            agent.run("你可以直接复用我已经登录的Chrome，记一下");

            assertEquals(0, agent.getMemoryManager().getLongTermMemory().size(),
                    "模型只说“已记住”但没有调用 save_memory 时，不得暗中自动落盘");
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
    void shouldCarryWorkingMemoryEvidenceIntoNextTurnContext(@TempDir Path tempDir) {
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
                    new LlmClient.ChatResponse("assistant", "已完成", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "第二轮完成", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            agent = new Agent(llmClient, tools);

            agent.run("读取 sample.txt");

            // 单轮内工具证据由 tool_result 原文承载，不再复制进 system prompt
            assertTrue(llmClient.messagesByCall.size() >= 2);
            String secondSystem = llmClient.messagesByCall.get(1).get(0).content();
            assertFalse(secondSystem.contains("react-working-memory-evidence"), secondSystem);
            assertTrue(llmClient.messagesByCall.get(1).stream()
                            .anyMatch(m -> m.content() != null
                                    && m.content().contains("react-working-memory-evidence")),
                    "工具证据应通过 tool_result 抵达 LLM");

            // 跨轮由当轮上下文快照承载精确实体
            agent.run("基于刚才读到的内容继续");
            List<LlmClient.Message> lastCall = llmClient.messagesByCall.get(llmClient.messagesByCall.size() - 1);
            String lastUser = lastCall.stream()
                    .filter(m -> "user".equals(m.role()) && m.content() != null)
                    .reduce((first, second) -> second)
                    .map(LlmClient.Message::content)
                    .orElse("");
            assertTrue(lastUser.contains("Session Memory"), lastUser);
            assertTrue(lastUser.contains("react-working-memory-evidence"), lastUser);
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
    void shouldMaintainSessionPreSummaryAfterLongTurn(@TempDir Path tempDir) throws InterruptedException {
        String oldMemoryDir = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.resolve("memory").toString());
        Agent agent = null;
        try {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "长会话回答", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", """
                            {"schema_version":2,"request_intent":"自动维护的会话预摘要",\
                            "concepts":[],"files":[],"pitfalls":[],"resolution_steps":[],\
                            "user_messages":["请记住这段上下文"],"protected_facts":[]}
                            """, null, 20, 10)
            ));
            agent = new Agent(llmClient);

            agent.seedHistory(List.of(LlmClient.Message.user("旧上下文" + "x".repeat(12_000)),
                    LlmClient.Message.assistant("旧任务完成")));
            agent.run("请记住这段上下文：" + "中".repeat(24_000));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (agent.getMemoryManager().getCompactionSummaryCache().currentPreSummary().isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(agent.getMemoryManager().getCompactionSummaryCache().currentPreSummary().isPresent());
            String summary = agent.getMemoryManager().getCompactionSummaryCache()
                    .currentPreSummary().orElseThrow().summary();
            assertTrue(summary.contains("## 主要请求与意图"));
            assertTrue(RollingSummary.parse(summary).get("主要请求与意图")
                    .contains("自动维护的会话预摘要"));
            assertEquals(2, llmClient.messagesByCall.size(), "一次任务响应后应追加一次预摘要维护调用");
            assertEquals(40, agent.getMemoryManager().getTokenBudget().getTotalInputTokens(),
                    "后台摘要消耗必须计入会话统计");
            assertTrue(agent.compactHistoryForPersistence(18_000));
            assertEquals(2, llmClient.messagesByCall.size(), "正式压缩应直接复用同一原始前缀的预摘要");
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
    void finalResponseDoesNotWaitForPreSummaryMaintenance() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "任务完成", null, 20, 10)
        ));
        BlockingPreSummaryMemoryManager memoryManager =
                new BlockingPreSummaryMemoryManager(llmClient, tempDir.resolve("memory-async"));
        ToolRegistry toolRegistry = new ToolRegistry();
        Agent agent = new Agent(llmClient, toolRegistry, memoryManager);
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = runner.submit(() -> agent.run("执行任务"));

            assertTrue(memoryManager.maintenanceScheduled.await(2, TimeUnit.SECONDS));
            assertEquals("任务完成", result.get(100, TimeUnit.MILLISECONDS),
                    "预摘要是投机缓存，不能延迟主回合返回");
        } finally {
            memoryManager.pending.complete(
                    MemoryManager.SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED);
            runner.shutdownNow();
            agent.close();
            toolRegistry.close();
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

            // skill 索引按输入路径过滤的语义不变，载体改为当轮上下文快照
            String turnContext = llmClient.messagesByCall.get(0).stream()
                    .filter(m -> "user".equals(m.role()) && m.content() != null)
                    .reduce((first, second) -> second)
                    .map(LlmClient.Message::content)
                    .orElse("");
            assertTrue(turnContext.contains("java-review"), turnContext);
            assertTrue(turnContext.contains("global-skill"), turnContext);
            assertFalse(turnContext.contains("docs-review"), turnContext);
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

    private static final class BlockingPreSummaryMemoryManager extends MemoryManager {
        private final CountDownLatch maintenanceScheduled = new CountDownLatch(1);
        private final CompletableFuture<SessionPreSummaryMaintenanceResult> pending =
                new CompletableFuture<>();

        private BlockingPreSummaryMemoryManager(LlmClient llmClient, Path memoryDir) {
            super(llmClient, 4_096, 128_000, new LongTermMemory(memoryDir.toFile()));
        }

        @Override
        public CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
                List<LlmClient.Message> history,
                int turnToolCalls,
                int largestToolResultChars,
                int triggerTokens,
                java.util.function.BooleanSupplier callGuard,
                java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer) {
            maintenanceScheduled.countDown();
            return pending;
        }
    }
}
