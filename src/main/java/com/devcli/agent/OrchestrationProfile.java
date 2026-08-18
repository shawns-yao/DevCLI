package com.devcli.agent;

/**
 * 编排执行器内部能力配置。交互入口统一为 Plan；STANDARD 仅供旧执行器兼容。
 *
 * <p>配置只决定确定性的执行外壳，不在运行中根据任务内容自动切换。</p>
 */
public enum OrchestrationProfile {
    STANDARD("plan", "Plan-and-Execute", "devcli-plan-executor", 4,
            false, false, false),
    TEAM("plan", "Plan", "devcli-plan", 8,
            true, true, true);

    private final String snapshotMode;
    private final String displayName;
    private final String threadNamePrefix;
    private final int maxParallelism;
    private final boolean workerPool;
    private final boolean reviewerGate;
    private final boolean checkpointResume;

    OrchestrationProfile(String snapshotMode,
                         String displayName,
                         String threadNamePrefix,
                         int maxParallelism,
                         boolean workerPool,
                         boolean reviewerGate,
                         boolean checkpointResume) {
        this.snapshotMode = snapshotMode;
        this.displayName = displayName;
        this.threadNamePrefix = threadNamePrefix;
        this.maxParallelism = maxParallelism;
        this.workerPool = workerPool;
        this.reviewerGate = reviewerGate;
        this.checkpointResume = checkpointResume;
    }

    public String snapshotMode() {
        return snapshotMode;
    }

    public String displayName() {
        return displayName;
    }

    String threadNamePrefix() {
        return threadNamePrefix;
    }

    public int maxParallelism() {
        return maxParallelism;
    }

    public boolean usesWorkerPool() {
        return workerPool;
    }

    public boolean requiresReviewerGate() {
        return reviewerGate;
    }

    public boolean supportsCheckpointResume() {
        return checkpointResume;
    }

    int parallelismFor(int itemCount, int requestedParallelism) {
        if (itemCount <= 0) {
            return 0;
        }
        int requested = requestedParallelism <= 0 ? 1 : requestedParallelism;
        return Math.max(1, Math.min(itemCount, Math.min(requested, maxParallelism)));
    }
}
