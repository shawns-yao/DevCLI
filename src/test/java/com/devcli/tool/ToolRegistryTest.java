package com.devcli.tool;

import com.devcli.browser.BrowserConnector;
import com.devcli.mcp.protocol.McpToolDescriptor;
import com.devcli.rag.CodeChunk;
import com.devcli.rag.VectorStore;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void searchToolsFindsBuiltinAndMcpTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_issues",
                McpToolDescriptor.namespaced("github", "list_issues"),
                "List GitHub issues",
                JsonNodeFactory.instance.objectNode()
        ), args -> "ok");

        String builtin = registry.executeTool("search_tools", "{\"query\":\"shell command\"}");
        String mcp = registry.executeTool("search_tools", "{\"query\":\"github issues\"}");

        assertTrue(builtin.contains("execute_command"), builtin);
        assertTrue(mcp.contains("mcp__github__list_issues"), mcp);
        assertTrue(mcp.contains("List GitHub issues"), mcp);
    }

    @Test
    void searchToolsMatchesParameterSchemaText() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("search_tools", "{\"query\":\"python\"}");

        assertTrue(result.contains("create_project"), result);
    }

    @Test
    void searchToolsReusesCachedIndexUntilToolCatalogChanges() {
        ToolRegistry registry = new ToolRegistry();

        registry.executeTool("search_tools", "{\"query\":\"python\"}");
        registry.executeTool("search_tools", "{\"query\":\"shell command\"}");
        assertEquals(1, registry.toolSearchIndexBuildCount());

        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_issues",
                McpToolDescriptor.namespaced("github", "list_issues"),
                "List GitHub issues",
                JsonNodeFactory.instance.objectNode()
        ), args -> "ok");

        String mcp = registry.executeTool("search_tools", "{\"query\":\"github issues\"}");

        assertTrue(mcp.contains("mcp__github__list_issues"), mcp);
        assertEquals(2, registry.toolSearchIndexBuildCount());
    }

    @Test
    void mcpToolsAreDeferredFromDefinitionsUntilSearchActivatesThem() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_issues",
                McpToolDescriptor.namespaced("github", "list_issues"),
                "List GitHub issues",
                JsonNodeFactory.instance.objectNode()
        ), args -> "ok");

        assertTrue(registry.getToolDefinitions().stream()
                .anyMatch(tool -> "search_tools".equals(tool.name())));
        assertFalse(registry.getToolDefinitions().stream()
                .anyMatch(tool -> "mcp__github__list_issues".equals(tool.name())));

        registry.executeTool("search_tools", "{\"query\":\"github issues\"}");

        assertTrue(registry.getToolDefinitions().stream()
                .anyMatch(tool -> "mcp__github__list_issues".equals(tool.name())));
    }

    @Test
    void prefetchToolDefinitionsForInputActivatesMatchingMcpTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_issues",
                McpToolDescriptor.namespaced("github", "list_issues"),
                "List GitHub issues",
                JsonNodeFactory.instance.objectNode()
        ), args -> "ok");

        registry.prefetchToolDefinitionsForInput("帮我查看 github issues");

        assertTrue(registry.getToolDefinitions().stream()
                .anyMatch(tool -> "mcp__github__list_issues".equals(tool.name())));
    }

    @Test
    void unknownToolGuidesModelToSearchTools() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("mcp__github__create_issue", "{}");

        assertTrue(result.contains("未知工具: mcp__github__create_issue"), result);
        assertTrue(result.contains("search_tools"), result);
        assertTrue(result.contains("query"), result);
    }

    @Test
    void mcpToolSnapshotIncludesSchemaFingerprint() {
        ToolRegistry registry = new ToolRegistry();
        var issueSchema = JsonNodeFactory.instance.objectNode();
        issueSchema.put("type", "object");
        issueSchema.putObject("properties").putObject("repo").put("type", "string");
        var pullSchema = JsonNodeFactory.instance.objectNode();
        pullSchema.put("type", "object");
        pullSchema.putObject("properties").putObject("state").put("type", "string");
        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_issues",
                McpToolDescriptor.namespaced("github", "list_issues"),
                "List GitHub issues",
                issueSchema
        ), args -> "ok");
        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_pulls",
                McpToolDescriptor.namespaced("github", "list_pulls"),
                "List GitHub pull requests",
                pullSchema
        ), args -> "ok");

        String snapshot = registry.mcpToolSnapshot();

        assertTrue(snapshot.matches("github:2@[0-9a-f]{12}#v0"), snapshot);
    }

    @Test
    void mcpToolSnapshotIncludesServerLifecycleVersion() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerMcpTool(new McpToolDescriptor(
                "github",
                "list_issues",
                McpToolDescriptor.namespaced("github", "list_issues"),
                "List GitHub issues",
                JsonNodeFactory.instance.objectNode()
        ), args -> "ok");

        registry.setMcpServerLifecycleVersion("github", 7);

        String snapshot = registry.mcpToolSnapshot();

        assertTrue(snapshot.matches("github:1@[0-9a-f]{12}#v7"), snapshot);
    }

    @Test
    void currentRagIndexEpochSnapshotReadsProjectVectorStore(@TempDir Path tempDir) throws Exception {
        String oldRagDir = System.getProperty("devcli.rag.dir");
        System.setProperty("devcli.rag.dir", tempDir.resolve("rag").toString());
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            try (VectorStore store = new VectorStore(tempDir.toString())) {
                store.clearProject();
                store.replaceProjectIndex(
                        List.of(new VectorStore.CodeChunkEntry(
                                CodeChunk.fileChunk("README.md", "indexed content"),
                                new float[]{1.0f})),
                        List.of(),
                        "idx-global");
            }

            assertEquals("idx-global", registry.currentRagIndexEpochSnapshot());
        } finally {
            if (oldRagDir == null) {
                System.clearProperty("devcli.rag.dir");
            } else {
                System.setProperty("devcli.rag.dir", oldRagDir);
            }
        }
    }

    @Test
    void searchCodeIncludesInvalidationForDeletedSymbol(@TempDir Path tempDir) throws Exception {
        String oldRagDir = System.getProperty("devcli.rag.dir");
        System.setProperty("devcli.rag.dir", tempDir.resolve("rag").toString());
        try {
            try (ToolRegistry registry = new ToolRegistry()) {
                registry.setProjectPath(tempDir.toString());
            CodeChunk deleted = CodeChunk.methodChunk("UserService.java", "UserService.findUser",
                    "public User findUser(Long id) { return null; }", 1, 3);
            CodeChunk retained = CodeChunk.methodChunk("OrderService.java", "OrderService.list",
                    "public List<Order> list() { return List.of(); }", 1, 3);
            try (VectorStore store = new VectorStore(tempDir.toString())) {
                store.clearProject();
                store.replaceProjectIndex(
                        List.of(
                                new VectorStore.CodeChunkEntry(deleted, new float[]{1.0f, 0.0f}),
                                new VectorStore.CodeChunkEntry(retained, new float[]{0.0f, 1.0f})),
                        List.of(),
                        "idx-old");
                store.replaceProjectIndex(
                        List.of(new VectorStore.CodeChunkEntry(retained, new float[]{0.0f, 1.0f})),
                        List.of(),
                        "idx-new");
            }

            String result = registry.executeTool("search_code",
                    "{\"query\":\"UserService.findUser\",\"top_k\":5,\"mode\":\"definition\"}");

            assertTrue(result.contains("negativeFact: Do not rely on UserService.findUser"), result);
            assertTrue(result.contains("oldSymbolVersion="), result);
            assertTrue(result.contains("newSymbolVersion=deleted"), result);
            }
        } finally {
            if (oldRagDir == null) {
                System.clearProperty("devcli.rag.dir");
            } else {
                System.setProperty("devcli.rag.dir", oldRagDir);
            }
        }
    }

    @Test
    void shouldRunCommandInProjectDirectory(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"pwd\"}");

        String normalizedResult = result.replace('/', '\\').toLowerCase(Locale.ROOT);
        String normalizedPath = tempDir.getFileName().toString().toLowerCase(Locale.ROOT);
        assertTrue(normalizedResult.contains(normalizedPath), result);
    }

    @Test
    void shouldRejectBroadFilesystemScan() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("execute_command", "{\"command\":\"find / -name \\\"pom.xml\\\" -type f | head -20\"}");

        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void shouldTimeoutLongRunningCommandWithoutHanging(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry(1);
        registry.setProjectPath(tempDir.toString());

        String result = registry.executeTool("execute_command", "{\"command\":\"sleep 2\"}");

        assertTrue(result.contains("命令执行超时"));
    }

    @Test
    void shouldExecuteMultipleToolInvocationsInParallelAndKeepResultOrder() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                int now = current.incrementAndGet();
                peak.updateAndGet(prev -> Math.max(prev, now));
                bothStarted.countDown();
                try {
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "两个工具调用应同时进入执行区");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "first", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "second", "{}")
        ));

        assertEquals(2, peak.get(), "两个工具调用应并行执行");
        assertEquals("call_1", results.get(0).id());
        assertEquals("result-first", results.get(0).result());
        assertEquals("call_2", results.get(1).id());
        assertEquals("result-second", results.get(1).result());
    }

    @Test
    void shouldCancelToolInvocationWhenBatchTimeoutIsReached() {
        ToolRegistry registry = new ToolRegistry(1, 1) {
            @Override
            public String executeTool(String name, String argumentsJson) {
                if ("slow".equals(name)) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "result-" + name;
            }
        };

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "slow", "{}"),
                new ToolRegistry.ToolInvocation("call_2", "fast", "{}")
        ));

        assertTrue(results.get(0).timedOut());
        assertTrue(results.get(0).result().contains("工具执行超时"));
        assertEquals("result-fast", results.get(1).result());
    }

    @Test
    void shouldRejectRuntimeWriteConflictBetweenParallelSteps(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String first = registry.runWithResourceLease("step_a", () ->
                registry.executeTool("write_file", "{\"path\":\"src/main/User.java\",\"content\":\"class A {}\"}"));
        String second = registry.runWithResourceLease("step_b", () ->
                registry.executeTool("write_file", "{\"path\":\"src/main/User.java\",\"content\":\"class B {}\"}"));

        assertTrue(first.contains("文件已写入"), first);
        assertTrue(second.contains("策略拒绝"), second);
        assertTrue(second.contains("资源写入冲突"), second);
    }

    @Test
    void shouldAllowSameStepToWriteSameFileAgain(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());

        String result = registry.runWithResourceLease("step_a", () -> {
            registry.executeTool("write_file", "{\"path\":\"src/main/User.java\",\"content\":\"class A {}\"}");
            return registry.executeTool("write_file", "{\"path\":\"src/main/User.java\",\"content\":\"class A2 {}\"}");
        });

        assertTrue(result.contains("文件已写入"), result);
    }

    @Test
    void browserConnectToolUsesInjectedConnector() {
        ToolRegistry registry = new ToolRegistry();
        registry.setBrowserConnector(new BrowserConnector() {
            @Override
            public String status() {
                return "status-ok";
            }

            @Override
            public String connectDefault() {
                return "connected";
            }

            @Override
            public String disconnect() {
                return "disconnected";
            }
        });

        assertEquals("connected", registry.executeTool("browser_connect", "{}"));
        assertEquals("status-ok", registry.executeTool("browser_status", "{}"));
        assertEquals("disconnected", registry.executeTool("browser_disconnect", "{}"));
    }

    @Test
    void saveMemoryToolUsesInjectedMemorySaver() {
        ToolRegistry registry = new ToolRegistry();
        List<String> saved = new ArrayList<>();
        registry.setMemorySaver(saved::add);

        String result = registry.executeTool("save_memory", "{\"fact\":\"访问 yuque.com 时复用登录态\"}");

        assertEquals(List.of("访问 yuque.com 时复用登录态"), saved);
        assertTrue(result.contains("已保存到长期记忆"));
    }

    @Test
    void saveMemoryToolReportsPolicyRejection() {
        ToolRegistry registry = new ToolRegistry();
        registry.setMemorySaveHandler(fact -> new ToolRegistry.MemorySaveResult(false,
                "长期记忆策略跳过: 一次性临时信息"));

        String result = registry.executeTool("save_memory", "{\"fact\":\"今天地铁好挤\"}");

        assertTrue(result.contains("长期记忆策略跳过"), result);
    }

    @Test
    void listMemoryToolUsesInjectedMemoryLister() {
        ToolRegistry registry = new ToolRegistry();
        registry.setMemoryListHandler(limit -> "memories limit=" + limit);

        String result = registry.executeTool("list_memory", "{\"limit\":2}");

        assertEquals("memories limit=2", result);
    }

    @Test
    void listMemoryToolReportsMissingLister() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("list_memory", "{}");

        assertTrue(result.contains("记忆查询器未初始化"), result);
    }

    @Test
    void listMemoryToolDefinitionGuidesMemoryQueriesWithoutKeywordRouting() {
        ToolRegistry registry = new ToolRegistry();

        var tool = registry.getToolDefinitions().stream()
                .filter(definition -> "list_memory".equals(definition.name()))
                .findFirst()
                .orElseThrow();

        assertTrue(tool.description().contains("长期记忆"));
        assertTrue(tool.description().contains("只读"));
        assertTrue(tool.description().contains("search_code"));
    }

    @Test
    void shouldRejectMalformedToolArgumentsBeforeExecution() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("read_file", "{not-json");

        assertTrue(result.contains("工具参数校验失败"));
        assertTrue(result.contains("不是合法 JSON"));
    }

    @Test
    void shouldRejectBuiltinToolTypeMismatchBeforeExecution() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("search_code", "{\"query\":\"agent\",\"top_k\":\"5\"}");

        assertTrue(result.contains("工具参数校验失败"));
        assertTrue(result.contains("$.top_k must be integer"));
    }

    @Test
    void shouldRejectBuiltinToolEnumMismatchBeforeExecution() {
        ToolRegistry registry = new ToolRegistry();

        String result = registry.executeTool("create_project", "{\"name\":\"demo\",\"type\":\"go\"}");

        assertTrue(result.contains("工具参数校验失败"));
        assertTrue(result.contains("$.type must be one of [java, python, node]"));
    }
}
