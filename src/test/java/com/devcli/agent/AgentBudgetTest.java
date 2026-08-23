package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.llm.GLMClient;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBudgetTest {

    @Test
    void initiallyWithinBudget() {
        AgentBudget budget = new AgentBudget(1000, 3, 50);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void tokenBudgetExceededAfterAccumulation() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(60, 30);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordTokens(20, 0);
        assertEquals(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED, budget.check());
    }

    @Test
    void stagnationDetectedAfterRepeatedToolCalls() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        List<LlmClient.ToolCall> sameCall = List.of(
                new LlmClient.ToolCall("call_1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"))
        );

        budget.recordToolCalls(sameCall);
        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolCalls(sameCall);
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void semanticEquivalentArgumentsTriggerStagnation() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);

        budget.recordToolCalls(List.of(toolCall("search_code",
                "{\"query\":\"  User   Service \",\"top_k\":5}")));
        budget.recordToolCalls(List.of(toolCall("search_code",
                "{\"top_k\":5,\"query\":\"user service\"}")));
        budget.recordToolCalls(List.of(toolCall("search_code",
                "{\"query\":\"USER SERVICE\",\"top_k\":5}")));

        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void stagnationResetsWhenToolCallsDiffer() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"a.txt\"}")));
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"a.txt\"}")));
        budget.recordToolCalls(List.of(toolCall("read_file", "{\"path\":\"b.txt\"}")));
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void hardIterationLimitTriggersAfterEnoughIterations() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 3);
        budget.beginIteration();
        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.beginIteration();
        assertEquals(AgentBudget.ExitReason.HARD_ITERATION_LIMIT, budget.check());
    }

    @Test
    void stagnationTakesPrecedenceOverTokenBudget() {
        AgentBudget budget = new AgentBudget(100, 2, 50);
        budget.recordTokens(200, 0);
        budget.recordToolCalls(List.of(toolCall("x", "{}")));
        budget.recordToolCalls(List.of(toolCall("x", "{}")));
        assertEquals(AgentBudget.ExitReason.STAGNATION_DETECTED, budget.check());
    }

    @Test
    void repeatedToolErrorsTriggerCircuitBreaker() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);

        budget.recordToolResult("mcp__demo__search", "MCP 参数校验失败: $.query is required");
        budget.recordToolResult("mcp__demo__search", "MCP 参数校验失败: $.query is required");
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolResult("mcp__demo__search", "MCP 参数校验失败: $.query is required");
        assertEquals(AgentBudget.ExitReason.REPEATED_TOOL_ERROR, budget.check());
        assertTrue(budget.describeExit(AgentBudget.ExitReason.REPEATED_TOOL_ERROR).contains("mcp__demo__search|schema"));
    }

    @Test
    void successfulToolResultResetsErrorCircuitBreakerWindow() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);

        budget.recordToolResult("read_file", "工具执行失败: no such file");
        budget.recordToolResult("read_file", "ok");
        budget.recordToolResult("read_file", "工具执行失败: no such file");
        budget.recordToolResult("read_file", "工具执行失败: no such file");

        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void successfulStructuredResultDoesNotTreatErrorLikeTextAsFailure() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);
        ToolRegistry.ToolExecutionResult result = new ToolRegistry.ToolExecutionResult(
                "call_1", "search_code", "{}", "symbol not found in comments",
                1, ToolStatus.SUCCESS, ToolErrorCode.NONE, false, List.of());

        budget.recordToolResult(result);
        budget.recordToolResult(result);
        budget.recordToolResult(result);

        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());
    }

    @Test
    void structuredErrorCodeTriggersCircuitBreakerRegardlessOfMessageText() {
        AgentBudget budget = new AgentBudget(1_000_000, 3, 50);

        budget.recordToolResult(toolError("call_1", "字段缺失"));
        budget.recordToolResult(toolError("call_2", "输入不符合约束"));
        assertEquals(AgentBudget.ExitReason.WITHIN_BUDGET, budget.check());

        budget.recordToolResult(toolError("call_3", "请修正参数"));
        assertEquals(AgentBudget.ExitReason.REPEATED_TOOL_ERROR, budget.check());
        assertTrue(budget.describeExit(AgentBudget.ExitReason.REPEATED_TOOL_ERROR)
                .contains("mcp__demo__search|schema"));
    }

    private static ToolRegistry.ToolExecutionResult toolError(String id, String message) {
        return new ToolRegistry.ToolExecutionResult(
                id, "mcp__demo__search", "{}", message,
                1, ToolStatus.REJECTED, ToolErrorCode.INVALID_ARGUMENTS, true, List.of());
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(0, 3, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 1, 50));
        assertThrows(IllegalArgumentException.class, () -> new AgentBudget(100, 3, 0));
    }

    @Test
    void describeExitContainsRelevantNumbers() {
        AgentBudget budget = new AgentBudget(100, 3, 50);
        budget.recordTokens(80, 40);
        String message = budget.describeExit(AgentBudget.ExitReason.TOKEN_BUDGET_EXCEEDED);
        assertTrue(message.contains("120"));
        assertTrue(message.contains("100"));
    }

    @Test
    void defaultTokenBudgetIsFiniteAndDerivedFromModelWindow() {
        AgentBudget budget = AgentBudget.fromLlmClient(new GLMClient("test-key"));

        assertEquals(800_000, budget.tokenBudget());
        assertTrue(budget.tokenBudget() < Integer.MAX_VALUE);
    }

    @Test
    void defaultHardIterationLimitIs100() {
        String old = System.getProperty("devcli.react.hard.max.iterations");
        try {
            System.clearProperty("devcli.react.hard.max.iterations");
            assertEquals(100, AgentBudget.fromSystemProperties().hardMaxIterations());
        } finally {
            restoreProperty("devcli.react.hard.max.iterations", old);
        }
    }

    @Test
    void systemPropertyCanOverrideHardIterationLimit() {
        String old = System.getProperty("devcli.react.hard.max.iterations");
        try {
            System.setProperty("devcli.react.hard.max.iterations", "7");
            assertEquals(7, AgentBudget.fromSystemProperties().hardMaxIterations());
        } finally {
            restoreProperty("devcli.react.hard.max.iterations", old);
        }
    }

    @Test
    void systemPropertyCanStillOverrideDynamicTokenBudget() {
        String old = System.getProperty("devcli.react.token.budget");
        try {
            System.setProperty("devcli.react.token.budget", "12345");
            AgentBudget budget = AgentBudget.fromLlmClient(new GLMClient("test-key"));

            assertEquals(12345, budget.tokenBudget());
        } finally {
            if (old == null) {
                System.clearProperty("devcli.react.token.budget");
            } else {
                System.setProperty("devcli.react.token.budget", old);
            }
        }
    }

    private LlmClient.ToolCall toolCall(String name, String args) {
        return new LlmClient.ToolCall("call_" + name + "_" + args.hashCode(),
                new LlmClient.ToolCall.Function(name, args));
    }

    private static void restoreProperty(String key, String old) {
        if (old == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, old);
        }
    }
}
