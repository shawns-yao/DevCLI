package com.devcli.render;

/**
 * 渲染器状态栏数据载体。
 *
 * <p>InlineRenderer 把这些字段格式化到底部常驻状态栏；PlainRenderer 直接忽略。
 */
public record StatusInfo(
        String model,
        long totalTokens,
        long contextWindow,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        String estimatedCost,
        String permissionMode,
        long elapsedMillis,
        String phase,
        String mcpSummary,
        String skillSummary,
        String grantSummary
) {
    public StatusInfo(String model, long totalTokens, long contextWindow, String permissionMode, long elapsedMillis) {
        this(model, totalTokens, contextWindow, 0L, 0L, 0L, null, permissionMode, elapsedMillis,
                (totalTokens > 0 || elapsedMillis > 0) ? "running" : "idle", null, null, null);
    }

    public StatusInfo(String model,
                      long totalTokens,
                      long contextWindow,
                      long inputTokens,
                      long outputTokens,
                      long cachedInputTokens,
                      String estimatedCost,
                      String permissionMode,
                      long elapsedMillis,
                      String phase) {
        this(model, totalTokens, contextWindow, inputTokens, outputTokens, cachedInputTokens,
                estimatedCost, permissionMode, elapsedMillis, phase, null, null, null);
    }

    public static StatusInfo idle(String model, long contextWindow, String permissionMode) {
        return new StatusInfo(model, 0L, contextWindow, 0L, 0L, 0L, null, permissionMode, 0L, "idle");
    }

    public static StatusInfo active(String model, long contextWindow, String permissionMode, String phase) {
        return new StatusInfo(model, 0L, contextWindow, 0L, 0L, 0L, null, permissionMode, 0L, phase);
    }

    public static StatusInfo tokens(String model,
                                    long contextWindow,
                                    long inputTokens,
                                    long outputTokens,
                                    long cachedInputTokens,
                                    String estimatedCost,
                                    String permissionMode,
                                    long elapsedMillis,
                                    String phase) {
        long total = Math.max(0L, inputTokens) + Math.max(0L, outputTokens);
        return new StatusInfo(
                model,
                total,
                contextWindow,
                Math.max(0L, inputTokens),
                Math.max(0L, outputTokens),
                Math.max(0L, cachedInputTokens),
                estimatedCost,
                permissionMode,
                elapsedMillis,
                phase == null || phase.isBlank() ? "running" : phase,
                null,
                null,
                null
        );
    }

    public StatusInfo withEnvironment(String mcpSummary, String skillSummary) {
        return new StatusInfo(
                model,
                totalTokens,
                contextWindow,
                inputTokens,
                outputTokens,
                cachedInputTokens,
                estimatedCost,
                permissionMode,
                elapsedMillis,
                phase,
                normalizeSummary(mcpSummary),
                normalizeSummary(skillSummary),
                grantSummary
        );
    }

    /**
     * 附加持久授权基线摘要。状态栏据此常驻显示基线——基线持久化后容易被遗忘，
     * 可见性是它可被接受的前提（见 docs/adr/0005）。
     */
    public StatusInfo withGrantSummary(String grantSummary) {
        return new StatusInfo(
                model,
                totalTokens,
                contextWindow,
                inputTokens,
                outputTokens,
                cachedInputTokens,
                estimatedCost,
                permissionMode,
                elapsedMillis,
                phase,
                mcpSummary,
                skillSummary,
                normalizeSummary(grantSummary)
        );
    }

    private static String normalizeSummary(String summary) {
        return summary == null || summary.isBlank() ? null : summary.trim();
    }
}
