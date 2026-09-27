package com.devcli.mcp;

import com.devcli.mcp.config.McpConfigLoader;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 定向测试：真实 MCP 管理入口、stdio 协议及 Windows 只读隔离。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxMcpTest {
    @Test
    void initializesAndCallsReadOnlyServerInNativeSandbox(@TempDir Path workspace) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("devcli.windows.sandbox.launcher") != null);
        Files.writeString(workspace.resolve("seed.txt"), "seed");
        String script = """
                [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
                while ($null -ne ($line=[Console]::ReadLine())) {
                  $r=$line | ConvertFrom-Json
                  if ($null -eq $r.id) { continue }
                  $result=@{}
                  if ($r.method -eq 'initialize') {
                    $result=@{protocolVersion='2024-11-05';capabilities=@{};serverInfo=@{name='probe';version='1'}}
                  } elseif ($r.method -eq 'tools/list') {
                    $result=@{tools=@(@{name='probe';description='read-only probe';inputSchema=@{type='object';properties=@{}}})}
                  } elseif ($r.method -eq 'tools/call') {
                    $seed=[IO.File]::ReadAllText('seed.txt')
                    $denied=$false
                    try { [IO.File]::WriteAllText('forbidden.txt','bad') } catch { $denied=$true }
                    $result=@{content=@(@{type='text';text=($seed+':writeDenied='+$denied)})}
                  }
                  @{jsonrpc='2.0';id=$r.id;result=$result} | ConvertTo-Json -Depth 10 -Compress | ForEach-Object { [Console]::WriteLine($_) }
                }
                """;
        String shell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        Path config = workspace.resolve("mcp-test.json");
        new ObjectMapper().writeValue(config.toFile(), Map.of("mcpServers", Map.of("probe", Map.of(
                "command", shell, "sandbox", "WINDOWS_NATIVE", "args", List.of("-NoProfile", "-NonInteractive",
                        "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))))));
        long brokerPid;
        try (ToolRegistry registry = new ToolRegistry();
             McpServerManager manager = new McpServerManager(registry, workspace,
                     new McpConfigLoader(config, workspace.resolve("absent.json"), workspace))) {
            manager.loadConfiguredServers();
            manager.startAll();
            var server = manager.server("probe");
            assertEquals(McpServerStatus.READY, server.status(), server.errorMessage());
            assertTrue(registry.hasTool("mcp__probe__probe"));
            assertTrue(server.client().callTool("probe", "{}").contains("seed:writeDenied=True"));
            assertFalse(Files.exists(workspace.resolve("forbidden.txt")));
            brokerPid = server.client().processId();
        }
        assertFalse(ProcessHandle.of(brokerPid).map(ProcessHandle::isAlive).orElse(false));
    }
}
