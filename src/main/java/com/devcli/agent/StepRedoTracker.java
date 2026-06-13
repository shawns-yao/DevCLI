package com.devcli.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 失败步骤"在位重做"的状态与决策，从 {@link AgentOrchestrator} 解耦出来单独承载。
 *
 * <p>背景：Multi-Agent 的失败恢复从"生成平行恢复计划（replan）"改为"失败步骤保持原 id/依赖
 * 在 DAG 原位换思路重做"。本类只负责"该不该重做"和"重做时给什么换思路反馈"两件事，不碰调度
 * 与步骤列表——调度循环（executeSteps）问它决策，自己执行状态迁移。
 *
 * <p>每个 orchestration run/resume 独立一份状态，开始时 {@link #reset()}。非线程安全假设：
 * 仅调度主线程访问（重做决策发生在串行调度循环，不在并行 Worker 线程），用 ConcurrentHashMap
 * 仅为防御性一致，不承诺并发语义。
 */
final class StepRedoTracker {
    private final int maxRedoPerStep;
    private final Map<String, Integer> redoCount = new ConcurrentHashMap<>();
    private final Map<String, String> lastFailure = new ConcurrentHashMap<>();

    StepRedoTracker(int maxRedoPerStep) {
        this.maxRedoPerStep = Math.max(0, maxRedoPerStep);
    }

    /** 该步骤是否还能在位重做（未达上限）。 */
    boolean canRedo(String stepId) {
        return redoCount.getOrDefault(stepId, 0) < maxRedoPerStep;
    }

    /**
     * 记录一次重做：计数 +1，存本次失败原因供下轮注入"换思路"提示。
     *
     * @return 这是该步骤第几次重做（从 1 开始）
     */
    int recordRedo(String stepId, String failureReason) {
        int n = redoCount.getOrDefault(stepId, 0) + 1;
        redoCount.put(stepId, n);
        lastFailure.put(stepId, failureReason == null ? "" : failureReason);
        return n;
    }

    /** 该步骤是否处于重做中（已重做过至少一次）。 */
    boolean isRedo(String stepId) {
        return redoCount.getOrDefault(stepId, 0) > 0;
    }

    /** 该步骤上次失败原因（重做时注入 buildStepContext）；无则空串。 */
    String lastFailureReason(String stepId) {
        return lastFailure.getOrDefault(stepId, "");
    }

    int maxRedoPerStep() {
        return maxRedoPerStep;
    }

    /** 每个 run/resume 开始时清空，避免跨任务串档。 */
    void reset() {
        redoCount.clear();
        lastFailure.clear();
    }
}
