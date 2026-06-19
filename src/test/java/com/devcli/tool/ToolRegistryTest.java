package com.devcli.tool;

import com.devcli.browser.BrowserConnector;
import com.devcli.mcp.protocol.McpToolDescriptor;
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
