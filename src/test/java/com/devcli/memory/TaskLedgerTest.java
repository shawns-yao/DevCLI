package com.devcli.memory;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskLedger 单元测试 + 经 {@link WorkingMemory#renderForPrompt()} 的注入闭环验证。
 */
class TaskLedgerTest {

    private static Map<String, String> steps(String... idDescPairs) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < idDescPairs.length; i += 2) {
            m.put(idDescPairs[i], idDescPairs[i + 1]);
        }
        return m;
    }

    @Test
    void emptyLedgerRendersBlank() {
        TaskLedger ledger = new TaskLedger();
        assertTrue(ledger.isEmpty());
        assertEquals("", ledger.render());
    }

    @Test
    void setPlanRegistersStepsAsPending() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "重构记忆模块", steps("task_1", "读现状", "task_2", "改代码"));
        assertFalse(ledger.isEmpty());
        String r = ledger.render();
        assertTrue(r.contains("任务账本"), "应有标题");
        assertTrue(r.contains("重构记忆模块"), "应含计划目标");
        assertTrue(r.contains("待执行: task_1, task_2"), "新注册步骤为待执行");
    }

    @Test
    void startStepRendersRunningWithDescription() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "目标", steps("task_1", "分析日志", "task_2", "修复"));
        ledger.startStep("task_1");
        String r = ledger.render();
        assertTrue(r.contains("进行中: task_1 分析日志"), "运行中步骤带描述");
        assertTrue(r.contains("待执行: task_2"), "其余仍待执行");
    }

    @Test
    void completeStepRendersDone() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "目标", steps("task_1", "分析", "task_2", "修复"));
        ledger.startStep("task_1");
        ledger.completeStep("task_1");
        String r = ledger.render();
        assertTrue(r.contains("已完成: task_1"));
        assertFalse(r.contains("进行中"), "完成后不再进行中");
    }

    @Test
    void failStepKeepsError() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "目标", steps("task_1", "分析"));
        ledger.failStep("task_1", "NPE at line 42");
        String r = ledger.render();
        assertTrue(r.contains("失败: task_1"), "应列失败步骤");
        assertTrue(r.contains("NPE at line 42"), "保留错误信息");
    }

    @Test
    void completeStepClearsPriorError() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "目标", steps("task_1", "x"));
        ledger.failStep("task_1", "transient error");
        ledger.completeStep("task_1");  // 重做成功
        String r = ledger.render();
        assertTrue(r.contains("已完成: task_1"));
        assertFalse(r.contains("transient error"), "完成后清除旧错误");
    }

    @Test
    void startUnknownStepAutoCreates() {
        TaskLedger ledger = new TaskLedger();
        ledger.startStep("dynamic_1");  // 未经 setPlan 注册
        assertFalse(ledger.isEmpty());
        assertTrue(ledger.render().contains("进行中: dynamic_1"));
    }

    @Test
    void setPlanOverwritesOldLedger() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "旧目标", steps("old_1", "旧步骤"));
        ledger.setPlan("plan-2", "新目标", steps("new_1", "新步骤"));  // replan 覆盖
        String r = ledger.render();
        assertTrue(r.contains("新目标"));
        assertFalse(r.contains("old_1"), "replan 后旧步骤不残留");
    }

    @Test
    void clearResetsLedger() {
        TaskLedger ledger = new TaskLedger();
        ledger.setPlan("plan-1", "目标", steps("task_1", "x"));
        ledger.clear();
        assertTrue(ledger.isEmpty());
        assertEquals("", ledger.render());
    }

    @Test
    void injectedIntoWorkingMemoryRender() {
        WorkingMemory wm = new WorkingMemory();
        wm.taskLedger().setPlan("plan-1", "重构上下文", steps("task_1", "读", "task_2", "写"));
        wm.taskLedger().startStep("task_1");
        String section = wm.renderForPrompt();
        assertTrue(section.contains("任务账本"), "TaskLedger 应注入 working memory 段");
        assertTrue(section.contains("进行中: task_1"), "进度可见");
    }
}
