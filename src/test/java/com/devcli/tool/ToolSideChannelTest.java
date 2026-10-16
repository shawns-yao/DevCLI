package com.devcli.tool;

import com.devcli.tool.ToolContract;
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
            registry.registerTool(new ToolContract.Tool(
                    "typed_lookup",
                    "test",
                    JSON.readTree("{\"type\":\"object\"}"),
                    (ToolContract.StructuredToolExecutor) arguments -> {
                        executions.incrementAndGet();
                        return ToolOutput.success("visible-result").withSideChannel(channel);
                    },
                    ToolContract.ToolEffect.READ_ONLY));

            ToolContract.ToolExecutionResult[] results = new ToolContract.ToolExecutionResult[2];
            // 结果缓存按任务隔离，生产路径由 AgentExecutionEngine 按 turn 建立身份。
            ToolRegistry.runWithToolTask("test-task", () -> {
                results[0] = execute(registry, "call-1");
                results[1] = execute(registry, "call-2");
                return null;
            });
            ToolContract.ToolExecutionResult first = results[0];
            ToolContract.ToolExecutionResult second = results[1];

            assertEquals(1, executions.get());
            assertEquals("visible-result", first.result());
            assertEquals(1, first.sideChannels().size());
            assertEquals(1, second.sideChannels().size());
            assertEquals("evidence-1",
                    assertInstanceOf(ProbeSideChannel.class, second.sideChannels().get(0)).value());
        }
    }

    private static ToolContract.ToolExecutionResult execute(ToolRegistry registry, String id) {
        return registry.executeTools(List.of(new ToolContract.ToolInvocation(
                id, "typed_lookup", "{}"))).get(0);
    }

    private record ProbeSideChannel(String value) implements ToolSideChannel {
    }
}
