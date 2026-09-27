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
 * {@link LlmPermissionClassifier} 的响应解析、输入组装与失败路径。
 *
 * <p>用桩模型客户端，不依赖网络：这里要验证的是「模型给出任何非预期输出时都收口为失败」，
 * 而真实模型的输出恰好是不可复现的那部分。</p>
 *
 * <p><b>两个方向必须分开固定</b>：判定逻辑默认放行（不命中阻止条件就放行），但调用失败、超时、
 * 响应不可解析一律按阻止处理。只测一侧会让另一侧被悄悄改掉，而改错方向在「默认放行」的取向下
 * 就是安全漏洞。</p>
 */
class LlmPermissionClassifierTest {

    /** 桩提示词：必须带齐四个用户规则占位符，否则装配期就会拒绝。 */
    private static final String PROMPT = "你是安全监控。\n"
            + ClassifierRuleBlocks.ENVIRONMENT_SLOT + "\n"
            + ClassifierRuleBlocks.HARD_DENY_SLOT + "\n"
            + ClassifierRuleBlocks.SOFT_DENY_SLOT + "\n"
            + ClassifierRuleBlocks.ALLOW_SLOT + "\n";

    // ------------------ 响应解析 ------------------

    @Test
    void parsesBlockNo() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict(
                "<block>no</block><reason>项目内读取文件</reason>");

