package com.devcli.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodexMcpConfigLoaderTest {
    @TempDir Path directory;

    @Test
    void importsConnectionsWithoutImportingApprovalBypasses() throws Exception {
        Path source = directory.resolve("config.toml");
        Files.writeString(source, """
                [mcp_servers.codegraph]
                command = 'C:\\tools\\codegraph.cmd'
                args = ["mcp", "--project", "${PROJECT_DIR}"]
                default_tools_approval_mode = "never"
                disabled_tools = ["delete_index"]
                [mcp_servers.codegraph.env]
                LANG = "zh_CN.UTF-8"
                [mcp_servers.remote]
                url = "https://example.com/mcp"
                bearer_token_env_var = "EXAMPLE_TOKEN"
                [mcp_servers.remote.env_http_headers]
                "X-Project" = "EXAMPLE_PROJECT"
                [mcp_servers.off]
                command = "unused"
                enabled = false
                """);
        var configs = CodexMcpConfigLoader.load(source);
        var graph = configs.get("codegraph");
        assertEquals("C:\\tools\\codegraph.cmd", graph.getCommand());
        assertEquals(List.of("mcp", "--project", "${PROJECT_DIR}"), graph.getArgs());
        assertEquals("zh_CN.UTF-8", graph.getEnv().get("LANG"));
        assertEquals(List.of("delete_index"), graph.getDeniedTools());
        assertFalse(graph.isTrustReadOnlyAnnotations());
        assertTrue(graph.getReadOnlyTools().isEmpty());
        assertEquals("Bearer ${EXAMPLE_TOKEN}", configs.get("remote").getHeaders().get("Authorization"));
        assertEquals("${EXAMPLE_PROJECT}", configs.get("remote").getHeaders().get("X-Project"));
        assertTrue(configs.get("off").isDisabled());
    }

    @Test
    void devCliUserAndProjectSettingsOverrideSharedServers() throws Exception {
        Path shared = directory.resolve("config.toml");
        Files.writeString(shared, "[mcp_servers.graph]\ncommand = 'shared'\n[mcp_servers.search]\ncommand = 'search'\n");
        Path user = directory.resolve("user.json");
        Files.writeString(user, "{\"mcpServers\":{\"graph\":{\"command\":\"user\"}}}");
        Path project = directory.resolve("project.json");
        Files.writeString(project, "{\"mcpServers\":{\"graph\":{\"command\":\"project\"}}}");
        var configs = new McpConfigLoader(user, project, directory, shared).load();
        assertEquals("project", configs.get("graph").getCommand());
        assertEquals("search", configs.get("search").getCommand());
    }

    @Test
    void malformedTomlDoesNotExposeItsContents() throws Exception {
        Path source = directory.resolve("config.toml");
        Files.writeString(source, "[mcp_servers.secret]\ncommand = \"private-value");
        var error = assertThrows(java.io.IOException.class, () -> CodexMcpConfigLoader.load(source));
        assertFalse(error.getMessage().contains("private-value"));
    }

    @Test
    void localPolicyOnlyEntryKeepsSharedConnectionAndDenials() throws Exception {
        Path shared = directory.resolve("config.toml");
        Files.writeString(shared, """
                [mcp_servers.graph]
                command = 'shared'
                args = ['mcp', '${PROJECT_DIR}']
                disabled_tools = ['delete_index']
                enabled = false
                [mcp_servers.graph.env]
                LOCALE = 'zh_CN'
                """);
        Path user = directory.resolve("user.json");
        Files.writeString(user, """
                {"mcpServers":{"graph":{"readOnlyTools":["search","status"]}}}
                """);
        var loader = new McpConfigLoader(user, directory.resolve("missing.json"), directory, shared);
        var graph = loader.load().get("graph");
        assertEquals("shared", graph.getCommand());
        assertEquals(List.of("mcp", "${PROJECT_DIR}"), graph.getArgs());
        assertEquals("zh_CN", graph.getEnv().get("LOCALE"));
        assertEquals(List.of("search", "status"), graph.getReadOnlyTools());
        assertEquals(List.of("delete_index"), graph.getDeniedTools());
        assertTrue(graph.isDisabled());
        assertFalse(graph.isTrustReadOnlyAnnotations());
        loader.prepare(graph);
        assertEquals(directory.toString(), graph.getArgs().get(1));
    }

    @Test
    void projectPolicyCanExplicitlyClearInheritedReadOnlyTrust() throws Exception {
        Path user = directory.resolve("user.json");
        Files.writeString(user, """
                {"mcpServers":{"graph":{"command":"graph","readOnlyTools":["search"],
                  "trustReadOnlyAnnotations":true,"deniedTools":["delete"]}}}
                """);
        Path project = directory.resolve("project.json");
        Files.writeString(project, """
                {"mcpServers":{"graph":{"readOnlyTools":[],"trustReadOnlyAnnotations":false}}}
                """);
        var graph = new McpConfigLoader(user, project, directory).load().get("graph");
        assertEquals("graph", graph.getCommand());
        assertTrue(graph.getReadOnlyTools().isEmpty());
        assertFalse(graph.isTrustReadOnlyAnnotations());
        assertEquals(List.of("delete"), graph.getDeniedTools());
    }

    @Test
    void connectionReplacementDoesNotInheritOldToolTrust() throws Exception {
        Path user = directory.resolve("user.json");
        Files.writeString(user, """
                {"mcpServers":{"graph":{"command":"old","readOnlyTools":["search"]}}}
                """);
        Path project = directory.resolve("project.json");
        Files.writeString(project, "{\"mcpServers\":{\"graph\":{\"command\":\"new\"}}}");
        var graph = new McpConfigLoader(user, project, directory).load().get("graph");
        assertEquals("new", graph.getCommand());
        assertTrue(graph.getReadOnlyTools().isEmpty());
    }

    @Test
    void policyWithoutKnownConnectionDoesNotInventOne() throws Exception {
        Path user = directory.resolve("user.json");
        Files.writeString(user, "{\"mcpServers\":{\"unknown\":{\"readOnlyTools\":[\"search\"]}}}");
        var loader = new McpConfigLoader(user, directory.resolve("missing.json"), directory);
        assertThrows(IllegalArgumentException.class, () -> loader.prepare(loader.load().get("unknown")));
    }
}
