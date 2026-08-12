package com.devcli.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 并行 Agent 共享的单一原子预算账本。 */
public final class BudgetLedger {
    private final RunBudgetPolicy policy;
    private final PricingCatalog pricingCatalog;
    private final long startedAtMillis;
    private final Map<String, Long> reservations = new HashMap<>();

    private long inputTokens;
    private long outputTokens;
    private long cachedInputTokens;
    private long llmCalls;
    private long toolCalls;
    private long reservedTokens;
    private BigDecimal estimatedCost = BigDecimal.ZERO;
    private String currency = "unknown";
    private long unknownPricingCount;
    private String exitReason = "";

    public BudgetLedger(RunBudgetPolicy policy, PricingCatalog pricingCatalog) {
        this(policy, pricingCatalog, System.currentTimeMillis());
    }

    public BudgetLedger(RunBudgetPolicy policy, PricingCatalog pricingCatalog,
                        RunBudget.Snapshot restored) {
        this(policy, pricingCatalog,
                System.currentTimeMillis() - Math.max(0,
                        restored == null ? 0 : restored.elapsedMillis()));
        if (restored == null) {
            return;
        }
        this.inputTokens = Math.max(0, restored.inputTokens());
        this.outputTokens = Math.max(0, restored.outputTokens());
        this.cachedInputTokens = Math.max(0, restored.cachedInputTokens());
        this.llmCalls = Math.max(0, restored.llmCalls());
        this.toolCalls = Math.max(0, restored.toolCalls());
        this.estimatedCost = restored.estimatedCost() == null
                ? BigDecimal.ZERO : restored.estimatedCost().max(BigDecimal.ZERO);
        this.currency = restored.currency() == null || restored.currency().isBlank()
                ? "unknown" : restored.currency();
        this.unknownPricingCount = Math.max(0, restored.unknownPricingCount());
        this.exitReason = restored.exitReason() == null ? "" : restored.exitReason();
    }

    BudgetLedger(RunBudgetPolicy policy, PricingCatalog pricingCatalog, long startedAtMillis) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.pricingCatalog = pricingCatalog == null ? PricingCatalog.empty() : pricingCatalog;
        this.startedAtMillis = Math.max(0, startedAtMillis);
    }

    public synchronized RunBudget.Admission tryStartLlmRequest(
            String phase, String agent, String attempt, long requestedReservation) {
        long reservation = Math.max(0, requestedReservation);
        if (isTimeOrCostExhausted()
                || llmCalls >= policy.maxLlmCalls()
                || consumedTokens() + reservedTokens + reservation > policy.maxTotalTokens()) {
            String reason = llmCalls >= policy.maxLlmCalls()
                    ? "llm_call_limit" : "token_or_time_limit";
            markHardStop(reason);
            return RunBudget.Admission.denied(phase, agent, attempt, reason);
        }
        String reservationId = "budget_" + UUID.randomUUID().toString().replace("-", "");
        llmCalls++;
        reservedTokens += reservation;
        reservations.put(reservationId, reservation);
        return RunBudget.Admission.allowed(
                phase, agent, attempt, llmCalls, reservationId, reservation);
    }

    public synchronized void recordLlmUsage(
            RunBudget.Admission admission, String provider, String model,
            long responseInputTokens, long responseOutputTokens, long responseCachedInputTokens) {
        if (admission == null || !admission.allowed() || !settle(admission)) return;
        inputTokens += Math.max(0, responseInputTokens);
        outputTokens += Math.max(0, responseOutputTokens);
        cachedInputTokens += Math.max(0, responseCachedInputTokens);

        PricingCatalog.Cost callCost = pricingCatalog.estimate(
                provider, model,
                Math.max(0, responseInputTokens),
                Math.max(0, responseCachedInputTokens),
                Math.max(0, responseOutputTokens), Instant.now());
        if (!callCost.known()) {
            unknownPricingCount++;
        } else if ("unknown".equalsIgnoreCase(currency)
                || currency.equalsIgnoreCase(callCost.currency())) {
            currency = callCost.currency();
            estimatedCost = estimatedCost.add(callCost.amount());
        } else {
            unknownPricingCount++;
        }
        if (consumedTokens() > policy.maxTotalTokens() || isTimeOrCostExhausted()) {
            markHardStop("resource_limit");
        }
    }

    public synchronized void releaseReservation(RunBudget.Admission admission) {
        if (admission == null || !admission.allowed()) return;
        settle(admission);
    }

    public synchronized boolean tryRecordToolCalls(long count) {
        long normalized = Math.max(0, count);
        if (normalized == 0) return !isHardStopped();
        if (isHardStopped() || toolCalls + normalized > policy.maxToolCalls()) {
            markHardStop("tool_call_limit");
            return false;
        }
        toolCalls += normalized;
        return true;
    }

    public synchronized RunBudget.Snapshot snapshot() {
        return new RunBudget.Snapshot(
                inputTokens, outputTokens, cachedInputTokens,
                llmCalls, toolCalls, reservedTokens,
                estimatedCost, currency, unknownPricingCount,
                elapsedMillis(), decision(), exitReason);
    }

    private boolean settle(RunBudget.Admission admission) {
        Long reserved = reservations.remove(admission.reservationId());
        if (reserved == null) return false;
        reservedTokens = Math.max(0, reservedTokens - reserved);
        return true;
    }

    private long consumedTokens() {
        return inputTokens + outputTokens;
    }

    private long elapsedMillis() {
        return Math.max(0, System.currentTimeMillis() - startedAtMillis);
    }

    private RunBudget.Decision decision() {
        if (isHardStopped()
                || consumedTokens() + reservedTokens >= policy.maxTotalTokens()
                || llmCalls >= policy.maxLlmCalls()
                || toolCalls >= policy.maxToolCalls()) {
            return RunBudget.Decision.HARD_STOP;
        }
        double usage = Math.max(
                ratio(consumedTokens() + reservedTokens, policy.maxTotalTokens()),
                Math.max(ratio(llmCalls, policy.maxLlmCalls()),
                        ratio(toolCalls, policy.maxToolCalls())));
        if (usage >= policy.softStopRatio()) return RunBudget.Decision.SOFT_STOP;
        if (usage >= policy.warningRatio()) return RunBudget.Decision.WARN;
        return RunBudget.Decision.CONTINUE;
    }

    private boolean isHardStopped() {
        return !exitReason.isBlank() || isTimeOrCostExhausted();
    }

    private boolean isTimeOrCostExhausted() {
        return elapsedMillis() >= policy.maxWallClockMillis()
                || (policy.maxEstimatedCost() != null
                && unknownPricingCount == 0
                && estimatedCost.compareTo(policy.maxEstimatedCost()) >= 0);
    }

    private void markHardStop(String reason) {
        if (exitReason.isBlank()) exitReason = reason == null ? "resource_limit" : reason;
    }

    private static double ratio(long used, long limit) {
        return limit <= 0 ? 1.0 : Math.min(1.0, (double) used / limit);
    }
}
