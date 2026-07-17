package com.devcli.tool;

import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ToolSideChannelTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sideChannelSurvivesGovernanceCacheAndBatchResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ProbeSideChannel channel = new ProbeSideChannel("evidence-1");
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.registerTool(new ToolRegistry.Tool(
                    "typed_lookup",
                    "test",
                    JSON.readTree("{\"type\":\"object\"}"),
                    (ToolRegistry.StructuredToolExecutor) arguments -> {
                        executions.incrementAndGet();
                        return ToolOutput.success("visible-result").withSideChannel(channel);
                    },
                    ToolRegistry.ToolEffect.READ_ONLY));

            ToolRegistry.ToolExecutionResult first = execute(registry, "call-1");
            ToolRegistry.ToolExecutionResult second = execute(registry, "call-2");

            assertEquals(1, executions.get());
            assertEquals("visible-result", first.result());
            assertEquals(1, first.sideChannels().size());
            assertEquals(1, second.sideChannels().size());
            assertEquals("evidence-1",
                    assertInstanceOf(ProbeSideChannel.class, second.sideChannels().get(0)).value());
        }
    }

    private static ToolRegistry.ToolExecutionResult execute(ToolRegistry registry, String id) {
        return registry.executeTools(List.of(new ToolRegistry.ToolInvocation(
                id, "typed_lookup", "{}"))).get(0);
    }

    private record ProbeSideChannel(String value) implements ToolSideChannel {
    }
}
