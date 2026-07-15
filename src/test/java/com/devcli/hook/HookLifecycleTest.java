package com.devcli.hook;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HookLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void closesMessageAndTurnAfterAllToolResults() {
        try (EventRecordingRegistry registry = new EventRecordingRegistry()) {
            HookLifecycle lifecycle = lifecycle(registry);
            LlmClient.ToolCall first = toolCall("call_1", "read_file");
            LlmClient.ToolCall second = toolCall("call_2", "list_dir");

            lifecycle.startAgent();
            lifecycle.startTurn(1);
            lifecycle.assistantMessageCompleted(1, 2);
            lifecycle.toolExecutionsStarted(1, List.of(first, second));
            lifecycle.toolResultsReceived(1, List.of(
                    result("call_1", "read_file"),
                    result("call_2", "list_dir")));
            lifecycle.endAgent();
            lifecycle.endAgent();

            assertEquals(List.of(
                    "agent_start", "turn_start", "message_start", "message_end",
                    "tool_execution_start", "tool_execution_start",
                    "tool_execution_end", "tool_execution_end",
                    "turn_end", "agent_end"), registry.events);
        }
    }

    @Test
    void closesNoToolTurnImmediately() {
        try (EventRecordingRegistry registry = new EventRecordingRegistry()) {
            HookLifecycle lifecycle = lifecycle(registry);

            lifecycle.startAgent();
            lifecycle.startTurn(2);
            lifecycle.assistantMessageCompleted(2, 0);
            lifecycle.endAgent();

            assertEquals(List.of(
                    "agent_start", "turn_start", "message_start",
                    "message_end", "turn_end", "agent_end"), registry.events);
        }
    }

    private static HookLifecycle lifecycle(EventRecordingRegistry registry) {
        List<HookDefinition> hooks = new ArrayList<>();
        for (HookEvent event : HookEvent.values()) {
            hooks.add(new HookDefinition(
                    event.wireName(), event.wireName(), event, true,
                    "record", JSON.createObjectNode().put("event", "${event}"),
                    HookDefinition.FailureMode.REQUIRED, false));
        }
        return HookLifecycle.create(
                HookDispatcher.create(registry, hooks),
                new HookDispatcher.HookContext("project", "run", 0, "", "", ""));
    }

    private static LlmClient.ToolCall toolCall(String id, String name) {
        return new LlmClient.ToolCall(
                id, new LlmClient.ToolCall.Function(name, "{}"));
    }

    private static ToolRegistry.ToolExecutionResult result(String id, String name) {
        return new ToolRegistry.ToolExecutionResult(
                id, name, "{}", "ok", 1,
                ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of());
    }

    private static final class EventRecordingRegistry extends ToolRegistry {
        private final List<String> events = new ArrayList<>();

        @Override
        public ToolEffect toolEffect(String name) {
            return ToolEffect.READ_ONLY;
        }

        @Override
        public ToolOutput executeToolOutput(String name, String argumentsJson) {
            try {
                events.add(JSON.readTree(argumentsJson).path("event").asText());
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
            return ToolOutput.success("ok");
        }
    }
}
