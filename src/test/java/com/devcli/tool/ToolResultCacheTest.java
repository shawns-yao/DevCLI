package com.devcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolResultCacheTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void semanticallyEquivalentReadOnlyCallsShareCachedResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "value-" + executions.incrementAndGet(),
                    ToolRegistry.ToolEffect.READ_ONLY));

            ToolOutput first = registry.executeToolOutput("cached_lookup", "{\"query\":\"  User   Service \",\"limit\":5}");
            ToolOutput second = registry.executeToolOutput("cached_lookup", "{\"limit\":5,\"query\":\"user service\"}");

            assertEquals("value-1", first.text());
            assertEquals("value-1", second.text());
            assertEquals(1, executions.get());
        }
    }

    @Test
    void toolCatalogChangeInvalidatesCachedResult() throws Exception {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "v1", ToolRegistry.ToolEffect.READ_ONLY));
            assertEquals("v1", registry.executeToolOutput("cached_lookup", "{}").text());

            registry.registerTool(new ToolRegistry.Tool(
                    "cached_lookup", "test", JSON.readTree("{\"type\":\"object\"}"),
                    arguments -> "v2", ToolRegistry.ToolEffect.READ_ONLY));

            assertEquals("v2", registry.executeToolOutput("cached_lookup", "{}").text());
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

            registry.executeToolOutput("cached_lookup", "{\"query\":\"x\"}");
            registry.executeToolOutput("mutate", "{}");
            ToolOutput afterMutation = registry.executeToolOutput("cached_lookup", "{\"query\":\"x\"}");

            assertEquals("value-2", afterMutation.text());
            assertEquals(2, executions.get());
        }
    }
}
