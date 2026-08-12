package com.devcli.agent;

import java.util.Locale;

/** 结构化执行的审查强度；策略只选择既有执行语义，不复制编排状态机。 */
public enum ExecutionReviewPolicy {
    PLAN_REVIEW("plan", false, false, false),
    TEAM_REVIEW("team", true, true, true);

    private final String cliValue;
    private final boolean workerPool;
    private final boolean preReview;
    private final boolean reviewer;

    ExecutionReviewPolicy(String cliValue, boolean workerPool,
                          boolean preReview, boolean reviewer) {
        this.cliValue = cliValue;
        this.workerPool = workerPool;
        this.preReview = preReview;
        this.reviewer = reviewer;
    }

    public String cliValue() { return cliValue; }
    public boolean usesWorkerPool() { return workerPool; }
    public boolean usesPreReview() { return preReview; }
    public boolean usesReviewer() { return reviewer; }

    public static ExecutionReviewPolicy parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "plan", "plan-review" -> PLAN_REVIEW;
            case "team", "team-review" -> TEAM_REVIEW;
            default -> null;
        };
    }
}
