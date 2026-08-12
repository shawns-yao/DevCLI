package com.devcli.budget;

import com.devcli.runtime.event.RunEvent;

import java.math.BigDecimal;
import java.util.Objects;

/** 一次 Run 的共享预算门面。 */
public final class RunBudget {
    public enum Decision {
        CONTINUE,
        WARN,
        SOFT_STOP,
        HARD_STOP
    }

    private final String runId;
    private final RunBudgetPolicy policy;
    private final BudgetLedger ledger;

    private RunBudget(String runId, RunBudgetPolicy policy, PricingCatalog pricingCatalog) {
        this.runId = requireText(runId, "runId");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.ledger = new BudgetLedger(policy, pricingCatalog);
    }

    public static RunBudget create(String runId, RunBudgetPolicy policy, PricingCatalog pricingCatalog) {
        return new RunBudget(runId, policy, pricingCatalog);
    }

    public Admission tryStartLlmRequest(String phase, String agent, String attempt) {
        return tryStartLlmRequest(phase, agent, attempt, 0);
    }

    public Admission tryStartLlmRequest(String phase, String agent, String attempt,
                                        long reservedTokens) {
        return ledger.tryStartLlmRequest(phase, agent, attempt, reservedTokens);
    }

    public void recordLlmUsage(Admission admission, String provider, String model,
                               long inputTokens, long outputTokens, long cachedInputTokens) {
        ledger.recordLlmUsage(admission, provider, model, inputTokens, outputTokens, cachedInputTokens);
    }

    public boolean tryRecordToolCalls(long count) {
        return ledger.tryRecordToolCalls(count);
    }

    public void releaseReservation(Admission admission) {
        ledger.releaseReservation(admission);
    }

    public Snapshot snapshot() {
        return ledger.snapshot();
    }

    public String runId() { return runId; }
    public RunBudgetPolicy policy() { return policy; }

    public RunEvent.BudgetUsageUpdated usageEvent(String phase, String agent, String attempt) {
        Snapshot value = snapshot();
        return new RunEvent.BudgetUsageUpdated(
                runId, phase, agent, attempt,
                value.inputTokens(), value.outputTokens(), value.cachedInputTokens(),
                value.llmCalls(), value.toolCalls(), value.costDisplay(), value.currency(),
                value.decision().name());
    }

    public record Admission(boolean allowed, String phase, String agent, String attempt,
                            long requestNumber, String reservationId,
                            long reservedTokens, String reason) {
        public Admission {
            phase = text(phase);
            agent = text(agent);
            attempt = text(attempt);
            reservationId = text(reservationId);
            reason = text(reason);
        }

        static Admission allowed(String phase, String agent, String attempt, long requestNumber,
                                 String reservationId, long reservedTokens) {
            return new Admission(true, phase, agent, attempt, requestNumber,
                    reservationId, Math.max(0, reservedTokens), "");
        }

        static Admission denied(String phase, String agent, String attempt, String reason) {
            return new Admission(false, phase, agent, attempt, 0, "", 0, reason);
        }
    }

    public record Snapshot(long inputTokens, long outputTokens, long cachedInputTokens,
                           long llmCalls, long toolCalls, long reservedTokens,
                           BigDecimal estimatedCost,
                           String currency, long unknownPricingCount, long elapsedMillis,
                           Decision decision, String exitReason) {
        public long totalTokens() { return inputTokens + outputTokens; }
        public String costDisplay() {
            return unknownPricingCount > 0 || "unknown".equalsIgnoreCase(currency)
                    ? "unknown" : estimatedCost.toPlainString();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
