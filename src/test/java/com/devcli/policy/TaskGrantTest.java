package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskGrantTest {

    @Test
    void rejectsAbsoluteAndEscapingGlobs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of("/etc/**"), false),
                "授权路径必须是项目相对路径");
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of("../**"), false),
                "授权路径不能跳出项目根");
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of("src/../../**"), false));
    }

    @Test
    void normalizesAndDeduplicatesGlobs() {
        TaskGrant grant = new TaskGrant(List.of("./src/**", "src/**", " test/** ", "  "), false);

        assertEquals(List.of("src/**", "test/**"), grant.writeGlobs());
        assertTrue(grant.workspaceWrites());
        assertTrue(grant.summary().contains("src/**"));
    }

    @Test
    void emptyGlobsMeanNoWriteGrant() {
        TaskGrant grant = new TaskGrant(List.of(), true);

        assertFalse(grant.workspaceWrites());
        assertTrue(grant.projectCommands());
        assertFalse(grant.isEmpty());
        assertTrue(grant.summary().contains("构建与测试"));
        assertFalse(grant.summary().contains("写入"));
    }

    @Test
    void withWriteGlobsKeepsCommandGrant() {
        TaskGrant grant = TaskGrant.PROJECT_COMMANDS.withWriteGlobs(List.of("src/**"));

        assertTrue(grant.projectCommands());
        assertEquals(List.of("src/**"), grant.writeGlobs());
    }

    @Test
    void summaryDistinguishesWholeProjectFromScopedGrant() {
        assertTrue(TaskGrant.WORKSPACE_WRITES.summary().contains("写入项目内文件"));
        assertFalse(TaskGrant.WORKSPACE_WRITES.summary().contains(WriteGlobSet.WHOLE_PROJECT));
        assertTrue(new TaskGrant(List.of("src/**"), false).summary().contains("指定路径"));
    }

    @Test
    void noneGrantIsEmptyAndDescribesNoAuthorization() {
        assertTrue(TaskGrant.NONE.isEmpty());
        assertFalse(TaskGrant.NONE.workspaceWrites());
        assertTrue(TaskGrant.NONE.summary().contains("未授权"));
    }

    @Test
    void networkGrantAuthorizesDomainAndItsSubdomainsOnly() {
        TaskGrant grant = new TaskGrant(List.of(), List.of("github.com", "docs.oracle.com"), false);

        assertTrue(grant.networkAllowed());
        assertTrue(grant.authorizesHost("github.com"));
        assertTrue(grant.authorizesHost("api.github.com"));
        assertTrue(grant.authorizesHost("API.GitHub.COM"), "域名匹配应忽略大小写");
        assertTrue(grant.authorizesHost("docs.oracle.com"));
        assertFalse(grant.authorizesHost("notgithub.com"), "后缀匹配必须按域名边界");
        assertFalse(grant.authorizesHost("github.com.evil.test"));
        assertFalse(grant.authorizesHost("example.com"));
        assertFalse(TaskGrant.NONE.authorizesHost("github.com"), "未授权时不得放行任何域名");
    }

    @Test
    void networkGrantRejectsNonHostInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of(), List.of("https://github.com"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of(), List.of("github.com/path"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of(), List.of("github.com:443"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskGrant(List.of(), List.of("*.github.com"), false),
                "不支持通配符，避免授权语义含糊");
    }

    @Test
    void networkGrantPreservesOtherDimensions() {
        TaskGrant grant = TaskGrant.WORKSPACE_WRITES.withNetworkHosts(List.of("github.com"));

        assertTrue(grant.workspaceWrites());
        assertTrue(grant.networkAllowed());
        assertTrue(grant.summary().contains("github.com"));
        assertTrue(grant.summary().contains("写入项目内文件"));
    }

    @Test
    void twoArgConstructorKeepsNetworkUnauthorized() {
        TaskGrant grant = new TaskGrant(List.of("src/**"), true);

        assertFalse(grant.networkAllowed());
        assertFalse(grant.authorizesHost("github.com"));
    }

    @Test
    void globMatchingUsesProjectRelativeSemantics() {
        assertTrue(WriteGlobSet.matches(List.of("src/**"), "src/main/A.java"));
        assertFalse(WriteGlobSet.matches(List.of("src/**"), "test/A.java"));
        assertTrue(WriteGlobSet.matches(List.of(WriteGlobSet.WHOLE_PROJECT), "any/deep/path.txt"));
        assertFalse(WriteGlobSet.matches(List.of(), "src/A.java"));
    }

    @Test
    void compactSummaryIsEmptyWithoutGrantSoStatusBarKeepsNoPlaceholder() {
        assertEquals("", TaskGrant.NONE.compactSummary());
        assertEquals("write ** · commands", TaskGrant.WORKSPACE_WRITES_AND_COMMANDS.compactSummary());
        assertEquals("write src/** · net github.com",
                new TaskGrant(List.of("src/**"), List.of("github.com"), false).compactSummary());
    }
}
