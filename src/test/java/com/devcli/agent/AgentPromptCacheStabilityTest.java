package com.devcli.agent;

import com.devcli.llm.GLMClient;
import com.devcli.llm.LlmClient;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prompt cache（提示词缓存）契约测试。
 *
 * <p>自动前缀缓存按请求 token 前缀命中。{@code messages[0]}（system prompt）是整个请求的前缀，
 * 一旦它在轮次之间发生变化，其后<b>全部对话历史</b>都会前缀失配——静态头通常只有几千 token，
 * 而历史可以到几十万，等于缓存形同虚设。
 *
 * <p>因此约束是：<b>system prompt 在同一会话内必须逐字节稳定</b>，易变上下文
 * （长期记忆检索 / skill 索引 / 工作记忆）只能以 append-only 方式进入消息尾部。
 */
class AgentPromptCacheStabilityTest {

    @Test
    void systemPromptStaysByteIdenticalAcrossReactIterations(@TempDir Path tempDir) throws IOException {
        Path sampleFile = tempDir.resolve("sample.txt");
        Files.writeString(sampleFile, "cache-stability-evidence");

        withIsolatedMemoryDir(tempDir, () -> {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    toolCallResponse("call_1", "read_file", sampleFile),
                    new LlmClient.ChatResponse("assistant", "已完成", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            try (Agent agent = new Agent(llmClient, tools)) {
                agent.run("读取 sample.txt");
            }

            List<List<LlmClient.Message>> reactCalls = llmClient.reactCalls();
            assertTrue(reactCalls.size() >= 2,
                    "需要至少两次 ReAct 迭代才能验证跨迭代稳定性，实际: " + reactCalls.size());

            String first = reactCalls.get(0).get(0).content();
            String second = reactCalls.get(1).get(0).content();
            assertEquals(first, second,
                    "system prompt 在 ReAct 迭代之间必须逐字节一致，否则整段对话历史前缀缓存失配");
        });
    }

    @Test
    void systemPromptStaysByteIdenticalAcrossUserTurns(@TempDir Path tempDir) throws IOException {
        Path sampleFile = tempDir.resolve("sample.txt");
        Files.writeString(sampleFile, "cross-turn-evidence");

        withIsolatedMemoryDir(tempDir, () -> {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    toolCallResponse("call_1", "read_file", sampleFile),
                    new LlmClient.ChatResponse("assistant", "第一轮完成", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "第二轮完成", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            try (Agent agent = new Agent(llmClient, tools)) {
                agent.run("读取 sample.txt");
                agent.run("再说一次结论");
            }

            List<List<LlmClient.Message>> reactCalls = llmClient.reactCalls();
            assertTrue(reactCalls.size() >= 3,
                    "需要覆盖两轮 user 输入，实际调用: " + reactCalls.size());

            String firstTurnSystem = reactCalls.get(0).get(0).content();
            String secondTurnSystem = reactCalls.get(reactCalls.size() - 1).get(0).content();
            assertEquals(firstTurnSystem, secondTurnSystem,
                    "system prompt 跨 user 轮次也必须稳定，否则每轮都要重算全部历史");
        });
    }

    @Test
    void workingMemoryEvidenceReachesLlmOnNextTurnWithoutTouchingSystemPrompt(@TempDir Path tempDir)
            throws IOException {
        Path sampleFile = tempDir.resolve("sample.txt");
        Files.writeString(sampleFile, "turn-context-evidence");

        withIsolatedMemoryDir(tempDir, () -> {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    toolCallResponse("call_1", "read_file", sampleFile),
                    new LlmClient.ChatResponse("assistant", "第一轮完成", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "第二轮完成", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            try (Agent agent = new Agent(llmClient, tools)) {
                agent.run("读取 sample.txt");
                agent.run("基于刚才读到的内容回答");
            }

            List<List<LlmClient.Message>> reactCalls = llmClient.reactCalls();
            List<LlmClient.Message> secondTurn = reactCalls.get(reactCalls.size() - 1);

            String system = secondTurn.get(0).content();
            assertTrue(!system.contains("turn-context-evidence"),
                    "工具证据不得进入 system prompt，否则破坏前缀稳定性");

            String lastUser = lastUserContent(secondTurn);
            assertTrue(lastUser.contains("Session Memory"),
                    "第二轮 user 消息应前置注入会话记忆块，实际内容: " + lastUser);
            assertTrue(lastUser.contains("turn-context-evidence"),
                    "上一轮的精确工具证据应通过 turn context 抵达 LLM，实际内容: " + lastUser);
        });
    }

    /**
     * 前缀缓存的完整契约：上一次请求的消息序列必须是下一次请求的前缀。
     *
     * <p>只断言 {@code messages[0]} 稳定还不够——历史中任何一条消息被原地改写、移除或位移，
     * 都会让失配点之后的全部内容不可复用。唯一安全的形态是 append-only。
     *
     * <p>合法例外只有上下文压缩（会重建历史），本用例的 token 量远低于压缩阈值，不会触发。
     */
    @Test
    void eachRequestIsAppendOnlyExtensionOfThePreviousRequest(@TempDir Path tempDir) throws IOException {
        Path sampleFile = tempDir.resolve("sample.txt");
        Files.writeString(sampleFile, "append-only-evidence");

        withIsolatedMemoryDir(tempDir, () -> {
            RecordingStubGLMClient llmClient = new RecordingStubGLMClient(List.of(
                    toolCallResponse("call_1", "read_file", sampleFile),
                    toolCallResponse("call_2", "read_file", sampleFile),
                    new LlmClient.ChatResponse("assistant", "已完成", null, 20, 10)
            ));
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            try (Agent agent = new Agent(llmClient, tools)) {
                agent.run("连续读取 sample.txt");
            }

            List<List<LlmClient.Message>> reactCalls = llmClient.reactCalls();
            assertTrue(reactCalls.size() >= 3, "需要至少三次迭代，实际: " + reactCalls.size());

            for (int i = 1; i < reactCalls.size(); i++) {
                List<LlmClient.Message> previous = reactCalls.get(i - 1);
                List<LlmClient.Message> current = reactCalls.get(i);
                assertTrue(current.size() >= previous.size(),
                        "第 " + i + " 次请求不应比上一次更短（历史只能追加）");
                for (int j = 0; j < previous.size(); j++) {
                    assertEquals(previous.get(j).role(), current.get(j).role(),
                            "第 " + i + " 次请求消息[" + j + "] role 与上一次不一致，前缀缓存失配");
                    assertEquals(previous.get(j).content(), current.get(j).content(),
                            "第 " + i + " 次请求消息[" + j + "] 内容被改写，前缀缓存自此失配");
                }
            }
        });
    }

    private static String lastUserContent(List<LlmClient.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmClient.Message message = messages.get(i);
            if ("user".equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    private static LlmClient.ChatResponse toolCallResponse(String callId, String toolName, Path path) {
        return new LlmClient.ChatResponse(
                "assistant",
                "",
                List.of(new LlmClient.ToolCall(
                        callId,
                        new LlmClient.ToolCall.Function(
                                toolName,
                                "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}"
                        )
                )),
                20,
                10
        );
    }

    private static void withIsolatedMemoryDir(Path tempDir, ThrowingRunnable body) {
        String old = System.getProperty("devcli.memory.dir");
        System.setProperty("devcli.memory.dir", tempDir.resolve("memory").toString());
        try {
            body.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (old == null) {
                System.clearProperty("devcli.memory.dir");
            } else {
                System.setProperty("devcli.memory.dir", old);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class RecordingStubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingStubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        /**
         * 只保留 ReAct 主循环的调用。会话预摘要维护会用完全不同的 system prompt 另发一次请求，
         * 不属于本契约的比较范围。
         */
        private List<List<Message>> reactCalls() {
            List<List<Message>> calls = new ArrayList<>();
            for (List<Message> messages : messagesByCall) {
                if (messages.isEmpty()) {
                    continue;
                }
                Message system = messages.get(0);
                if ("system".equals(system.role())
                        && system.content() != null
                        && system.content().contains("## Mode: ReAct Agent")) {
                    calls.add(messages);
                }
            }
            return calls;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
