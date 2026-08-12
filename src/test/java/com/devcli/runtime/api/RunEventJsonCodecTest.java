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

    @Test
    void envelopeUsesSchemaV2AndPersistsCorrelationFields() throws Exception {
        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.TurnStarted("task"),
                new com.devcli.observability.RunTelemetry(
                        "run-1", "turn-1", "step-1", "agent-1", "attempt-1", "trace-1")));
        assertEquals(2, payload.path("schema_version").asInt());
        assertEquals("run-1", payload.path("run_id").asText());
        assertEquals("turn-1", payload.path("turn_id").asText());
        assertEquals("step-1", payload.path("step_id").asText());
        assertEquals("agent-1", payload.path("agent_id").asText());
        assertEquals("attempt-1", payload.path("attempt_id").asText());
        assertEquals("trace-1", payload.path("trace_id").asText());
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void encodesStableTurnAndToolPayloads() throws Exception {
        RunEvent.ToolCalls event = new RunEvent.ToolCalls(List.of(
                new RunEvent.ToolCallData(
                        "call_1", "read_file", "{\"path\":\"a\\\"b.txt\"}")));

        JsonNode payload = MAPPER.readTree(RunEventJsonCodec.encode(event, "turn_1"));

        assertEquals(2, payload.path("schema_version").asInt());
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

    @Test
    void encodesAttemptRetryAndRecoveryCorrelation() throws Exception {
        JsonNode attempt = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.AttemptStarted(
                        "run_1", "attempt_2", "attempt_1", "CORRECTION", "step_1",
                        "reviewer_rejected", 2, 0), "turn_1"));
        JsonNode retry = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.RetryScheduled(
                        "run_1", "INFRASTRUCTURE_RETRY", "llm",
                        "RATE_LIMITED", 3, 800), "turn_1"));
        JsonNode recovery = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.RecoveryReconciled(
                        "run_1", "agent-checkpoint:orch", "ROLLED_BACK",
                        "ALLOW", "safe_recovery_point"), ""));

        assertEquals("attempt_2", attempt.path("attempt_id").asText());
        assertEquals("attempt_1", attempt.path("parent_attempt_id").asText());
        assertEquals("CORRECTION", attempt.path("kind").asText());
        assertEquals(800, retry.path("backoff_millis").asLong());
        assertEquals("ROLLED_BACK", recovery.path("patch_journal_action").asText());
    }

    @Test
    void encodesSecurityAndSandboxEvents() throws Exception {
        JsonNode security = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.SecurityDecisionMade(
                        "run_1", "execute_command", "sandboxed", "MAVEN_TEST",
                        true, true, "maven_test"), "turn_1"));
        JsonNode sandbox = MAPPER.readTree(RunEventJsonCodec.encode(
                new RunEvent.SandboxExecution(
                        "run_1", "MAVEN_TEST", "started", "maven_test"), "turn_1"));

        assertEquals("sandboxed", security.path("domain").asText());
        assertTrue(security.path("approval_required").asBoolean());
        assertEquals("started", sandbox.path("state").asText());
    }
}
