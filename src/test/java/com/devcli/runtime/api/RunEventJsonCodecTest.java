package com.devcli.runtime.api;

import com.devcli.runtime.event.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventJsonCodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodesStableTurnAndToolPayloads() throws Exception {
        RunEvent.ToolCalls event = new RunEvent.ToolCalls(List.of(
                new RunEvent.ToolCallData(
                        "call_1", "read_file", "{\"path\":\"a\\\"b.txt\"}")));

        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(event, "turn_1"));

        assertEquals(1, payload.path("schema_version").asInt());
        assertEquals("turn_1", payload.path("turn_id").asText());
        assertEquals("call_1", payload.path("calls").get(0).path("id").asText());
        assertEquals("read_file", payload.path("calls").get(0).path("name").asText());
        assertEquals("a\"b.txt", payload.path("calls").get(0).path("arguments").path("path").asText());
    }

    @Test
    void keepsInvalidToolArgumentsAsTextInsteadOfBreakingEvent() throws Exception {
        RunEvent.ToolCalls event = new RunEvent.ToolCalls(List.of(
                new RunEvent.ToolCallData("call_1", "custom", "not-json")));

        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(event, "turn_1"));

        assertTrue(payload.path("calls").get(0).path("arguments").isTextual());
        assertEquals("not-json", payload.path("calls").get(0).path("arguments").asText());
    }

    @Test
    void threadEventDoesNotInventTurnCorrelation() throws Exception {
        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.ThreadCreated("thread_1"), ""));

        assertEquals("thread_1", payload.path("thread_id").asText());
        assertFalse(payload.has("turn_id"));
    }

    @Test
    void encodesQueueStateAndAction() throws Exception {
        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.QueueUpdated("STEERING", 2, 1, "enqueued"), "turn_1"));

        assertEquals("STEERING", payload.path("channel").asText());
        assertEquals(2, payload.path("steering_pending").asInt());
        assertEquals(1, payload.path("follow_up_pending").asInt());
        assertEquals("enqueued", payload.path("action").asText());
    }

    @Test
    void encodesSessionStateAndCustomMessage() throws Exception {
        JsonNode state = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.SessionStateChanged("thread_1", "running", "turn_started"), ""));
        JsonNode custom = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.CustomMessage("lsp.diagnostic", "found issue",
                        Map.of("severity", "warning")), "turn_1"));

        assertEquals("thread_1", state.path("session_id").asText());
        assertEquals("running", state.path("state").asText());
        assertEquals("lsp.diagnostic", custom.path("message_type").asText());
        assertEquals("found issue", custom.path("content").asText());
        assertEquals("warning", custom.path("attributes").path("severity").asText());
    }

    @Test
    void encodesBudgetUsageAndUnknownCostExplicitly() throws Exception {
        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.BudgetUsageUpdated(
                        "run_1", "worker", "worker-1", "attempt-2",
                        100, 40, 20, 3, 4, "unknown", "unknown", "CONTINUE"),
                "turn_1"));

        assertEquals("run_1", payload.path("run_id").asText());
        assertEquals("worker", payload.path("phase").asText());
        assertEquals(100, payload.path("input_tokens").asLong());
        assertEquals(3, payload.path("llm_calls").asLong());
        assertEquals("unknown", payload.path("estimated_cost").asText());
    }
}
