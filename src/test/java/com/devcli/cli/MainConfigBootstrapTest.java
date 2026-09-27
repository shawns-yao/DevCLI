package com.devcli.cli;

import com.devcli.config.DevCliConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainConfigBootstrapTest {

    @Test
    void createsDefaultChromeDevtoolsMcpConfigWhenMissing(@TempDir Path tempHome) throws Exception {
        Main.McpConfigBootstrapResult result = Main.ensureDefaultMcpConfig(tempHome);

        Path config = tempHome.resolve(".devcli").resolve("mcp.json");
        assertTrue(result.created());
        assertTrue(Files.exists(config));
        String content = Files.readString(config);
        assertTrue(content.contains("\"chrome-devtools\""));
        assertTrue(content.contains("chrome-devtools-mcp@latest"));
        assertTrue(content.contains("--isolated=true"));
    }

    @Test
    void doesNotOverwriteExistingUserConfig(@TempDir Path tempHome) throws Exception {
        Path config = tempHome.resolve(".devcli").resolve("mcp.json");
        Files.createDirectories(config.getParent());
        String original = """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem"]
                    }
                  }
                }
                """;
        Files.writeString(config, original);

        Main.McpConfigBootstrapResult result = Main.ensureDefaultMcpConfig(tempHome);

        assertFalse(result.created());
        assertEquals(original, Files.readString(config));
        assertTrue(result.message().contains("未配置 chrome-devtools"));
    }

    @Test
    void loadsPermissionRulesFromConfig() {
        DevCliConfig config = new DevCliConfig();
        config.getPermissions().setHardDeny(List.of("execute_command(git push:*)"));
        config.getPermissions().setSoftDeny(List.of("write_file(.github/**)"));
        config.getPermissions().setAllow(List.of("Edit(src/**)"));
        config.getPermissions().setEnvironment(List.of("可信域名：github.com"));

        com.devcli.policy.PermissionRuleSet rules = Main.loadPermissionRules(config);

        assertEquals(1, rules.hardDeny().size());
        assertEquals(1, rules.softDeny().size());
        assertEquals(1, rules.allow().size());
        assertEquals(1, rules.environment().size(), "环境事实是自由文本，必须原样保留");
        assertEquals("edit_file", rules.allow().get(0).tool(), "别名必须归一到 DevCLI 原生工具名");
        assertEquals("src/**", rules.allow().get(0).specifier());
    }

    @Test
    void emptyPermissionRulesYieldNoRules() {
        com.devcli.policy.PermissionRuleSet rules = Main.loadPermissionRules(new DevCliConfig());

        assertTrue(rules.isEmpty(), "未配置规则时必须等价于无规则，由模式基线策略接管");
    }

    @Test
    void illegalPermissionRuleIsRejectedInsteadOfPartiallyApplied() {
        DevCliConfig config = new DevCliConfig();
        config.getPermissions().setHardDeny(List.of("execute_command(git push:*)"));
        // MCP 工具没有声明参数级资源槽，带括号的规则在解析时就必须被拒绝
        config.getPermissions().setAllow(List.of("mcp__puppeteer__navigate(page)"));

        com.devcli.policy.PermissionRuleSet rules = Main.loadPermissionRules(config);

        assertTrue(rules.isEmpty(), "任一条规则非法时必须整体降级，不能让合法规则单独生效");
    }

    @Test
    void startupPermissionModeReadsConfiguredValue() {
        DevCliConfig config = new DevCliConfig();
        config.getPermissions().setDefaultMode("bypassPermissions");

        assertEquals(com.devcli.policy.PermissionMode.BYPASS_PERMISSIONS,
                Main.startupPermissionMode(config));
    }

    @Test
    void startupPermissionModeDefaultsToAsking() {
        assertEquals(com.devcli.policy.PermissionMode.DEFAULT,
                Main.startupPermissionMode(new DevCliConfig()),
                "未配置时必须逐个询问：一个默认不生效的 deny 规则比没有更糟");
        assertEquals(com.devcli.policy.PermissionMode.DEFAULT,
                Main.startupPermissionMode(null), "配置整体缺失时不能抛异常");
    }

    @Test
    void illegalStartupPermissionModeFallsBackToDefault() {
        DevCliConfig config = new DevCliConfig();
        config.getPermissions().setDefaultMode("maybe");

        // 回落而不是启动失败：配置写错不该让人打不开工具
        assertEquals(com.devcli.policy.PermissionMode.DEFAULT,
                Main.startupPermissionMode(config));
    }

    @Test
    void neverPromptsOnlyForModesWithNoApprovalOutcome() {
        // 活动轮次能否并发读终端取决于这个判断。判宽了会让审批读取器和队列读取器同时抢 stdin；
        // 判窄了只是少一个便利功能，所以这里按「宁可判窄」取值。
        var noRules = com.devcli.policy.PermissionRuleSet.EMPTY;
        var softDenyRule = com.devcli.policy.PermissionRuleSet.parse(
                List.of(), List.of("write_file(*)"), List.of(), List.of());

        // dontAsk 把未决动作收口为拒绝，连显式 soft_deny 规则也被拒绝，不会弹审批
        assertTrue(Main.neverPrompts(com.devcli.policy.PermissionMode.DONT_ASK, noRules));
        assertTrue(Main.neverPrompts(com.devcli.policy.PermissionMode.DONT_ASK, softDenyRule));

        // bypassPermissions 放行未决动作，但显式 soft_deny 规则仍然会询问
        assertTrue(Main.neverPrompts(com.devcli.policy.PermissionMode.BYPASS_PERMISSIONS, noRules));
        assertFalse(Main.neverPrompts(com.devcli.policy.PermissionMode.BYPASS_PERMISSIONS, softDenyRule));

        // 其余模式都可能弹审批——auto 的分类器失败也会退回询问
        for (var mode : List.of(com.devcli.policy.PermissionMode.DEFAULT,
                com.devcli.policy.PermissionMode.PLAN,
                com.devcli.policy.PermissionMode.ACCEPT_EDITS,
                com.devcli.policy.PermissionMode.AUTO)) {
            assertFalse(Main.neverPrompts(mode, noRules), mode.id());
        }
    }
}
