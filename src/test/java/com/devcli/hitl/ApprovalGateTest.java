package com.devcli.hitl;

import com.devcli.policy.PathGuard;
import com.devcli.policy.TaskGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批三值判定的确定性语义：策略硬边界 → 用户授权范围 → 人工审批。
 */
class ApprovalGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path projectRoot;

    private ApprovalGate.PathScope pathScope() {
        PathGuard guard = new PathGuard(projectRoot.toString());
        Path root = projectRoot.toAbsolutePath().normalize();
        return new ApprovalGate.PathScope() {
            @Override
            public Path resolve(String path) {
                return guard.resolveSafe(path);
            }

            @Override
            public String relativeKey(String path) {
                try {
                    return root.relativize(guard.resolveSafe(path)).toString().replace('\\', '/');
                } catch (RuntimeException unresolved) {
                    return null;
                }
            }
        };
    }

    private ApprovalGate.Result decide(String tool, String argumentsJson, TaskGrant grant) throws Exception {
        return ApprovalGate.decide(tool, JSON.readTree(argumentsJson), grant, pathScope());
    }

    @Test
    void workspaceWriteStillNeedsApprovalWithoutGrant() throws Exception {
        assertEquals(ApprovalGate.Decision.HITL,
                decide("write_file", "{\"path\":\"src/A.java\"}", TaskGrant.NONE).decision());
    }

    @Test
    void workspaceGrantAutoAllowsWriteInsideProject() throws Exception {
        ApprovalGate.Result result = decide("write_file",
                "{\"path\":\"src/A.java\"}", TaskGrant.WORKSPACE_WRITES);

        assertEquals(ApprovalGate.Decision.ALLOW, result.decision());
        assertTrue(result.reason().contains("已授权"), result.reason());
    }

    @Test
    void editFileFollowsTheSameResourceSlot() throws Exception {
        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("edit_file", "{\"path\":\"src/A.java\",\"old_string\":\"a\",\"new_string\":\"b\"}",
                        TaskGrant.WORKSPACE_WRITES).decision());
    }

    @Test
    void grantNeverWidensPolicyBoundary() throws Exception {
        ApprovalGate.Result result = decide("write_file",
                "{\"path\":\"../../outside.txt\"}", TaskGrant.WORKSPACE_WRITES_AND_COMMANDS);

        assertEquals(ApprovalGate.Decision.DENY, result.decision(),
                "越界写必须由策略拒绝，授权不能放行");
        assertTrue(result.reason().contains("策略边界"), result.reason());
    }

    /** 资源粒度授权：只有落在已授权 glob 内的写才自动放行，其余回到人工审批。 */
    @Test
    void scopedWriteGrantOnlyCoversGrantedGlobs() throws Exception {
        TaskGrant scoped = new TaskGrant(List.of("src/**"), false);

        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("write_file", "{\"path\":\"src/A.java\"}", scoped).decision(),
                "授权 glob 内的写应自动放行");
        ApprovalGate.Result outside = decide("write_file", "{\"path\":\"test/B.java\"}", scoped);
        assertEquals(ApprovalGate.Decision.HITL, outside.decision(),
                "超出授权 glob 的写必须回到人工审批");
        assertTrue(outside.reason().contains("不在本次授权范围内"), outside.reason());
    }

    @Test
    void scopedWriteGrantStillDeniesPolicyEscape() throws Exception {
        TaskGrant wholeProject = new TaskGrant(List.of("**"), false);

        assertEquals(ApprovalGate.Decision.DENY,
                decide("write_file", "{\"path\":\"../../outside.txt\"}", wholeProject).decision(),
                "授权整个项目也不放宽项目根围栏");
    }

    @Test
    void scopedWriteGrantRejectsAbsoluteOutputPath() throws Exception {
        TaskGrant scoped = new TaskGrant(List.of("src/**"), false);

        assertEquals(ApprovalGate.Decision.DENY,
                decide("write_file", "{\"path\":\"" + projectRoot.getParent().resolve("x.txt")
                        .toString().replace("\\", "\\\\") + "\"}", scoped).decision(),
                "绝对越界路径由策略拒绝");
    }

    @Test
    void missingResourceArgumentFallsBackToApproval() throws Exception {
        assertEquals(ApprovalGate.Decision.HITL,
                decide("write_file", "{}", TaskGrant.WORKSPACE_WRITES).decision());
    }

    @Test
    void projectBuildCommandIsAutoAllowedUnderCommandGrant() throws Exception {
        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("execute_command", "{\"command\":\"mvn -q test\"}",
                        TaskGrant.PROJECT_COMMANDS).decision());
    }

    @Test
    void commandStillNeedsApprovalWithoutGrant() throws Exception {
        assertEquals(ApprovalGate.Decision.HITL,
                decide("execute_command", "{\"command\":\"mvn -q test\"}", TaskGrant.NONE).decision());
    }

    @Test
    void remoteMutationIsNotCoveredByProjectCommandGrant() throws Exception {
        ApprovalGate.Result result = decide("execute_command",
                "{\"command\":\"git push origin main\"}", TaskGrant.WORKSPACE_WRITES_AND_COMMANDS);

        assertEquals(ApprovalGate.Decision.HITL, result.decision(),
                "git push 属于远程副作用，必须先人工确认");
        assertTrue(result.reason().contains("白名单"), result.reason());
    }

    @Test
    void dangerousCommandIsDeniedRegardlessOfGrant() throws Exception {
        ApprovalGate.Result result = decide("execute_command",
                "{\"command\":\"rm -rf /\"}", TaskGrant.WORKSPACE_WRITES_AND_COMMANDS);

        assertEquals(ApprovalGate.Decision.DENY, result.decision());
        assertTrue(result.reason().contains("黑名单"), result.reason());
    }

    @Test
    void createProjectFollowsItsOwnResourceSlot() throws Exception {
        TaskGrant scoped = new TaskGrant(List.of("newproj/**"), false);

        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("create_project", "{\"name\":\"newproj\",\"type\":\"java\"}", scoped).decision(),
                "授权目录覆盖该目录本身，create_project 应自动放行");

        ApprovalGate.Result outside = decide("create_project",
                "{\"name\":\"otherproj\",\"type\":\"java\"}", scoped);
        assertEquals(ApprovalGate.Decision.HITL, outside.decision());
        assertTrue(outside.reason().contains("不在本次授权范围内"), outside.reason());
    }

    @Test
    void createProjectStillDeniesPolicyEscape() throws Exception {
        assertEquals(ApprovalGate.Decision.DENY,
                decide("create_project", "{\"name\":\"../escape\",\"type\":\"java\"}",
                        TaskGrant.WORKSPACE_WRITES).decision());
    }

    @Test
    void webFetchNeedsAuthorizationForUnfamiliarDomain() throws Exception {
        assertEquals(ApprovalGate.Decision.HITL,
                decide("web_fetch", "{\"url\":\"https://example.com/doc\"}", TaskGrant.NONE).decision(),
                "未授权时陌生域名必须人工确认");
    }

    @Test
    void webFetchAutoAllowsAuthorizedDomainAndSubdomain() throws Exception {
        TaskGrant grant = new TaskGrant(List.of(), List.of("github.com"), false);

        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("web_fetch", "{\"url\":\"https://github.com/a/b\"}", grant).decision());
        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("web_fetch", "{\"url\":\"https://api.github.com/repos\"}", grant).decision());

        ApprovalGate.Result other = decide("web_fetch",
                "{\"url\":\"https://example.com/\"}", grant);
        assertEquals(ApprovalGate.Decision.HITL, other.decision());
        assertTrue(other.reason().contains("陌生域名"), other.reason());
    }

    @Test
    void webFetchHostIsTakenAfterUserInfoSoItCannotBeSpoofed() throws Exception {
        TaskGrant grant = new TaskGrant(List.of(), List.of("github.com"), false);

        // userinfo 只是 URL 的凭证段，真实主机是 @ 之后的部分
        assertEquals(ApprovalGate.Decision.HITL,
                decide("web_fetch", "{\"url\":\"https://github.com@evil.test/x\"}", grant).decision(),
                "userinfo 不得把授权域名当成真实主机");

        // 授权域名作为前缀出现在攻击者域名里也不算命中
        assertEquals(ApprovalGate.Decision.HITL,
                decide("web_fetch", "{\"url\":\"https://github.com.evil.test/x\"}", grant).decision());
    }

    @Test
    void webFetchNormalizesCaseAndTrailingDot() throws Exception {
        TaskGrant grant = new TaskGrant(List.of(), List.of("github.com"), false);

        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("web_fetch", "{\"url\":\"https://GitHub.COM./a\"}", grant).decision(),
                "域名大小写与末尾点应归一化后匹配");
    }

    @Test
    void webFetchIgnoresPortForDomainAuthorization() throws Exception {
        TaskGrant grant = new TaskGrant(List.of(), List.of("github.com"), false);

        assertEquals(ApprovalGate.Decision.ALLOW,
                decide("web_fetch", "{\"url\":\"https://github.com:8443/a\"}", grant).decision(),
                "授权按域名判定，端口不参与匹配");
    }

    @Test
    void webFetchDeniesDisallowedSchemeWithoutPrompting() throws Exception {
        assertEquals(ApprovalGate.Decision.DENY,
                decide("web_fetch", "{\"url\":\"ftp://example.com/x\"}",
                        TaskGrant.NONE).decision(),
                "必然被策略拒绝的 scheme 不应先打扰用户");
    }

    @Test
    void webFetchWithoutHostFallsBackToApproval() throws Exception {
        assertEquals(ApprovalGate.Decision.HITL,
                decide("web_fetch", "{\"url\":\"https:///no-host\"}",
                        new TaskGrant(List.of(), List.of("example.com"), false)).decision());
    }

    @Test
    void undeclaredToolsAlwaysFallBackToApproval() throws Exception {
        for (String tool : new String[]{
                "revert_turn", "mcp__calendar__create_event"}) {
            assertEquals(ApprovalGate.Decision.HITL,
                    decide(tool, "{\"path\":\"src/A.java\"}",
                            TaskGrant.WORKSPACE_WRITES_AND_COMMANDS).decision(),
                    tool + " 没有声明参数级资源槽，不能自动放行");
        }
    }
}
