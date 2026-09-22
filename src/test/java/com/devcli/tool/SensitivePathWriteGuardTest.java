package com.devcli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受保护路径在策略层拦截，与 HITL 开关无关。
 *
 * <p>这里用的是普通 {@link ToolRegistry}（没有任何审批中间件），正是为了证明
 * "关闭人工审批不会让凭据、私钥或 `.git` 变成可写"。</p>
 */
class SensitivePathWriteGuardTest {

    @TempDir
    Path projectRoot;

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectRoot.toString());
        return registry;
    }

    private static void assertDenied(ToolOutput output, String what) {
        assertEquals(ToolStatus.REJECTED, output.status(), what + ": " + output.text());
        assertEquals(ToolErrorCode.POLICY_DENIED, output.errorCode(), what + ": " + output.text());
        assertTrue(output.text().contains("受保护"), output.text());
    }

    @Test
    void writeFileRejectsProtectedPaths() throws Exception {
        try (ToolRegistry registry = registry()) {
            Files.createDirectories(projectRoot.resolve(".git"));

            assertDenied(registry.executeToolOutput("write_file",
                    "{\"path\":\".git/config\",\"content\":\"x\"}"), ".git 目录");
            assertDenied(registry.executeToolOutput("write_file",
                    "{\"path\":\".env\",\"content\":\"x\"}"), ".env");
            assertDenied(registry.executeToolOutput("write_file",
                    "{\"path\":\"certs/server.pem\",\"content\":\"x\"}"), "私钥文件");

            assertFalse(Files.exists(projectRoot.resolve(".git/config")));
            assertFalse(Files.exists(projectRoot.resolve(".env")));
        }
    }

    @Test
    void editFileIsCoveredByTheSameGuard() throws Exception {
        try (ToolRegistry registry = registry()) {
            // edit_file 的语义校验要求目标已存在，先建出来，确保拦截来自写入围栏而不是前置校验
            Files.createDirectories(projectRoot.resolve(".git"));
            Files.writeString(projectRoot.resolve(".git/config"), "[core]\n");

            assertDenied(registry.executeToolOutput("edit_file",
                    "{\"path\":\".git/config\",\"old_string\":\"[core]\",\"new_string\":\"[x]\"}"),
                    "edit_file 同样受保护");
            assertEquals("[core]\n", Files.readString(projectRoot.resolve(".git/config")));
        }
    }

    @Test
    void createProjectCannotTargetProtectedDirectory() {
        try (ToolRegistry registry = registry()) {
            assertDenied(registry.executeToolOutput("create_project",
                    "{\"name\":\".git\",\"type\":\"java\"}"), "create_project 同样受保护");
        }
    }

    @Test
    void envTemplateAndOrdinarySourcesStayWritable() throws Exception {
        try (ToolRegistry registry = registry()) {
            ToolOutput template = registry.executeToolOutput("write_file",
                    "{\"path\":\".env.example\",\"content\":\"KEY=\"}");
            assertTrue(template.isSuccess(), template.text());

            ToolOutput source = registry.executeToolOutput("write_file",
                    "{\"path\":\"src/main/java/A.java\",\"content\":\"class A {}\"}");
            assertTrue(source.isSuccess(), source.text());

            assertTrue(Files.exists(projectRoot.resolve(".env.example")));
            assertTrue(Files.exists(projectRoot.resolve("src/main/java/A.java")));
        }
    }
}
