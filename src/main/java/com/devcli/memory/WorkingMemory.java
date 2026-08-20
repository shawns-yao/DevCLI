package com.devcli.memory;

/**
 * @deprecated 兼容旧扩展的迁移适配器。新的短期记忆统一使用 {@link SessionMemory}。
 */
@Deprecated
public class WorkingMemory extends SessionMemory {
    public enum View { FULL, PLANNER, WORKER, REVIEWER }

    public WorkingMemory() { super(); }
    public WorkingMemory(int maxToolResults, int maxVolatileFacts) {
        super(maxToolResults, maxVolatileFacts);
    }
    public WorkingMemory(int maxToolResults, int maxVolatileFacts, int maxRagEvidence) {
        super(maxToolResults, maxVolatileFacts, maxRagEvidence);
    }

    public String renderForPrompt(View view) {
        return super.renderForPrompt(toSessionView(view));
    }

    public String renderForPostCompactRestore(View view) {
        return super.renderForPostCompactRestore(toSessionView(view));
    }

    private static SessionView toSessionView(View view) {
        if (view == null) return SessionView.FULL;
        return SessionView.valueOf(view.name());
    }
}
