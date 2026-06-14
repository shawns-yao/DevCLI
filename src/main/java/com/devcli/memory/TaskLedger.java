package com.devcli.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务账本：当前会话计划执行进度的结构化投影。
 *
 * <p>解决的问题：长 ReAct / Plan 循环里，"执行到哪一步" 如果只写在对话摘要的自然语言里，
 * 压缩时容易被改写或丢失，模型只能从文本里猜。TaskLedger 把进度结构化，挂在
 * {@link WorkingMemory}（不进 conversationHistory），因此 {@code ConversationHistoryCompactor}
 * 压缩对话历史时不会触碰它——第 15 轮压缩后第 20 轮仍能看到当前 step / 已完成 / 待执行 / 失败。
 *
 * <p>定位：projection（投影视图），不是 source of truth。真实执行状态仍由
 * {@code plan.ExecutionPlan} / {@code plan.Task} 拥有；{@code PlanExecuteAgent} 在 task 状态变化时
 * 把进度投影到这里。摘要只引用进度，不拥有任务状态。
 *
 * <p>线程安全：所有方法 synchronized（与 {@link WorkingMemory} 一致，防御 Multi-Agent 并发回写）。
 */
public class TaskLedger {

    /** 步骤状态。对齐 {@code plan.Task.TaskStatus}，额外加 SKIPPED 供跳过场景。 */
    public enum StepStatus {
        PENDING, RUNNING, DONE, FAILED, SKIPPED
    }

    /** 单个步骤的账本条目。 */
    public static final class Entry {
        private final String stepId;
        private final String description;
        private StepStatus status;
        private String lastError;

        Entry(String stepId, String description) {
            this.stepId = stepId;
            this.description = description == null ? "" : description;
            this.status = StepStatus.PENDING;
            this.lastError = "";
        }

        public String stepId() { return stepId; }
        public String description() { return description; }
        public StepStatus status() { return status; }
        public String lastError() { return lastError; }
    }

    private String planId = "";
    private String goal = "";
    private final LinkedHashMap<String, Entry> steps = new LinkedHashMap<>();

    /**
     * 设置当前计划并注册全部步骤（覆盖旧账本——一次会话可能多次 replan）。
     *
     * @param planId       计划 id
     * @param goal         计划目标
     * @param stepIdToDesc 步骤 id → 描述（保持插入顺序即展示顺序）
     */
    public synchronized void setPlan(String planId, String goal, Map<String, String> stepIdToDesc) {
        this.planId = planId == null ? "" : planId;
        this.goal = goal == null ? "" : goal;
        steps.clear();
        if (stepIdToDesc != null) {
            for (Map.Entry<String, String> e : stepIdToDesc.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) continue;
                steps.put(e.getKey(), new Entry(e.getKey(), e.getValue()));
            }
        }
    }

    /** 标记步骤开始（RUNNING）。step 不存在时按需创建（兼容动态新增 step）。 */
    public synchronized void startStep(String stepId) {
        Entry entry = ensure(stepId);
        if (entry != null) {
            entry.status = StepStatus.RUNNING;
        }
    }

    /** 标记步骤完成（DONE），清空其错误。 */
    public synchronized void completeStep(String stepId) {
        Entry entry = ensure(stepId);
        if (entry != null) {
            entry.status = StepStatus.DONE;
            entry.lastError = "";
        }
    }

    /** 标记步骤失败（FAILED），保留错误信息。 */
    public synchronized void failStep(String stepId, String error) {
        Entry entry = ensure(stepId);
        if (entry != null) {
            entry.status = StepStatus.FAILED;
            entry.lastError = error == null ? "" : error;
        }
    }

    private Entry ensure(String stepId) {
        if (stepId == null || stepId.isBlank()) return null;
        return steps.computeIfAbsent(stepId, id -> new Entry(id, ""));
    }

    public synchronized boolean isEmpty() {
        return steps.isEmpty();
    }

    public synchronized void clear() {
        planId = "";
        goal = "";
        steps.clear();
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(steps.values()));
    }

    /**
     * 渲染为紧凑的 system prompt 段（不含外层 "## Working Memory" 标题，由调用方包裹）。
     * 空账本返回空串。
     */
    public synchronized String render() {
        if (steps.isEmpty()) return "";
        List<String> running = new ArrayList<>();
        List<String> done = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Entry e : steps.values()) {
            switch (e.status) {
                case RUNNING -> running.add(e.stepId + " " + compact(e.description));
                case DONE -> done.add(e.stepId);
                case FAILED -> failed.add(e.stepId + (e.lastError.isBlank() ? "" : " (" + compact(e.lastError) + ")"));
                case PENDING -> pending.add(e.stepId);
                case SKIPPED -> { /* 跳过的步骤不占渲染预算 */ }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### 任务账本（当前计划执行进度）\n\n");
        if (!goal.isBlank()) {
            sb.append("- 计划: ").append(compact(goal)).append('\n');
        }
        if (!running.isEmpty()) {
            sb.append("- 进行中: ").append(String.join("; ", running)).append('\n');
        }
        if (!done.isEmpty()) {
            sb.append("- 已完成: ").append(String.join(", ", done)).append('\n');
        }
        if (!pending.isEmpty()) {
            sb.append("- 待执行: ").append(String.join(", ", pending)).append('\n');
        }
        if (!failed.isEmpty()) {
            sb.append("- 失败: ").append(String.join("; ", failed)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String compact(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
    }
}
