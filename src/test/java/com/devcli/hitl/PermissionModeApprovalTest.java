package com.devcli.hitl;

import com.devcli.policy.PermissionMode;
import com.devcli.policy.PermissionRuleSet;
import com.devcli.policy.TaskGrant;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限模式接入审批链后的端到端行为。
 *
 * <p>模式层是求值链的固定一环，位置本身带语义：{@code bypassPermissions} 与 {@code acceptEdits}
 * 在规则层之后、任务授权之前短路；{@code dontAsk} 在所有放行路径之后收口。因此本测试同时钉住
 * 「模式能放行什么」与「模式放不过什么」——后者才是安全边界。</p>
 *
 * <p>本测试只覆盖非隔离路径下的工具（{@code write_file} / {@code delete_files}）。
 * 主机命令走 {@code reviewHostCommand}，不经过这里，见类尾注释。</p>
 */
class PermissionModeApprovalTest {

    private static HitlToolRegistry registry(Path projectRoot, RecordingHandler handler,
                                             PermissionMode mode,
                                             List<String> hardDeny, List<String> softDeny,
                                             List<String> allow) {
        HitlToolRegistry registry = new HitlToolRegistry(handler)
                .withPermissionRules(PermissionRuleSet.parse(hardDeny, softDeny, allow, List.of()))
                .withPermissionMode(mode);
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }

    private static HitlToolRegistry registry(Path projectRoot, RecordingHandler handler, PermissionMode mode) {
        return registry(projectRoot, handler, mode, List.of(), List.of(), List.of());
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

    // ------------------ 本次修复的缺陷：规则层不再挂在审批开关之后 ------------------

    @Test
    void denyRuleAppliesEvenWhenApprovalChannelIsDisabled(@TempDir Path tempDir) {
        // 规则层曾经排在 hitlHandler.isEnabled() 早退之后，而 HITL 默认关闭——
        // 用户写的 deny 规则默认是一段死文本。这条就是那个缺陷的回归测试。
        RecordingHandler handler = new RecordingHandler();
        handler.setEnabled(false);
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DEFAULT,
                List.of("write_file(*.txt)"), List.of(), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[规则]"), output.text());
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    // ------------------ bypassPermissions ------------------

    @Test
    void bypassPermissionsSkipsApprovalForEveryTool(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "x");
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.BYPASS_PERMISSIONS);

        var write = registry.executeToolOutput("write_file", writeArgs("b.txt"));
        var delete = registry.executeToolOutput("delete_files", paths("a.txt"));

        assertEquals(ToolStatus.SUCCESS, write.status(), write.text());
        assertEquals(ToolStatus.SUCCESS, delete.status(), delete.text());
        assertEquals(0, handler.requestCount(), "bypassPermissions 不应发起审批");
        assertTrue(Files.exists(tempDir.resolve("b.txt")));
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void bypassPermissionsCannotOverrideDenyRule(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.BYPASS_PERMISSIONS,
                List.of("write_file(*.txt)"), List.of(), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, handler.requestCount());
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void bypassPermissionsCannotOverrideExplicitAskRule(@TempDir Path tempDir) {
        // 显式 soft_deny 表达的是用户边界，非 auto 模式不能把它改写成放行
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.BYPASS_PERMISSIONS,
                List.of(), List.of("write_file(*.txt)"), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        assertEquals(1, handler.requestCount(), "显式询问规则必须仍然发起审批");
    }

    @Test
    void bypassPermissionsCannotOverridePolicyBoundary(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.BYPASS_PERMISSIONS);
        // 目标名唯一：共享临时目录里可能残留同名文件，断言必须只对自己创建的目标成立
        String outsideName = "mode-boundary-" + java.util.UUID.randomUUID() + ".txt";
        Path outside = tempDir.resolveSibling(outsideName);

        var output = registry.executeToolOutput("write_file", writeArgs("../" + outsideName));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[策略]"), output.text());
        assertFalse(Files.exists(outside), "模式不能越过项目根围栏");
    }

    // ------------------ acceptEdits ------------------

