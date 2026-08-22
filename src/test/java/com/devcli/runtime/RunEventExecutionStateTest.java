package com.devcli.runtime;

import com.devcli.runtime.api.RunEventJsonCodec;
import com.devcli.runtime.event.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;

class RunEventExecutionStateTest {

    @Test
    void encodesExecutionStateForRuntimeConsumers() throws Exception {
        String json = RunEventJsonCodec.encode(
                new RunEvent.ExecutionStateChanged(
                        2, RunEvent.ExecutionState.TOOL_RESULTS_PAIRED, "2 个工具结果已对账"),
                "turn-1");
        JsonNode node = new ObjectMapper().readTree(json);

        assertEquals("turn-1", node.get("turn_id").asText());
        assertEquals(2, node.get("iteration").asInt());
        assertEquals("TOOL_RESULTS_PAIRED", node.get("state").asText());
        assertEquals("2 个工具结果已对账", node.get("reason").asText());
    }

    @Test
    void encodesContextRefreshLifecycle() throws Exception {
        String json = RunEventJsonCodec.encode(new RunEvent.ContextRefresh(
                "step-1", RunEvent.ContextRefreshState.RUNNING,
                List.of("src/Service.java"), "刷新完成"), "turn-2");
        JsonNode node = new ObjectMapper().readTree(json);

        assertEquals("step-1", node.get("scope").asText());
        assertEquals("RUNNING", node.get("state").asText());
        assertEquals("src/Service.java", node.path("resources").get(0).asText());
    }
}
