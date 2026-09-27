package com.devcli.hitl;

import com.devcli.llm.LlmClient;
import com.devcli.policy.PermissionMode;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code auto} 模式接入审批链后的行为。
 *
 * <p>分类器在 {@code auto} 模式下是判定者，但它拿到的动作有两类：走完整条链、仅仅因为默认策略
 * 才需要询问的；以及命中用户 {@code allow} / {@code soft_deny} 规则的——那个模式下规则层刻意
 * 不短路，规则作为分类器输入参与同一次判定。因此本测试要固定两件事：<b>该到它手里的</b>
 * （未决动作与两类用户规则）确实到了，<b>不该到它手里的</b>（策略硬边界、{@code hard_deny}、
 * 删除确认、补丁与回滚确认、逐次审批）一个都没到；判定失败也不该变成放行。</p>
 *
 * <p>全部用注入桩，不依赖真实模型：分类器引入非确定性，测试必须能确定地复现失败路径。</p>
 */
class PermissionClassifierTest {

    private static final String PROMPT = "你是安全监控。\n"
            + ClassifierRuleBlocks.ENVIRONMENT_SLOT + "\n"
            + ClassifierRuleBlocks.HARD_DENY_SLOT + "\n"
            + ClassifierRuleBlocks.SOFT_DENY_SLOT + "\n"
            + ClassifierRuleBlocks.ALLOW_SLOT + "\n";

    private static HitlToolRegistry registry(Path projectRoot, RecordingHandler handler,
                                             PermissionMode mode, PermissionClassifier classifier,
                                             List<String> hardDeny, List<String> softDeny,
                                             List<String> allow) {
        HitlToolRegistry registry = new HitlToolRegistry(handler)
                .withPermissionRules(PermissionRuleSet.parse(hardDeny, softDeny, allow, List.of()))
                .withPermissionMode(mode)
                .withPermissionClassifier(classifier);
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }

    private static HitlToolRegistry registry(Path projectRoot, RecordingHandler handler,
                                             PermissionMode mode, PermissionClassifier classifier) {
        return registry(projectRoot, handler, mode, classifier, List.of(), List.of(), List.of());
    }

    private static String writeArgs(String path) {
        return "{\"path\":\"" + path + "\",\"content\":\"x\"}";
    }

    private static String paths(String... values) {
        StringBuilder json = new StringBuilder("{\"paths\":[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(values[index]).append('"');
        }
        return json.append("]}").toString();
    }

    private static String patchArgs(String... lines) {
        String patch = String.join("\n", lines)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"patch\":\"" + patch + "\"}";
    }

    // ------------------ 分类器确实接管了「默认要问」的动作 ------------------

