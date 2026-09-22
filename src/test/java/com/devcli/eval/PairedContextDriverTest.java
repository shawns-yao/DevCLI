package com.devcli.eval;

import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PairedContextDriverTest {
    @TempDir Path dir;

    @Test void chunkingPreservesEveryCharacterAndFinalInstruction() {
        String context = "x".repeat(7999) + "\uD83D\uDE00" + "y".repeat(16000);
        var messages = PairedContextDriver.history("PREFIX", context, "QUESTION");
        String joined = messages.subList(1, messages.size() - 1).stream()
                .filter(m -> m.role().equals("user")).map(LlmClient.Message::content).reduce("", String::concat);
        assertEquals("PREFIX" + context, joined);
        assertEquals("QUESTION", messages.get(messages.size() - 1).content());
    }

    @Test void rawMakesOneCallAndReportsActualUsage() throws Exception {
        var job = new ObjectMapper().createObjectNode().put("prefix", "p").put("context", "c").put("suffix", "q");
        AtomicInteger calls = new AtomicInteger();
        var result = PairedContextDriver.run(job, "raw", fake(calls), dir);
        assertEquals(1, calls.get());
        assertEquals(0, result.path("summary_calls").asInt());
        assertEquals(100, result.path("answer_input_tokens").asInt());
        assertEquals(100, result.path("total_input_tokens").asInt());
        assertFalse(result.path("context_changed").asBoolean());
    }

    @Test void usageWrapperPreservesModelBudgets() {
        LlmClient client = fake(new AtomicInteger());
        var counted = new PairedContextDriver.CountingClient(client);
        assertEquals(client.maxContextWindow(), counted.maxContextWindow());
        assertEquals(client.maxOutputTokens(), counted.maxOutputTokens());
    }

    @Test void treatmentInvokesProductionCompactorAndCountsSummarySeparately() throws Exception {
        var job = new ObjectMapper().createObjectNode().put("prefix", "p")
                .put("context", "Historical evidence. ".repeat(3000)).put("suffix", "What happened?");
        AtomicInteger calls = new AtomicInteger();
        var result = PairedContextDriver.run(job, "compact", fake(calls), dir);
        assertTrue(result.path("context_changed").asBoolean());
        assertTrue(result.path("history_summarized").asBoolean());
        assertTrue(result.path("summary_calls").asInt() > 0);
        assertEquals(calls.get() * 100, result.path("total_input_tokens").asInt());
        assertTrue(result.path("after_estimated_tokens").asInt() < result.path("before_estimated_tokens").asInt());
    }

    /**
     * 六段摘要契约：压缩器只接受合法六段 Markdown 或结构化信封，
     * 普通句子会被摘要协议拒绝并保留原历史，导致 compact 条件不产生任何变更。
     */
    private static final String SUMMARY = """
            ## 主要请求与意图
            - 保留历史证据并回答问题
            ## 关键技术概念
            - 无
            ## 文件和代码
            - 无
            ## 踩过的坑和修复
            - 无
            ## 问题解决过程
            - Historical evidence remains available.
            ## 逐条用户消息
            - 用户提供历史证据
            """;

    private static LlmClient fake(AtomicInteger calls) {
        return new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                calls.incrementAndGet();
                return new ChatResponse("assistant", SUMMARY, null, 100, 10);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public String getModelName() { return "offline-test"; }
            public String getProviderName() { return "test"; }
            public int maxContextWindow() { return 64_000; }
            public int maxOutputTokens() { return 2_048; }
        };
    }
}
