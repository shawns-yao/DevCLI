package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitivePathPolicyTest {

    private static String deny(String relative) {
        return SensitivePathPolicy.denyReason(Path.of(relative));
    }

    @Test
    void protectsVcsAndSshDirectories() {
        assertNotNull(deny(".git/config"));
        assertNotNull(deny("sub/.git/HEAD"));
        assertNotNull(deny(".ssh/id_rsa"));

        assertNull(deny(".gitignore"), ".gitignore 是普通文件，不是受保护目录");
        assertNull(deny("src/git/Config.java"), "路径中出现 git 字样不等于版本库目录");
    }

    @Test
    void protectsCredentialsAndPrivateKeys() {
        assertNotNull(deny(".env"));
        assertNotNull(deny(".env.local"));
        assertNotNull(deny("config/credentials.json"));
        assertNotNull(deny("deploy/service-account.json"));
        assertNotNull(deny("certs/server.pem"));
        assertNotNull(deny("certs/server.key"));
        assertNotNull(deny("deploy/app.jks"));
        assertNotNull(deny("keys/id_ed25519"));

        assertNull(deny("src/main/java/A.java"));
        assertNull(deny("README.md"));
        assertNull(deny("docs/credential-policy.md"), "不按文件名里的 credential 字样误伤");
    }

    @Test
    void allowsEnvTemplatesForWritingButNotForWorkspaceMaterialization() {
        assertNull(deny(".env.example"));
        assertNull(deny(".env.sample"));
        assertNull(deny(".env.template"));

        // 工作区物化仍按既有行为过滤模板，避免改变隔离工作区内容
        assertTrue(SensitivePathPolicy.isSensitiveFile(Path.of(".env.example"), false));
        assertFalse(SensitivePathPolicy.isSensitiveFile(Path.of(".env.example"), true));
        assertTrue(SensitivePathPolicy.isSensitiveFile(Path.of(".env"), false));
    }

    @Test
    void doesNotProtectBuildOrInternalDirectories() {
        assertNull(deny("target/classes/A.class"));
        assertNull(deny("node_modules/pkg/index.js"));
        assertNull(deny(".devcli/notes.txt"));
    }

    @Test
    void protectsAgentConfigurationNamesWithoutBlockingOrdinaryConfiguration() {
        assertNotNull(deny(".devcli/hooks.json"));
        assertNotNull(deny(".devcli/mcp.json"));
        assertNotNull(deny(".devcli/config.json"));
        assertNotNull(deny("nested/.DEVCLI/MCP.JSON"));
        assertNotNull(deny(".devcli"));
        assertNotNull(deny(".devcli/hooks.json/child"));
        assertNull(deny("config/hooks.json"));
        assertNull(deny(".devcli/hooks.json.example"));
    }
}
