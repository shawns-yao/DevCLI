package com.devcli.runtime;

import com.devcli.llm.LlmErrorCode;

import java.util.Objects;

/** 统一重试决策，只允许满足幂等和对账前提的动作自动重试。 */
public final class RetryPolicy {
    public enum Effect {
        READ_ONLY,
        LOCAL_CONTEXT,
        PROJECT_MUTATION,
        EXTERNAL_MUTATION
    }

    public record Decision(boolean retry, String reason) {
        public Decision {
            reason = reason == null ? "" : reason;
        }

        public static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision llm(LlmErrorCode code, boolean responseStarted,
                        int failedAttempt, int maxAttempts) {
        if (responseStarted) return Decision.deny("stream_already_started");
        if (failedAttempt >= Math.max(1, maxAttempts)) return Decision.deny("attempt_limit");
        LlmErrorCode normalized = code == null ? LlmErrorCode.UNKNOWN : code;
        return switch (normalized) {
            case RATE_LIMITED, OVERLOADED, TIMEOUT, NETWORK, SERVER_ERROR ->
                    Decision.allow(normalized.name().toLowerCase());
            default -> Decision.deny("non_retryable_" + normalized.name().toLowerCase());
        };
    }

    public Decision tool(Effect effect, boolean declaredIdempotent,
                         boolean idempotencyKeyPresent, boolean reconciled) {
        Effect normalized = Objects.requireNonNullElse(effect, Effect.EXTERNAL_MUTATION);
        if (normalized == Effect.READ_ONLY || normalized == Effect.LOCAL_CONTEXT) {
            return declaredIdempotent
                    ? Decision.allow("idempotent_read")
                    : Decision.deny("tool_not_declared_idempotent");
        }
        if (!declaredIdempotent || !idempotencyKeyPresent || !reconciled) {
            return Decision.deny("side_effect_retry_requires_idempotency_and_reconciliation");
        }
        return Decision.allow("reconciled_idempotent_side_effect");
    }

    public Decision recovery(boolean patchJournalReconciled,
                             boolean checkpointValid, boolean budgetRestored) {
        if (!patchJournalReconciled) return Decision.deny("patch_journal_not_reconciled");
        if (!checkpointValid) return Decision.deny("checkpoint_invalid");
        if (!budgetRestored) return Decision.deny("budget_not_restored");
        return Decision.allow("safe_recovery_point");
    }
}