        assertFalse(verdict.block(), "block=no 表示放行");
        assertEquals("项目内读取文件", verdict.reason());
    }

    @Test
    void parsesBlockYes() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict(
                "<block>yes</block><reason>会改写 git 历史</reason>");

        assertTrue(verdict.block());
        assertEquals("会改写 git 历史", verdict.reason());
    }

    @Test
    void parsesBlockMarkersInsideProse() throws Exception {
        // 模型常加前言或代码围栏；标记本身足以定位，不要求整段是纯标记。
        var verdict = LlmPermissionClassifier.parseVerdict(
                "判断结果如下：\n```\n<block>no</block><reason>只读查询</reason>\n```\n完毕。");

        assertFalse(verdict.block());
        assertEquals("只读查询", verdict.reason());
    }

    @Test
    void blockValueIsCaseInsensitive() throws Exception {
        assertTrue(LlmPermissionClassifier.parseVerdict("<block>YES</block>").block());
    }

    @Test
    void missingBlockMarkerIsRejected() {
        // 默认放行取向下最容易出错的地方：解析不出来绝不能当成放行。
        assertThrows(IOException.class,
                () -> LlmPermissionClassifier.parseVerdict("这个操作是安全的"));
    }

    @Test
    void invalidBlockValueIsRejected() {
        assertThrows(IOException.class,
                () -> LlmPermissionClassifier.parseVerdict("<block>maybe</block>"));
    }

    @Test
    void emptyBlockValueIsRejected() {
        assertThrows(IOException.class,
                () -> LlmPermissionClassifier.parseVerdict("<block></block>"));
    }

    @Test
    void blankResponseIsRejected() {
        assertThrows(IOException.class, () -> LlmPermissionClassifier.parseVerdict(""));
    }

    @Test
    void missingReasonFallsBackToEmptyString() throws Exception {
        var verdict = LlmPermissionClassifier.parseVerdict("<block>no</block>");

        assertFalse(verdict.block());
        assertEquals("", verdict.reason());
    }

    // ------------------ 输入组装 ------------------

    @Test
    void trustedIntentAndActionBothReachTheModel() throws Exception {
        // 判定以用户意图为最终信号，历史是意图的唯一来源；漏接线会让分类器看不到意图，
        // 而这种降级从外部观察不出来，必须用测试固定。
        // 同时要固定「待评估动作被单独标出」：历史与动作在同一段文本里，不标出的话
        // 模型可能去评历史里已经发生过的动作。
        StringBuilder seen = new StringBuilder();
        try (LlmPermissionClassifier classifier = classifier(messages -> {
            seen.append(messages.get(messages.size() - 1).content());
            return answer("<block>no</block>");
        }, 5_000)) {
            classifier.classify(new PermissionClassifier.Request(
                    "write_file", "{\"path\":\"a.txt\"}", "/tmp", "auto",
                    "<intent_context>\nUser: 帮我建个文件\n</intent_context>",
                    com.devcli.policy.PermissionRuleSet.EMPTY));
        }

        String prompt = seen.toString();
        assertTrue(prompt.contains("待评估的动作"), prompt);
        assertTrue(prompt.contains("write_file"), prompt);
        assertTrue(prompt.contains("<intent_context>"), prompt);
        assertTrue(prompt.contains("帮我建个文件"), prompt);
    }

    @Test
    void userRulesReachTheSystemPromptNotTheUserMessage() throws Exception {
        // 参照实现把用户规则当作系统提示词的一部分，让模型把它读成判定依据而不是待评估内容。
        // 四类规则各有自己的落点，位置错了规则的意思就变了，所以逐段核对。
        StringBuilder system = new StringBuilder();
        StringBuilder user = new StringBuilder();
        try (LlmPermissionClassifier classifier = classifier(messages -> {
            for (LlmClient.Message message : messages) {
                if ("system".equals(message.role())) {
                    system.append(message.content());
                } else {
                    user.append(message.content());
                }
            }
            return answer("<block>no</block>");
        }, 5_000)) {
            classifier.classify(new PermissionClassifier.Request(
                    "write_file", "{\"path\":\"a.txt\"}", "/tmp", "auto", "（无历史）",
                    com.devcli.policy.PermissionRuleSet.parse(
                            List.of("write_file(.env)"),
                            List.of("delete_files(**/**)"),
                            List.of("Edit(src/**)"),
                            List.of("可信域名：github.com"))));
        }

        String systemPrompt = system.toString();
        assertTrue(systemPrompt.contains("write_file(.env)"), systemPrompt);
        assertTrue(systemPrompt.contains("delete_files(**/**)"), systemPrompt);
        assertTrue(systemPrompt.contains("edit_file(src/**)"),
                "放行规则注入时应已归一到原生工具名: " + systemPrompt);
        assertTrue(systemPrompt.contains("可信域名：github.com"), systemPrompt);
        assertFalse(systemPrompt.contains(ClassifierRuleBlocks.HARD_DENY_SLOT),
                "占位符必须已被替换，残留意味着模型会把它当成一段没填进来的文本");
        assertFalse(user.toString().contains("write_file(.env)"),
                "规则不该出现在用户消息里：那会让模型把它当成待评估内容");
    }

    @Test
    void promptWithoutRuleSlotsIsRejectedAtAssembly() {
        // 用户级提示词覆盖可以换掉整份提示词。占位符一旦丢了，用户写的四类规则一条都进不去，
        // 而外部看不出任何异常——这种静默失效必须在装配期报出来。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LlmPermissionClassifier(() -> null, "没有占位符的提示词", 5_000));

        assertTrue(failure.getMessage().contains(ClassifierRuleBlocks.ALLOW_SLOT), failure.getMessage());
        assertTrue(failure.getMessage().contains("占位符"), failure.getMessage());
    }

    // ------------------ 调用失败 ------------------

    @Test
    void modelAnswerIsParsedIntoVerdict() throws Exception {
        try (LlmPermissionClassifier classifier = classifier(
                messages -> answer("<block>yes</block><reason>删除操作</reason>"), 5_000)) {
            var verdict = classifier.classify(request());

            assertTrue(verdict.block());
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
                     new LlmPermissionClassifier(() -> null, PROMPT, 5_000)) {
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
            return answer("<block>no</block>");
        }, 200)) {
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> classifier.classify(request()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(failure.getMessage().contains("超时"), failure.getMessage());
            assertTrue(elapsedMillis < 5_000,
                    "超时应立即失败关闭，实际耗时 " + elapsedMillis + "ms");
        }
    }

    // ------------------ 超时消息 ------------------

    @Test
    void timeoutMessageTellsUserHowToAdjust() {
        // 用户看到这条消息时已经在 auto 下被拒了，必须不读文档就知道能调、怎么调。
        String message = LlmPermissionClassifier.timeoutMessage(30_000L);
        assertTrue(message.contains("30 秒"), message);
        assertTrue(message.contains("devcli.permission.classifier.timeout.seconds"), message);
        assertTrue(message.contains("DEVCLI_PERMISSION_CLASSIFIER_TIMEOUT_SECONDS"), message);
        assertTrue(message.contains("非 .env"),
                "必须写明只认系统属性与环境变量：项目里模型配置走 .env，用户会自然以为通用");
        assertTrue(message.contains("工具批次预算"),
                "必须带预算安全阀：超时设到预算之上会让分类器永不启动");
    }

    @Test
    void timeoutMessageKeepsSubSecondTimeoutsReadable() {
        // 超时值可能是 200 这类非整秒，不能渲染成「0 秒」。
        assertTrue(LlmPermissionClassifier.timeoutMessage(200L).contains("200 毫秒"));
    }

    private static PermissionClassifier.Request request() {
        return new PermissionClassifier.Request("write_file", "{\"path\":\"a.txt\"}", "/tmp", "auto",
                "<intent_context>\nUser: 建一个文件\n</intent_context>",
                com.devcli.policy.PermissionRuleSet.EMPTY);
    }

    private static LlmPermissionClassifier classifier(ChatBehavior behavior, long timeoutMillis) {
        return new LlmPermissionClassifier(() -> new StubClient(behavior), PROMPT, timeoutMillis);
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
