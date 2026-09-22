package com.devcli.hitl;

import com.devcli.policy.PermissionRuleSet;
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
 * 规则层接入审批链后的端到端行为。
 *
 * <p>覆盖三类必须成立的边界：{@code deny} 规则先于一切授权生效、{@code allow} 规则不再发起
 * 审批、以及规则不能越过策略硬边界。同时确认空规则集时审批链行为与引入规则层之前一致。</p>
 */
class PermissionRuleApprovalTest {

    private static HitlToolRegistry registry(Path projectRoot, RecordingHandler handler,
                                             List<String> deny, List<String> ask, List<String> allow) {
        HitlToolRegistry registry = new HitlToolRegistry(handler)
                .withPermissionRules(PermissionRuleSet.parse(deny, ask, allow));
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }

    @Test
    void denyRuleRejectsWriteWithoutAskingTheUser(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of("write_file(*.txt)"), List.of(), List.of());

        var output = registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

        assertEquals(ToolStatus.REJECTED, output.status());
        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode());
        assertTrue(output.text().contains("[规则] 已拒绝"), output.text());
        assertEquals(0, handler.requestCount(), "拒绝规则不应再打扰用户");
        assertFalse(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void allowRuleSkipsApprovalEntirely(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of(), List.of(), List.of("write_file(*.txt)"));

        var output = registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

        assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        assertEquals(0, handler.requestCount(), "放行规则命中时不应发起审批");
        assertTrue(Files.exists(tempDir.resolve("a.txt")));
    }

    @Test
    void allowRuleDoesNotCoverPathOutsideItsSpecifier(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of(), List.of(), List.of("write_file(src/**)"));

        registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

        assertEquals(1, handler.requestCount(), "规则未命中时必须回落到人工审批");
    }

    @Test
    void denyRuleBeatsAllowRuleAndTaskGrant(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler,
                List.of("write_file(*.txt)"), List.of(), List.of("write_file(*.txt)"));

        var output = registry.runWithTaskGrant(
                new com.devcli.policy.TaskGrant(List.of("**"), false),
                () -> registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"));

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertEquals(0, handler.requestCount());
    }

    @Test
    void askRuleForcesApprovalEvenWhenTaskGrantWouldAllow(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of(), List.of("write_file(*.txt)"), List.of());

        var output = registry.runWithTaskGrant(
                new com.devcli.policy.TaskGrant(List.of("**"), false),
                () -> registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}"));

        assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
        assertEquals(1, handler.requestCount(), "询问规则必须强制人工审批，不能被任务授权短路");
    }

    @Test
    void broadAllowRuleCannotOverridePolicyBoundary(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of(), List.of(), List.of("write_file(**)"));
        // 目标名唯一：共享临时目录里可能残留同名文件，断言必须只对自己创建的目标成立
        String outsideName = "rule-boundary-" + java.util.UUID.randomUUID() + ".txt";
        Path outside = tempDir.resolveSibling(outsideName);

        var output = registry.executeToolOutput("write_file",
                "{\"path\":\"../" + outsideName + "\",\"content\":\"x\"}");

        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), output.text());
        assertTrue(output.text().contains("[策略]"), output.text());
        assertFalse(Files.exists(outside), "放行规则不能越过项目根围栏");
    }

    @Test
    void emptyRuleSetKeepsApprovalChainBehaviour(@TempDir Path tempDir) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of(), List.of(), List.of());

        registry.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

        assertEquals(1, handler.requestCount(), "无规则时必须保持逐次人工审批");
    }

    @Test
    void rulesAreInheritedByProjectFork(@TempDir Path tempDir) throws Exception {
        Path forkRoot = tempDir.resolve("fork");
        Files.createDirectories(forkRoot);
        RecordingHandler handler = new RecordingHandler();
        HitlToolRegistry registry = registry(tempDir, handler, List.of(), List.of(), List.of("write_file(*.txt)"));

        try (ToolRegistry fork = registry.forkForProject(forkRoot)) {
            var output = fork.executeToolOutput("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}");

            assertEquals(ToolStatus.SUCCESS, output.status(), output.text());
            assertEquals(0, handler.requestCount(), "fork 必须继承规则集");
        }
    }

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
