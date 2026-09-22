package com.devcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResultCacheTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 结果缓存按任务隔离：执行管线只在 {@link ToolRegistry#runWithToolTask} 建立的
     * 任务身份内命中缓存，生产路径由 AgentExecutionEngine 按 turn 提供身份。
     * 因此这里也必须建立身份，否则测的是「绕过缓存」而不是缓存语义。
     */
    private static final String TASK = "test-task";

    @Test
    void semanticallyEquivalentReadOnlyCallsShareCachedResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "value-" + executions.incrementAndGet(),
                    ToolRegistry.ToolEffect.READ_ONLY));

            ToolRegistry.runWithToolTask(TASK, () -> {
                ToolOutput first = registry.executeToolOutput("cached_lookup",
                        "{\"query\":\"  User   Service \",\"limit\":5}");
                ToolOutput second = registry.executeToolOutput("cached_lookup",
                        "{\"limit\":5,\"query\":\"user service\"}");
                assertEquals("value-1", first.text());
                assertEquals("value-1", second.text());
                return null;
            });
            assertEquals(1, executions.get());
        }
    }

    @Test
    void toolCatalogChangeInvalidatesCachedResult() throws Exception {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "v1", ToolRegistry.ToolEffect.READ_ONLY));
            ToolRegistry.Tool replacement = new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "v2", ToolRegistry.ToolEffect.READ_ONLY);

            ToolRegistry.runWithToolTask(TASK, () -> {
                assertEquals("v1", registry.executeToolOutput("cached_lookup", "{}").text());

                registry.registerTool(replacement);

                assertEquals("v2", registry.executeToolOutput("cached_lookup", "{}").text());
                return null;
            });
        }
    }

    @Test
    void unicodeEquivalentQuerySharesCachedResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "value-" + executions.incrementAndGet(),
                    ToolRegistry.ToolEffect.READ_ONLY));

            ToolRegistry.runWithToolTask(TASK, () -> {
                registry.executeToolOutput("cached_lookup", "{\"query\":\"ＡＰＩ Service\"}");
                ToolOutput second = registry.executeToolOutput("cached_lookup", "{\"query\":\"api service\"}");
                assertEquals("value-1", second.text());
                return null;
            });
            assertEquals(1, executions.get());
        }
    }

    @Test
    void caseSensitivePatternDoesNotShareCachedResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "value-" + executions.incrementAndGet(),
                    ToolRegistry.ToolEffect.READ_ONLY));

            ToolRegistry.runWithToolTask(TASK, () -> {
                registry.executeToolOutput("cached_lookup", "{\"pattern\":\"UserService\"}");
                ToolOutput second = registry.executeToolOutput("cached_lookup", "{\"pattern\":\"userservice\"}");
                assertEquals("value-2", second.text());
                return null;
            });
            assertEquals(2, executions.get());
        }
    }

    @Test
    void mutationInvalidatesReadOnlyCache() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "value-" + executions.incrementAndGet(),
                    ToolRegistry.ToolEffect.READ_ONLY));
            registry.registerTool(new ToolRegistry.Tool(
                    "mutate", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "changed",
                    ToolRegistry.ToolEffect.PROJECT_MUTATION));

            ToolRegistry.runWithToolTask(TASK, () -> {
                registry.executeToolOutput("cached_lookup", "{\"query\":\"x\"}");
                registry.executeToolOutput("mutate", "{}");
                ToolOutput afterMutation = registry.executeToolOutput("cached_lookup", "{\"query\":\"x\"}");
                assertEquals("value-2", afterMutation.text());
                return null;
            });
            assertEquals(2, executions.get());
        }
    }
}
