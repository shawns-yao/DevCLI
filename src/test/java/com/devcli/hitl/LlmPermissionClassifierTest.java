package com.devcli.hitl;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmPermissionClassifier} 的解析与失败路径。
 *
 * <p>用桩模型客户端，不依赖网络：这里要验证的是「模型给出任何非预期输出时都收口为失败」，
 * 而真实模型的输出恰好是不可复现的那部分。解析失败必须变成拒绝，绝不能猜一个默认值——
 * 猜错的方向如果偏向放行，这条链就白做了。</p>
 */
class LlmPermissionClassifierTest {

    // ------------------ 响应解析 ------------------

    @Test
    void parsesPlainJson() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict(
                "{\"allow\": true, \"reason\": \"项目内读取文件\"}");

        assertTrue(verdict.allow());
        assertEquals("项目内读取文件", verdict.reason());
    }

    @Test
    void parsesJsonInsideCodeFence() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict(
                "```json\n{\"allow\": false, \"reason\": \"会改写 git 历史\"}\n```");

        assertFalse(verdict.allow());
        assertEquals("会改写 git 历史", verdict.reason());
    }

    @Test
    void parsesJsonSurroundedByProse() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict(
                "判断结果如下：{\"allow\": true, \"reason\": \"只读查询\"} 完毕。");

        assertTrue(verdict.allow());
    }

    @Test
    void missingAllowFieldIsRejected() {
        assertThrows(IOException.class,
                () -> LlmPermissionClassifier.parseVerdict("{\"reason\": \"看起来还行\"}"));
    }

    @Test
    void nonBooleanAllowIsRejected() {
        assertThrows(IOException.class,
                () -> LlmPermissionClassifier.parseVerdict("{\"allow\": \"true\"}"));
    }

    @Test
    void nonJsonResponseIsRejected() {
        assertThrows(IOException.class,
                () -> LlmPermissionClassifier.parseVerdict("这个操作是安全的"));
    }

    @Test
    void blankResponseIsRejected() {
        assertThrows(IOException.class, () -> LlmPermissionClassifier.parseVerdict(""));
    }

    @Test
    void missingReasonFallsBackToEmptyString() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict("{\"allow\": true}");

        assertTrue(verdict.allow());
        assertEquals("", verdict.reason());
    }

    // ------------------ 调用失败 ------------------

    @Test
    void modelAnswerIsParsedIntoVerdict() throws Exception {
        try (LlmPermissionClassifier classifier = classifier(
                messages -> answer("{\"allow\": false, \"reason\": \"删除操作\"}"), 5_000)) {
            var verdict = classifier.classify(request());

            assertFalse(verdict.allow());
            assertEquals("删除操作", verdict.reason());
        }
    }

    @Test
    void modelFailureIsReportedAsFailure() throws Exception {
        try (LlmPermissionClassifier classifier = classifier(
                messages -> {
                    throw new IOException("连接被拒绝");
                }, 5_000)) {
            IOException failure = assertThrows(IOException.class, () -> classifier.classify(request()));

            assertTrue(failure.getMessage().contains("连接被拒绝"), failure.getMessage());
        }
    }

    @Test
    void missingClientIsReportedAsFailure() throws Exception {
        try (LlmPermissionClassifier classifier =
                     new LlmPermissionClassifier(() -> null, "system", 5_000)) {
            assertThrows(IOException.class, () -> classifier.classify(request()));
        }
    }

    @Test
    void blankModelAnswerIsReportedAsFailure() throws Exception {
        try (LlmPermissionClassifier classifier = classifier(messages -> answer("   "), 5_000)) {
            assertThrows(IOException.class, () -> classifier.classify(request()));
        }
    }

    @Test
    void timeoutFailsClosedInsteadOfWaiting() throws Exception {
        // 模型侧阻塞 30 秒，分类器超时 200 毫秒：必须立即失败，而不是等模型返回。
        try (LlmPermissionClassifier classifier = classifier(messages -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return answer("{\"allow\": true, \"reason\": \"慢但放行\"}");
        }, 200)) {
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> classifier.classify(request()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(failure.getMessage().contains("超时"), failure.getMessage());
            assertTrue(elapsedMillis < 5_000,
                    "超时应立即失败关闭，实际耗时 " + elapsedMillis + "ms");
        }
    }

    private static PermissionClassifier.Request request() {
        return new PermissionClassifier.Request("write_file", "{\"path\":\"a.txt\"}", "/tmp", "auto");
    }

    private static LlmPermissionClassifier classifier(ChatBehavior behavior, long timeoutMillis) {
        return new LlmPermissionClassifier(() -> new StubClient(behavior), "system", timeoutMillis);
    }

    private static LlmClient.ChatResponse answer(String content) {
        return new LlmClient.ChatResponse("assistant", content, List.of(), 0, 0);
    }

    @FunctionalInterface
    private interface ChatBehavior {
        LlmClient.ChatResponse answer(List<LlmClient.Message> messages) throws IOException;
    }

    private static final class StubClient implements LlmClient {
        private final ChatBehavior behavior;

        StubClient(ChatBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return behavior.answer(messages);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return behavior.answer(messages);
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }

        @Override
        public String getProviderName() {
            return "stub-provider";
        }
    }
}
