package com.devcli.runtime.api;

/** Runtime API 持久化压缩检查点配置。 */
public final class RuntimeCheckpointPolicy {
    public static final int DEFAULT_TRIGGER_TOKENS = 32_000;
    private static final int MIN_TRIGGER_TOKENS = 4_000;

    private RuntimeCheckpointPolicy() {
    }

    public static int configuredTriggerTokens() {
        String configured = System.getProperty("devcli.runtime.checkpoint.trigger.tokens");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_RUNTIME_CHECKPOINT_TRIGGER_TOKENS");
        }
        if (configured == null || configured.isBlank()) return DEFAULT_TRIGGER_TOKENS;
        try {
            return Math.max(MIN_TRIGGER_TOKENS, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_TRIGGER_TOKENS;
        }
    }
}