    @Test
    void acceptEditsAutoAllowsEdits(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.ACCEPT_EDITS);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        assertEquals(0, handler.requestCount(), "编辑类工具不应再打扰用户");
        assertTrue(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void acceptEditsStillAsksBeforeDeletingFiles(@TempDir Path tempDir) throws Exception {
        // delete_files 的资源槽是 PATH_LIST 而不是 PATH——用户说「接受编辑」时没有同意删文件
        Files.writeString(tempDir.resolve("a.txt"), "x");
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.ACCEPT_EDITS);

        registry.executeToolOutput("delete_files", paths("a.txt"));

        assertEquals(1, handler.requestCount(), "删除文件必须仍然询问");
    }

    @Test
    void acceptEditsStillRespectsDenyRule(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.ACCEPT_EDITS,
                List.of("write_file(*.txt)"), List.of(), List.of());

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, handler.requestCount());
    }

    // ------------------ dontAsk ------------------

    @Test
    void dontAskTurnsUnresolvedApprovalIntoDenial(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DONT_ASK);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolStatus.REJECTED, output.status(), output.text());
        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[模式] 已拒绝"), output.text());
        assertEquals(0, handler.requestCount(), "dontAsk 不该发起审批");
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void dontAskDoesNotRewriteExplicitAllowIntoDenial(@TempDir Path tempDir) {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DONT_ASK,
                List.of(), List.of(), List.of("write_file(*.txt)"));

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        assertEquals(0, handler.requestCount());
    }

    @Test
    void dontAskDoesNotEatTaskGrant(@TempDir Path tempDir) {
        // dontAsk 是收口而不是全面拒绝：已经明确授权过的动作不该被它改写
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DONT_ASK);

        var output = registry.runWithTaskGrant(
                new TaskGrant(List.of("**"), false),
                () -> registry.executeToolOutput("write_file", writeArgs("a.txt")));

        assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        assertEquals(0, handler.requestCount());
    }

    // ------------------ 审批通道不可用时 fail-closed ------------------

    @Test
    void missingApprovalChannelDeniesInsteadOfBlocking(@TempDir Path tempDir) {
        // 关掉审批通道后若仍走「问」的路径，会静默阻塞在 stdin；这里要求它直接拒绝
        RecordingHandler handler = new RecordingHandler();
        handler.setEnabled(false);
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DEFAULT);

        var output = registry.executeToolOutput("write_file", writeArgs("a.txt"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("没有可用的审批通道"), output.text());
        assertEquals(0, handler.requestCount());
    }

    // ------------------ 模式在 fork 之间共享 ------------------

    @Test
    void modeSwitchPropagatesToExistingFork(@TempDir Path tempDir) throws Exception {
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(forkRoot);
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, PermissionMode.DEFAULT);

        try (ToolRegistry fork = registry.forkForProject(forkRoot)) {
            registry.withPermissionMode(PermissionMode.BYPASS_PERMISSIONS);
            var output = fork.executeToolOutput("write_file", writeArgs("a.txt"));

            assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
            assertEquals(0, handler.requestCount(), "fork 与根注册表共享模式持有者");
            assertTrue(Files.exists(forkRoot.resolve("a.txt")));
        }
    }

    @Test
    void permissionModeDefaultsToAsking() {
        HitlToolRegistry registry = new HitlToolRegistry(new RecordingHandler());

        assertEquals(PermissionMode.DEFAULT, registry.currentPermissionMode());
    }

    // 未覆盖：execute_command 在非隔离路径下由 applyHitl 第一行早退到 reviewHostCommand，
    // 不经过规则层与模式短路。这是既有行为，不是本测试能固化的契约——修好之前不写测试，
    // 否则等于把缺口写成规格。见 docs/adr/0006 的「已知缺口」。

    /** 记录审批次数并一律批准的 handler。 */
    private static final class RecordingHandler implements HitlHandler {
        private final List<String> requests = new ArrayList<>();
        private boolean enabled = true;

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requests.add(request.toolName());
            return ApprovalResult.approve();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        int requestCount() {
            return requests.size();
        }
    }
}
