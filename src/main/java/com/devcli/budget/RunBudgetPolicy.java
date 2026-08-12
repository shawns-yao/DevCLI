package com.devcli.budget;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

/** 一次 Run 的有限资源上限，与模型上下文窗口无关。 */
public record RunBudgetPolicy(
        Tier tier,
        long maxTotalTokens,
        long maxLlmCalls,
        long maxToolCalls,
        long maxWallClockMillis,
        BigDecimal maxEstimatedCost,
        double warningRatio,
        double softStopRatio
) {
    public enum Tier {
        ECONOMY,
        BALANCED,
        THOROUGH
    }

    public RunBudgetPolicy {
        tier = tier == null ? Tier.BALANCED : tier;
        if (maxTotalTokens <= 0 || maxLlmCalls <= 0 || maxToolCalls <= 0
                || maxWallClockMillis <= 0) {
            throw new IllegalArgumentException("run budget limits must be positive");
        }
        if (maxEstimatedCost != null && maxEstimatedCost.signum() <= 0) {
            throw new IllegalArgumentException("maxEstimatedCost must be positive");
        }
        if (warningRatio <= 0 || warningRatio >= 1
                || softStopRatio <= warningRatio || softStopRatio >= 1) {
            throw new IllegalArgumentException("budget ratios must satisfy 0 < warn < soft < 1");
        }
    }

    public static RunBudgetPolicy forTier(Tier tier) {
        Tier effective = tier == null ? Tier.BALANCED : tier;
        return switch (effective) {
            case ECONOMY -> builder().tier(effective)
                    .maxTotalTokens(64_000).maxLlmCalls(16).maxToolCalls(48)
                    .maxWallClock(Duration.ofMinutes(15)).build();
            case BALANCED -> builder().tier(effective)
                    .maxTotalTokens(256_000).maxLlmCalls(64).maxToolCalls(192)
                    .maxWallClock(Duration.ofMinutes(45)).build();
            case THOROUGH -> builder().tier(effective)
                    .maxTotalTokens(1_000_000).maxLlmCalls(192).maxToolCalls(576)
                    .maxWallClock(Duration.ofHours(2)).build();
        };
    }

    public static RunBudgetPolicy fromConfiguration() {
        Tier tier = resolveTier(configured("devcli.run.budget.tier", "DEVCLI_RUN_BUDGET_TIER", "balanced"));
        RunBudgetPolicy defaults = forTier(tier);
        return builder()
                .tier(tier)
                .maxTotalTokens(readLong("devcli.run.budget.max.tokens", "DEVCLI_RUN_BUDGET_MAX_TOKENS",
                        defaults.maxTotalTokens()))
                .maxLlmCalls(readLong("devcli.run.budget.max.llm.calls", "DEVCLI_RUN_BUDGET_MAX_LLM_CALLS",
                        defaults.maxLlmCalls()))
                .maxToolCalls(readLong("devcli.run.budget.max.tool.calls", "DEVCLI_RUN_BUDGET_MAX_TOOL_CALLS",
                        defaults.maxToolCalls()))
                .maxWallClockMillis(readLong("devcli.run.budget.max.wall.millis",
                        "DEVCLI_RUN_BUDGET_MAX_WALL_MILLIS", defaults.maxWallClockMillis()))
                .maxEstimatedCost(readDecimal("devcli.run.budget.max.estimated.cost",
                        "DEVCLI_RUN_BUDGET_MAX_ESTIMATED_COST"))
                .build();
    }

    public static Tier resolveTier(String value) {
        if (value == null || value.isBlank()) return Tier.BALANCED;
        try {
            return Tier.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown run budget tier: " + value, error);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String configured(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long readLong(String property, String environment, long fallback) {
        String raw = configured(property, environment, "");
        if (raw.isBlank()) return fallback;
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static BigDecimal readDecimal(String property, String environment) {
        String raw = configured(property, environment, "");
        if (raw.isBlank()) return null;
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class Builder {
        private Tier tier = Tier.BALANCED;
        private long maxTotalTokens = 256_000;
        private long maxLlmCalls = 64;
        private long maxToolCalls = 192;
        private long maxWallClockMillis = Duration.ofMinutes(45).toMillis();
        private BigDecimal maxEstimatedCost;
        private double warningRatio = 0.75;
        private double softStopRatio = 0.90;

        public Builder tier(Tier value) { this.tier = value; return this; }
        public Builder maxTotalTokens(long value) { this.maxTotalTokens = value; return this; }
        public Builder maxLlmCalls(long value) { this.maxLlmCalls = value; return this; }
        public Builder maxToolCalls(long value) { this.maxToolCalls = value; return this; }
        public Builder maxWallClock(Duration value) {
            this.maxWallClockMillis = value == null ? 0 : value.toMillis();
            return this;
        }
        public Builder maxWallClockMillis(long value) { this.maxWallClockMillis = value; return this; }
        public Builder maxEstimatedCost(BigDecimal value) { this.maxEstimatedCost = value; return this; }
        public Builder warningRatio(double value) { this.warningRatio = value; return this; }
        public Builder softStopRatio(double value) { this.softStopRatio = value; return this; }

        public RunBudgetPolicy build() {
            return new RunBudgetPolicy(tier, maxTotalTokens, maxLlmCalls, maxToolCalls,
                    maxWallClockMillis, maxEstimatedCost, warningRatio, softStopRatio);
        }
    }
}
