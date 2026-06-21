package com.devcli.mcp;

import com.devcli.mcp.config.McpConfigLoader;
import com.devcli.mcp.config.McpServerConfig;
import com.devcli.tool.ToolRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过 MockWebServer 模拟 Streamable HTTP MCP server 来端到端验证 McpServerManager 的启停流程。
 * 不测真实 stdio 子进程（已在 StdioTransportTest 单独覆盖）。
 */
class McpServerManagerTest {

    private MockWebServer webServer;
    private ToolRegistry registry;
    private McpServerManager manager;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        webServer = new MockWebServer();
        webServer.start();
        registry = new ToolRegistry();
        // 用空 config loader 占位，单独把 server 直接放进 manager（避开真实文件读取）
        manager = new McpServerManager(registry, tempDir,
                new McpConfigLoader(tempDir.resolve("user.json"), tempDir.resolve("project.json"), tempDir));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (manager != null) manager.close();
        if (webServer != null) webServer.shutdown();
    }

    @Test
    void startAllStartsHttpServerAndRegistersTools() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo back text"));

        loadServersFromMap(Map.of("demo", httpConfig(webServer)));
        manager.startAll();

        McpServer server = manager.servers().iterator().next();
        assertEquals(McpServerStatus.READY, server.status(), "状态应为 READY，错误: " + server.errorMessage());
        assertEquals(1, server.tools().size());
        assertTrue(registry.hasTool("mcp__demo__echo"));

        List<McpConnectionEvent> events = manager.connectionEvents();
        assertTrue(events.stream().anyMatch(event ->
                event.serverName().equals("demo")
                        && event.type() == McpConnectionEvent.Type.STARTING
                        && event.status() == McpServerStatus.STARTING));
        assertTrue(events.stream().anyMatch(event ->
                event.serverName().equals("demo")
                        && event.type() == McpConnectionEvent.Type.READY
                        && event.status() == McpServerStatus.READY
                        && event.toolCount() == 1
                        && event.lifecycleVersion() == server.lifecycleVersion()));
    }

    @Test
    void resourcesCapabilityRegistersVirtualResourceTools() throws Exception {
        enqueueInitialize("{\"resources\":{\"listChanged\":true},\"prompts\":{}}");
        enqueueToolsList(toolJson("echo", "Echo back text"));
        enqueueResourcesList();

        loadServersFromMap(Map.of("demo", httpConfig(webServer)));
        manager.startAll();

        assertTrue(registry.hasTool("mcp__demo__echo"));
        assertTrue(registry.hasTool("mcp__demo__list_resources"));
        assertTrue(registry.hasTool("mcp__demo__read_resource"));
        assertTrue(manager.resourceCandidates().stream().anyMatch(r -> r.uri().equals("file://README.md")));
        assertTrue(manager.resourceIndexForPrompt().contains("@demo:file://README.md"));

        enqueuePromptsList();
        assertTrue(manager.prompts("demo").contains("Review (review)"));
    }

    @Test
    void singleServerFailureDoesNotBlockOthers() throws Exception {
        // 一个 OK 的 server + 一个引用未设置 ${VAR} 的 server
        enqueueInitialize();
        enqueueToolsList(toolJson("ok", "ok tool"));

        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        configs.put("good", httpConfig(webServer));
        McpServerConfig bad = new McpServerConfig();
        bad.setUrl("https://example.com/${UNSET_DEMO_VAR_FOR_TEST}");
        configs.put("bad", bad);
        loadServersFromMap(configs);

        manager.startAll();

        Map<String, McpServer> byName = new HashMap<>();
        manager.servers().forEach(s -> byName.put(s.name(), s));
        assertEquals(McpServerStatus.READY, byName.get("good").status(),
                "good 应正常启动，不被 bad 阻塞");
        assertEquals(McpServerStatus.ERROR, byName.get("bad").status(),
                "bad 应标 ERROR");
        assertNotNull(byName.get("bad").errorMessage());
        assertTrue(registry.hasTool("mcp__good__ok"));
        assertTrue(manager.connectionEvents().stream().anyMatch(event ->
                event.serverName().equals("bad")
                        && event.type() == McpConnectionEvent.Type.ERROR
                        && event.status() == McpServerStatus.ERROR
                        && event.message().contains("UNSET_DEMO_VAR_FOR_TEST")));
    }

    @Test
    void boundedStartupWaitReturnsWhileSlowServerContinuesStarting() throws Exception {
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}")
                .setBodyDelay(3, TimeUnit.SECONDS));

        loadServersFromMap(Map.of("slow", httpConfig(webServer)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long started = System.nanoTime();
        manager.startAll(new PrintStream(out, true, StandardCharsets.UTF_8), Duration.ofMillis(100));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        McpServer server = manager.server("slow");
        assertTrue(elapsedMillis < 1500, "bounded startup should return before initialize timeout");
        assertEquals(McpServerStatus.STARTING, server.status());
        assertFalse(registry.hasTool("mcp__slow__echo"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("后台继续启动"));
    }

    @Test
    void disableRemovesToolsFromRegistry() throws Exception {
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));

        loadServersFromMap(Map.of("demo", httpConfig(webServer)));
        manager.startAll();
        assertTrue(registry.hasTool("mcp__demo__echo"));
        McpToolDiscoveryEntry discovered = manager.toolDiscoveryCache().stream()
                .filter(entry -> entry.serverName().equals("demo"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("echo"), discovered.toolNames());
        assertEquals(1, discovered.toolCount());
        assertFalse(discovered.schemaFingerprint().isBlank());
        assertEquals(manager.server("demo").lifecycleVersion(), discovered.lifecycleVersion());

        String result = manager.disable("demo");
        assertTrue(result.contains("已禁用"));
        assertFalse(registry.hasTool("mcp__demo__echo"),
                "disable 后 ToolRegistry 应不再持有该工具");
        McpServer server = manager.servers().iterator().next();
        assertEquals(McpServerStatus.DISABLED, server.status());
        assertTrue(manager.toolDiscoveryCache().stream().anyMatch(entry ->
                entry.serverName().equals("demo")
                        && entry.toolNames().equals(List.of("echo"))
                        && entry.lifecycleVersion() == discovered.lifecycleVersion()));
    }

    @Test
    void restartReregistersToolsAfterFailure() throws Exception {
        // 第一次启动：失败（401）
        webServer.enqueue(new MockResponse().setResponseCode(401));

        loadServersFromMap(Map.of("demo", httpConfig(webServer)));
        manager.startAll();

        McpServer server = manager.servers().iterator().next();
        assertEquals(McpServerStatus.ERROR, server.status());
        assertFalse(registry.hasTool("mcp__demo__echo"));

        // 第二次启动：成功
        enqueueInitialize();
        enqueueToolsList(toolJson("echo", "Echo"));

        String result = manager.restart("demo");
        assertEquals(McpServerStatus.READY, server.status(), "重启后应 READY: " + result);
        assertTrue(registry.hasTool("mcp__demo__echo"));
    }

    @Test
    void failedServerAutomaticallyReconnectsAndRegistersTools() throws Exception {
        String previousMaxAttempts = System.getProperty("devcli.mcp.reconnect.maxAttempts");
        String previousInitialDelay = System.getProperty("devcli.mcp.reconnect.initialDelayMillis");
        System.setProperty("devcli.mcp.reconnect.maxAttempts", "1");
        System.setProperty("devcli.mcp.reconnect.initialDelayMillis", "10");
        try {
            webServer.enqueue(new MockResponse().setResponseCode(401));

            loadServersFromMap(Map.of("demo", httpConfig(webServer)));
            manager.startAll();

            McpServer server = manager.server("demo");
            assertEquals(McpServerStatus.ERROR, server.status());
            assertFalse(registry.hasTool("mcp__demo__echo"));

            enqueueInitialize();
            enqueueToolsList(toolJson("echo", "Echo"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline
                    && (server.status() != McpServerStatus.READY
                    || !registry.hasTool("mcp__demo__echo"))) {
                TimeUnit.MILLISECONDS.sleep(20);
            }

            assertEquals(McpServerStatus.READY, server.status(), "自动重连后应 READY: " + server.errorMessage());
            assertTrue(registry.hasTool("mcp__demo__echo"));
            assertTrue(manager.connectionEvents().stream().anyMatch(event ->
                    event.serverName().equals("demo")
                            && event.type() == McpConnectionEvent.Type.RECONNECTING));
        } finally {
            restoreProperty("devcli.mcp.reconnect.maxAttempts", previousMaxAttempts);
            restoreProperty("devcli.mcp.reconnect.initialDelayMillis", previousInitialDelay);
        }
    }

    @Test
    void restartWithArgsUpdatesServerConfig() {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("definitely-missing-devcli-test-command");
        config.setArgs(List.of("old"));
        loadServersFromMap(Map.of("demo", config));

        String result = manager.restartWithArgs("demo", List.of("new", "args"));

        McpServer server = manager.server("demo");
        assertEquals(List.of("new", "args"), server.config().getArgs());
        assertTrue(result.contains("重启失败"));
    }

    @Test
    void unknownServerOperationsReturnFriendlyError() {
        loadServersFromMap(Map.of());
        assertTrue(manager.disable("missing").contains("未找到"));
        assertTrue(manager.enable("missing").contains("未找到"));
        assertTrue(manager.restart("missing").contains("未找到"));
        assertTrue(manager.logs("missing").contains("未找到"));
    }

    // ---- helpers ----

    private void enqueueInitialize() {
        enqueueInitialize(null);
    }

    private void enqueueInitialize(String capabilitiesJson) {
        String capabilities = capabilitiesJson == null ? "" : ",\"capabilities\":" + capabilitiesJson;
        // initialize 请求响应
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "session-test")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"" + capabilities + "}}"));
        // initialized 通知（无 id），server 仍要返回 200，body 任意
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(""));
    }

    private void enqueueToolsList(String toolJson) {
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" + toolJson + "]}}"));
    }

    private void enqueueResourcesList() {
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"jsonrpc":"2.0","id":3,"result":{"resources":[
                          {"uri":"file://README.md","name":"README.md","description":"docs","mimeType":"text/markdown"}
                        ]}}
                        """));
    }

    private void enqueuePromptsList() {
        webServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"jsonrpc":"2.0","id":4,"result":{"prompts":[
                          {"name":"review","title":"Review","description":"Review code"}
                        ]}}
                        """));
    }

    private static String toolJson(String name, String description) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}";
    }

    private static McpServerConfig httpConfig(MockWebServer webServer) {
        McpServerConfig config = new McpServerConfig();
        config.setUrl(webServer.url("/mcp").toString());
        return config;
    }

    private void loadServersFromMap(Map<String, McpServerConfig> configs) {
        // 用反射不优雅；这里用 manager 暴露的"已加载"语义模拟：直接通过创建 McpServer 对象塞进去
        // 由于 McpServerManager.servers 是 ConcurrentHashMap 且 loadConfiguredServers 也是把 config 翻译成
        // McpServer 实例，这里复用 loadConfiguredServers 的内部行为：把 configs 写入临时文件后 load。
        // 简化为：直接通过 reflection 写入 servers map。
        try {
            java.lang.reflect.Field f = McpServerManager.class.getDeclaredField("servers");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, McpServer> map = (Map<String, McpServer>) f.get(manager);
            map.clear();
            configs.forEach((name, cfg) -> map.put(name, new McpServer(name, cfg)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
