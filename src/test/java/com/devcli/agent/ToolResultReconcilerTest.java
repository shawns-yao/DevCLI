package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultReconcilerTest {

    @Test
    void restoresOneResultPerOriginalCallInOriginalOrder() {
        List<LlmClient.ToolCall> calls = List.of(
                new LlmClient.ToolCall("call_a",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}")),
                new LlmClient.ToolCall("call_b",
                        new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}")));
        List<ToolRegistry.ToolExecutionResult> returned = List.of(
                new ToolRegistry.ToolExecutionResult(
                        "call_b", "list_dir", "{\"path\":\".\"}", "b", 1,
                        ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()),
                new ToolRegistry.ToolExecutionResult(
                        "unknown", "grep_code", "{}", "ignored", 1,
                        ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()),
                new ToolRegistry.ToolExecutionResult(
                        "call_b", "list_dir", "{\"path\":\".\"}", "duplicate", 1,
                        ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of()));

        ToolResultReconciler.Reconciliation reconciliation =
                ToolResultReconciler.reconcile(calls, returned);

        assertEquals(List.of("call_a", "call_b"),
                reconciliation.results().stream().map(ToolRegistry.ToolExecutionResult::id).toList());
        assertEquals(List.of("read_file", "list_dir"),
                reconciliation.results().stream().map(ToolRegistry.ToolExecutionResult::name).toList());
        assertEquals(ToolStatus.ERROR, reconciliation.results().get(0).status());
        assertTrue(reconciliation.results().get(0).result().contains("未返回结果"));
        assertEquals("b", reconciliation.results().get(1).result());
        assertEquals(3, reconciliation.issues().size());
    }
}