    @Test
    void classifierAllowExecutesWithoutAsking(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("项目内新建文件"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(1, classifier.callCount());
        assertEquals(0, handler.requestCount(), "分类器已判定，不应再走人工审批");
    }

    @Test
    void classifierDenyRejectsWithoutAsking(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.block("写入位置无法从参数确认"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[分类器]"), output.text());
        assertTrue(output.text().contains("写入位置无法从参数确认"), output.text());
        assertEquals(0, handler.requestCount());
    }

    @Test
    void classifierReceivesActionTrustedIntentAndRules(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("ok"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier,
                List.of(), List.of("write_file(notes/**)"), List.of())
                .withTrustedIntentContext(() -> "<intent_context>\nUser: 建一个文件\n</intent_context>");

        registry.executeToolOutput("write_file", writeArgs("a.txt"));

        PermissionClassifier.Request seen = classifier.lastRequest();
        assertEquals("write_file", seen.toolName());
        assertEquals(writeArgs("a.txt"), seen.argumentsJson());
        assertEquals(tempDir.toString(), seen.projectPath());
        assertEquals("auto", seen.mode());
        assertTrue(seen.intentContext().contains("建一个文件"),
                "可信意图必须随请求送达：漏接线会让分类器看不到用户意图");
        assertEquals(1, seen.rules().softDeny().size(),
                "规则必须随请求送达：auto 模式下规则层不短路，规则全靠这里进判定");
    }

    // ------------------ auto 模式下规则层让位给分类器 ------------------

    @Test
    void autoModeSendsAllowRuleToClassifierInsteadOfShortCircuiting(@TempDir Path tempDir) {
        // 允许例外在参照实现里是「命中即必须放行」，但两个例外情形（伪装成例外的可疑动作、
        // 用户明确设下的边界）只有看得到历史的分类器判得出来。规则若在此短路，分类器永远
        // 看不到它，那两个情形就都没法生效。
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("用户允许改 src"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier,
                List.of(), List.of(), List.of("write_file(src/**)"));

        var output = registry.executeToolOutput("write_file", writeArgs("src/a.txt"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(1, classifier.callCount(), "放行规则必须交给分类器，不能自己短路");
    }

    @Test
    void autoModeSendsSoftDenyRuleToClassifierInsteadOfPrompting(@TempDir Path tempDir) {
        // soft_deny 的语义是「明确且具体的用户意图可以清除它」。判断意图需要历史，
        // 所以在 auto 模式下它不是「一定询问」，而是分类器的一条输入。
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.block("用户设了边界"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier,
                List.of(), List.of("write_file(notes/**)"), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("notes/todo.md"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[分类器]"), output.text());
        assertEquals(1, classifier.callCount(), "软阻止规则必须交给分类器");
        assertEquals(0, handler.requestCount(), "auto 模式下不应因为软阻止规则而弹审批");
    }

    @Test
    void nonAutoModeStillShortCircuitsSoftDenyToApproval(@TempDir Path tempDir) {
        // 其余模式没有分类器可用，软阻止只能退回「强制人工审批」——规则层的行为逐字不变。
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("不该被调用"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DEFAULT, classifier,
                List.of(), List.of("write_file(notes/**)"), List.of());

        registry.executeToolOutput("write_file", writeArgs("notes/todo.md"));

        assertEquals(1, handler.requestCount(), "非 auto 模式下软阻止规则仍然强制人工审批");
        assertEquals(0, classifier.callCount(), "非 auto 模式不启动分类器");
    }

    @Test
    void hardDenyShortCircuitsInAutoModeWithoutCallingClassifier(@TempDir Path tempDir) {
        // 硬阻止的判定结果与分类器的硬阻止检查完全相同（无条件阻止），短路不改变结论，
        // 却省掉一次模型调用，也不受端点可用性影响。规则本身仍会进提示词——分类器需要它
        // 才能识别「这次委派要求子代理去做硬阻止清单里的动作」这类间接违规。
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("不该被调用"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier,
                List.of("write_file(notes/**)"), List.of(), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("notes/todo.md"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[规则]"), output.text());
        assertEquals(0, classifier.callCount(), "硬阻止必须自己短路，不依赖模型可用性");
    }

    @Test
    void allowRuleDoesNotClearIndependentSafetyChecks(@TempDir Path tempDir) {
        // 删除确认、命令删除、补丁与回滚确认、逐次审批是各自独立的安全要求，不是规则层的
        // 短路出口。分类器接管规则判定，不能顺带把这些也接管过去——一条宽泛的 allow 规则
        // 也不该让工作区回滚免于人工确认。
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("不该被调用"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier,
                List.of(), List.of(), List.of("revert_turn"));

        registry.executeToolOutput("revert_turn", "{\"offset\":1}");

        assertEquals(1, handler.requestCount(), "回滚确认必须仍然人工审批，放行规则不能免除它");
        assertEquals(0, classifier.callCount(), "独立安全要求不进分类器");
    }

    // ------------------ 失败路径：一律拒绝，绝不猜测 ------------------

    @Test
    void classifierFailureDeniesInsteadOfAllowing(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(new IOException("模型调用超时"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[分类器]"), output.text());
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("a.txt")),
                "分类器不可用时不得留下副作用");
    }

    @Test
    void consecutiveFailuresExitAutoModeAndFallBackToDefault(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier().always(new IOException("模型不可达"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        for (int attempt = 0; attempt < 3; attempt++) {
            registry.executeToolOutput("write_file", writeArgs("a" + attempt + ".txt"));
        }

        assertEquals(PermissionMode.DEFAULT, registry.currentPermissionMode(),
                "连续失败达阈值后必须退出 auto，而不是继续每次都等一次超时");
        assertEquals(3, classifier.callCount());

        // 退出后回落 default：下一次动作走人工询问，不再调分类器
        var afterExit = registry.executeToolOutput("write_file", writeArgs("b.txt"));
        assertNotEquals(ToolErrorCode.POLICY_DENIED, afterExit.errorCode(), afterExit.text());
        assertEquals(3, classifier.callCount(), "已退出 auto，不应再调分类器");
        assertEquals(1, handler.requestCount(), "退出后应回到逐个询问");
    }

    @Test
    void consecutiveFailuresAcrossProjectForksExitAutoMode(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier().always(new IOException("模型不可达"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);
        Path firstRoot = java.nio.file.Files.createDirectory(tempDir.resolve("fork-a"));
        Path secondRoot = java.nio.file.Files.createDirectory(tempDir.resolve("fork-b"));

        registry.executeToolOutput("write_file", writeArgs("root.txt"));
        try (ToolRegistry first = registry.forkForProject(firstRoot);
             ToolRegistry second = registry.forkForProject(secondRoot)) {
            first.executeToolOutput("write_file", writeArgs("first.txt"));
            second.executeToolOutput("write_file", writeArgs("second.txt"));
        }

        assertEquals(PermissionMode.DEFAULT, registry.currentPermissionMode(),
                "同一会话的项目 fork 必须共享分类器健康状态");
        assertEquals(3, classifier.callCount());
    }

    @Test
    void successResetsConsecutiveFailureCount(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier().script(
                new IOException("失败 1"),
                new IOException("失败 2"),
                PermissionClassifier.Verdict.allow("这一次判出来了"),
                new IOException("失败 3"),
                new IOException("失败 4"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        for (int attempt = 0; attempt < 5; attempt++) {
            registry.executeToolOutput("write_file", writeArgs("a" + attempt + ".txt"));
        }

        assertEquals(PermissionMode.AUTO, registry.currentPermissionMode(),
                "中间成功过一次，计数应归零，连续失败数未达阈值");
    }

    @Test
    void leavingAutoResetsConsecutiveFailureCount(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier().always(new IOException("模型不可达"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        registry.executeToolOutput("write_file", writeArgs("first.txt"));
        registry.executeToolOutput("write_file", writeArgs("second.txt"));
        registry.withPermissionMode(PermissionMode.DEFAULT);
        registry.withPermissionMode(PermissionMode.AUTO);
        registry.executeToolOutput("write_file", writeArgs("third.txt"));

        assertEquals(PermissionMode.AUTO, registry.currentPermissionMode(),
                "离开 auto 已经中断连续失败序列，重新进入后应重新计数");
    }

    // ------------------ 分类器拿不到硬拒绝或强制逐次确认的动作 ------------------

    @Test
    void hardDenyRuleNeverReachesClassifier(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("分类器想放行"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier,
                List.of("write_file(*.txt)"), List.of(), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[规则]"), output.text());
        assertEquals(0, classifier.callCount(), "规则层已判定，分类器不该被调用");
    }

    @Test
    void deleteApprovalNeverReachesClassifier(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "x");
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("分类器想放行"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("delete_files", paths("a.txt"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, classifier.callCount(),
                "删除确认有独立于默认策略的询问理由，不该交给分类器");
        assertEquals(1, handler.requestCount());
    }

    @Test
    void applyPatchDeletionNeverReachesClassifier(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "x\n");
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("分类器想放行"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("apply_patch", patchArgs(
                "*** Begin Patch",
                "*** Delete File: a.txt",
                "*** End Patch"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, classifier.callCount(), "补丁删除必须固定走人工审批");
        assertEquals(1, handler.requestCount());
    }

    @Test
    void applyPatchMoveNeverReachesClassifier(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("old.txt"), "before\n");
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("分类器想放行"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("apply_patch", patchArgs(
                "*** Begin Patch",
                "*** Update File: old.txt",
                "*** Move to: new.txt",
                "@@",
                "-before",
                "+after",
                "*** End Patch"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, classifier.callCount(), "补丁改名必须固定走人工审批");
        assertEquals(1, handler.requestCount());
    }

    @Test
    void ordinaryApplyPatchStillUsesClassifier(@TempDir Path tempDir) throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("a.txt"), "before\n");
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("普通内容修改可自动审批"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        var output = registry.executeToolOutput("apply_patch", patchArgs(
                "*** Begin Patch",
                "*** Update File: a.txt",
                "@@",
                "-before",
                "+after",
                "*** End Patch"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(1, classifier.callCount(), "普通内容修改仍应保留 auto 的自动审批能力");
        assertEquals(0, handler.requestCount());
        assertEquals("after\n", java.nio.file.Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void revertTurnNeverReachesClassifier(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.allow("分类器想放行"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier);

        registry.executeToolOutput("revert_turn", "{\"offset\":1}");

        assertEquals(0, classifier.callCount(), "工作区回滚必须固定走人工审批");
        assertEquals(1, handler.requestCount());
    }

    // ------------------ 非 auto 模式不受影响 ------------------

    @Test
    void nonAutoModeNeverCallsClassifier(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        StubClassifier classifier = new StubClassifier()
                .always(PermissionClassifier.Verdict.block("分类器想拒绝"));
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DEFAULT, classifier);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertNotEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, classifier.callCount());
        assertEquals(1, handler.requestCount());
    }

    @Test
    void autoWithoutClassifierFailsClosed(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, null);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, handler.requestCount(), "auto 不得在分类器缺失时改变为人工询问语义");
    }

    @Test
    void classifierDoesNotStartWhenItsTimeoutExceedsToolBudget(@TempDir Path tempDir) {
        AtomicInteger modelCalls = new AtomicInteger();
        LlmClient client = new LlmClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                modelCalls.incrementAndGet();
                return new ChatResponse("assistant",
                        "{\"allow\":true,\"reason\":\"stub\"}", List.of(), 0, 0);
            }

            @Override
            public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                     StreamListener listener) {
                return chat(messages, tools);
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }

            @Override
            public String getProviderName() {
                return "stub-provider";
            }
        };
        RecordingHandler handler = new RecordingHandler();
        try (LlmPermissionClassifier classifier =
                     new LlmPermissionClassifier(() -> client, PROMPT, 120_000);
             HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.AUTO, classifier)) {

            var result = registry.executeTools(List.of(
                    new ToolRegistry.ToolInvocation("write", "write_file", writeArgs("a.txt"))))
                    .getFirst();

            assertEquals(ToolErrorCode.POLICY_DENIED, result.errorCode());
            assertEquals(0, modelCalls.get(), "预算不足时不应启动分类器请求");
            assertFalse(java.nio.file.Files.exists(tempDir.resolve("a.txt")));
        }
    }

    /** 按脚本返回判定或抛出失败的桩。 */
    private static final class StubClassifier implements PermissionClassifier {
        private final List<Request> requests = new ArrayList<>();
        private final List<Object> scripted = new ArrayList<>();
        private Object fallback = PermissionClassifier.Verdict.allow("stub");

        StubClassifier script(Object... steps) {
            scripted.addAll(List.of(steps));
            return this;
        }

        StubClassifier always(Object step) {
            this.fallback = step;
            return this;
        }

        @Override
        public Verdict classify(Request request) throws IOException {
            requests.add(request);
            Object step = scripted.isEmpty() ? fallback : scripted.remove(0);
            if (step instanceof IOException failure) {
                throw failure;
            }
            return (Verdict) step;
        }

        int callCount() {
            return requests.size();
        }

        Request lastRequest() {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    /** 记录审批次数并一律批准的 handler。 */
    private static final class RecordingHandler implements HitlHandler {
        private final List<String> requests = new ArrayList<>();

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests.add(request.toolName());
            return ApprovalResult.approve();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        int requestCount() {
            return requests.size();
        }
    }
}
