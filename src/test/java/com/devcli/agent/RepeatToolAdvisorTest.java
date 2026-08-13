package com.devcli.agent;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatToolAdvisorTest {

    private static final List<Integer> THRESHOLDS = List.of(3, 5, 8);

    private static ToolRegistry.ToolExecutionResult result(String name, String argumentsJson) {
        return result(name, argumentsJson, ToolStatus.SUCCESS);
    }

    private static ToolRegistry.ToolExecutionResult result(
            String name, String argumentsJson, ToolStatus status) {
        return new ToolRegistry.ToolExecutionResult(
                "call_1", name, argumentsJson, "ok", 10,
                status, status == ToolStatus.SUCCESS ? ToolErrorCode.NONE : ToolErrorCode.EXECUTION_FAILED,
                false, List.of(), List.of());
    }

    @Test
    void escalatesGentleThenDetailedRemindersAtThresholds() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 500, List.of(), List.of());
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));

        RepeatToolAdvisor.Reminder first = advisor.observeAndMaybeRemind(
                result("read_file", "{\"path\":\"a.txt\"}"));
        assertNotNull(first);
        assertTrue(first.gentle());
        assertEquals(3, first.consecutiveCount());
        assertEquals("read_file", first.toolName());

        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        RepeatToolAdvisor.Reminder second = advisor.observeAndMaybeRemind(
                result("read_file", "{\"path\":\"a.txt\"}"));
        assertNotNull(second);
        assertFalse(second.gentle());
        assertEquals(5, second.consecutiveCount());
        assertTrue(second.text().contains("连续调用次数: 5"));

        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        RepeatToolAdvisor.Reminder third = advisor.observeAndMaybeRemind(
                result("read_file", "{\"path\":\"a.txt\"}"));
        assertNotNull(third);
        assertFalse(third.gentle());
        assertEquals(8, third.consecutiveCount());
        // 超过最大阈值后不再提醒，交由停滞检测兜底
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
    }

    @Test
    void semanticEquivalentArgumentsShareTheChain() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 500, List.of(), List.of());
        assertNull(advisor.observeAndMaybeRemind(
                result("search_code", "{\"query\":\"  User   Service \",\"top_k\":5}")));
        assertNull(advisor.observeAndMaybeRemind(
                result("search_code", "{\"top_k\":5,\"query\":\"user service\"}")));

        RepeatToolAdvisor.Reminder reminder = advisor.observeAndMaybeRemind(
                result("search_code", "{\"query\":\"USER SERVICE\",\"top_k\":5}"));
        assertNotNull(reminder);
        assertEquals(3, reminder.consecutiveCount());
    }

    @Test
    void differentToolOrArgumentsResetTheChain() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 500, List.of(), List.of());
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        // 参数变化：链重置
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"b.txt\"}")));
        assertFalse(advisor.suspendsStagnationExit());
        // 新链重新计数
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"b.txt\"}")));
        RepeatToolAdvisor.Reminder reminder = advisor.observeAndMaybeRemind(
                result("read_file", "{\"path\":\"b.txt\"}"));
        assertNotNull(reminder);
        assertEquals(3, reminder.consecutiveCount());
    }

    @Test
    void failedAndRejectedCallsCountTowardsTheChain() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 500, List.of(), List.of());
        assertNull(advisor.observeAndMaybeRemind(
                result("execute_command", "{\"command\":\"ls\"}", ToolStatus.ERROR)));
        assertNull(advisor.observeAndMaybeRemind(
                result("execute_command", "{\"command\":\"ls\"}", ToolStatus.REJECTED)));

        RepeatToolAdvisor.Reminder reminder = advisor.observeAndMaybeRemind(
                result("execute_command", "{\"command\":\"ls\"}", ToolStatus.ERROR));
        assertNotNull(reminder);
        assertEquals(3, reminder.consecutiveCount());
    }

    @Test
    void includeAndExcludeWildcardsControlTracking() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(
                THRESHOLDS, 500, List.of("read_*", "search_*"), List.of("search_code"));
        // exclude 优先：search_code 透明，不计数也不重置
        assertNull(advisor.observeAndMaybeRemind(result("search_code", "{\"query\":\"x\"}")));
        assertFalse(advisor.suspendsStagnationExit());
        // include 命中
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        RepeatToolAdvisor.Reminder reminder = advisor.observeAndMaybeRemind(
                result("read_file", "{\"path\":\"a.txt\"}"));
        assertNotNull(reminder);
        assertEquals(3, reminder.consecutiveCount());
        // 不命中 include：透明
        assertNull(advisor.observeAndMaybeRemind(result("web_search", "{\"query\":\"y\"}")));
    }

    @Test
    void detailedReminderBoundsArgumentsPreview() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 40, List.of(), List.of());
        String bigArguments = "{\"content\":\"" + "a".repeat(200) + "\"}";
        assertNull(advisor.observeAndMaybeRemind(result("write_file", bigArguments)));
        assertNull(advisor.observeAndMaybeRemind(result("write_file", bigArguments)));
        RepeatToolAdvisor.Reminder reminder = advisor.observeAndMaybeRemind(result("write_file", bigArguments));
        assertNotNull(reminder);
        // 预览被截断，但检测用的完整指纹不受影响
        assertTrue(reminder.argumentsPreview().length() <= 40 + 20);
        assertTrue(reminder.argumentsPreview().contains("(+"));
        assertEquals(3, reminder.consecutiveCount());
    }

    @Test
    void suspendsStagnationExitOnlyWithinRepeatedRange() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 500, List.of(), List.of());
        assertFalse(advisor.suspendsStagnationExit());
        advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}"));
        assertFalse(advisor.suspendsStagnationExit());
        advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}"));
        assertTrue(advisor.suspendsStagnationExit());
        advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}"));
        assertTrue(advisor.suspendsStagnationExit());
        // 超过最大阈值 8 后不再暂缓，停滞检测兜底
        for (int i = 0; i < 6; i++) {
            advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}"));
        }
        assertFalse(advisor.suspendsStagnationExit());
    }

    @Test
    void disabledAdvisorIsCompletelyInert() {
        RepeatToolAdvisor advisor = RepeatToolAdvisor.disabled();
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertNull(advisor.observeAndMaybeRemind(result("read_file", "{\"path\":\"a.txt\"}")));
        assertFalse(advisor.suspendsStagnationExit());
        assertEquals(0, advisor.consecutiveCount());
    }

    @Test
    void previewTruncationDoesNotSplitSurrogatePairs() {
        RepeatToolAdvisor advisor = new RepeatToolAdvisor(THRESHOLDS, 10, List.of(), List.of());
        String content = "a".repeat(8) + "\uD83D\uDE00" + "b".repeat(10);
        String args = "{\"content\":\"" + content + "\"}";
        assertNull(advisor.observeAndMaybeRemind(result("write_file", args)));
        assertNull(advisor.observeAndMaybeRemind(result("write_file", args)));
        RepeatToolAdvisor.Reminder reminder = advisor.observeAndMaybeRemind(result("write_file", args));
        assertNotNull(reminder);
        String preview = reminder.argumentsPreview();
        for (int i = 0; i < preview.length(); i++) {
            char c = preview.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < preview.length()
                        && Character.isLowSurrogate(preview.charAt(i + 1)));
            } else {
                assertFalse(Character.isLowSurrogate(c));
            }
        }
    }
}
