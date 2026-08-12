package com.devcli.llm;

import com.devcli.budget.RunBudget;

import java.util.ArrayDeque;
import java.util.Deque;

/** 把一次逻辑 LLM 调用及其 Provider 重试绑定到同一 RunBudget。 */
public final class LlmBudgetContext {
    private static final ThreadLocal<Deque<Scope>> CURRENT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LlmBudgetContext() {
    }

    public static Scope open(RunBudget budget, String phase, String agent,
                             String attemptPrefix, long reservedTokens) {
        Scope scope = new Scope(budget, phase, agent, attemptPrefix, reservedTokens);
        CURRENT.get().push(scope);
        return scope;
    }

    static Scope current() {
        Deque<Scope> scopes = CURRENT.get();
        return scopes.isEmpty() ? null : scopes.peek();
    }

    public static final class Scope implements AutoCloseable {
        private final RunBudget budget;
        private final String phase;
        private final String agent;
        private final String attemptPrefix;
        private final long reservedTokens;
        private final RunBudget.Admission firstAdmission;
        private final Deque<RunBudget.Admission> unsettled = new ArrayDeque<>();
        private boolean closed;

        private Scope(RunBudget budget, String phase, String agent,
                      String attemptPrefix, long reservedTokens) {
            this.budget = budget;
            this.phase = text(phase, "llm");
            this.agent = text(agent, "llm");
            this.attemptPrefix = text(attemptPrefix, "attempt");
            this.reservedTokens = Math.max(0, reservedTokens);
            this.firstAdmission = budget == null
                    ? null
                    : budget.tryStartLlmRequest(this.phase, this.agent,
                    this.attemptPrefix + "-1", this.reservedTokens);
            if (firstAdmission != null && firstAdmission.allowed()) unsettled.add(firstAdmission);
        }

        public boolean allowed() {
            return firstAdmission == null || firstAdmission.allowed();
        }

        public String denialReason() {
            return firstAdmission == null ? "" : firstAdmission.reason();
        }

        RunBudget.Admission admissionForAttempt(int attempt) {
            if (budget == null) return null;
            if (attempt <= 1) return firstAdmission;
            RunBudget.Admission admission = budget.tryStartLlmRequest(
                    phase, agent, attemptPrefix + "-" + attempt, reservedTokens);
            if (admission.allowed()) unsettled.add(admission);
            return admission;
        }

        void failed(RunBudget.Admission admission) {
            settleWithoutUsage(admission);
        }

        public void recordUsage(String provider, String model,
                                long inputTokens, long outputTokens, long cachedInputTokens) {
            if (budget == null) return;
            RunBudget.Admission admission = unsettled.peekLast();
            if (admission == null) admission = firstAdmission;
            budget.recordLlmUsage(admission, provider, model,
                    inputTokens, outputTokens, cachedInputTokens);
            unsettled.remove(admission);
        }

        public RunBudget.Admission firstAdmission() {
            return firstAdmission;
        }

        private void settleWithoutUsage(RunBudget.Admission admission) {
            if (budget == null || admission == null) return;
            budget.releaseReservation(admission);
            unsettled.remove(admission);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            while (!unsettled.isEmpty()) {
                settleWithoutUsage(unsettled.peekFirst());
            }
            Deque<Scope> scopes = CURRENT.get();
            if (!scopes.isEmpty() && scopes.peek() == this) scopes.pop();
            else scopes.remove(this);
            if (scopes.isEmpty()) CURRENT.remove();
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
