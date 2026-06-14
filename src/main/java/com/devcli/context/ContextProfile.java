package com.devcli.context;

import com.devcli.llm.LlmClient;

/**
 * 上下文策略配置。
 *
 * **设计原则**：没有"长 / 短 / 平衡"模式分档。所有参数都是 maxContextWindow 的简单函数，
 * 全模型走同一套行为，只是 window 大小不同导致触发时机和容量不同。
 *
 * 全局常量：
 * - 压缩触发阈值：取 占用率 ≥ 90% 与 "预留输出空间"（window − reserve）两者更早触发的那个，
 *   保证压缩后剩余窗口能装下模型一次输出
 *
 * 按 window 派生：
 * - 短期记忆预算 = window × 0.45
 * - 注入到 system prompt 的相关记忆 token 上限 = window × 0.005，封顶 5000
 * - MCP resource 索引注入：window ≥ 32k 才有意义（再小就挤）
 */
public record ContextProfile(
        int maxContextWindow,
        int agentTokenBudget,
        double compressionTriggerRatio,
        int shortTermMemoryBudget,
        int memoryContextTokens,
        boolean mcpResourceIndexEnabled,
        boolean promptCachingSupported,
        String promptCacheMode,
        int outputReserveTokens
) {
    public static final double DEFAULT_COMPRESSION_TRIGGER_RATIO = 0.90;
    private static final int MIN_WINDOW = 8_000;
    private static final int MCP_RESOURCE_INDEX_MIN_WINDOW = 32_000;
    /** from(null) / custom() 无法得知模型输出能力时的默认输出上限，对齐请求默认 max_tokens */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    /** 压缩后至少预留给"模型输出 + 估算误差 + 突发"的 token 经验下限 */
    private static final int MIN_OUTPUT_RESERVE = 20_000;

    public static ContextProfile from(LlmClient llmClient) {
        int window = Math.max(MIN_WINDOW, llmClient == null ? 128_000 : llmClient.maxContextWindow());
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTermBudget(window),
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                llmClient != null && llmClient.supportsPromptCaching(),
                llmClient == null ? "none" : llmClient.promptCacheMode(),
                llmClient == null ? DEFAULT_MAX_OUTPUT_TOKENS : Math.max(1, llmClient.maxOutputTokens())
        );
    }

    public static ContextProfile custom(int contextWindow, int shortTermMemoryBudget) {
        int window = Math.max(MIN_WINDOW, contextWindow);
        int shortTerm = Math.max(1, shortTermMemoryBudget);
        return new ContextProfile(
                window,
                agentBudget(window),
                DEFAULT_COMPRESSION_TRIGGER_RATIO,
                shortTerm,
                memoryContextTokens(window),
                window >= MCP_RESOURCE_INDEX_MIN_WINDOW,
                false,
                "none",
                DEFAULT_MAX_OUTPUT_TOKENS
        );
    }

    /**
     * 触发压缩的绝对 token 阈值（占用 ≥ 此值即压缩）。
     *
     * 取两者更早触发的那个：
     * - 比例触发：window × ratio（大 window 主导，留 10% 已足够装下输出）
     * - 预留触发：window − reserve，保证压缩后剩余空间能装下模型一次输出，
     *   避免 90% 触发后剩余窗口装不下回复而撞窗口（中小 window 主导）
     * reserve = max(模型输出上限, MIN_OUTPUT_RESERVE)，且最多占 window 的一半，
     * 防止极小 window 把阈值压成 0 或负数。
     */
    public int compressionTriggerTokens() {
        int ratioTrigger = (int) Math.floor(maxContextWindow * compressionTriggerRatio);
        int reserve = Math.min(Math.max(outputReserveTokens, MIN_OUTPUT_RESERVE), maxContextWindow / 2);
        int reserveTrigger = maxContextWindow - reserve;
        return Math.min(ratioTrigger, reserveTrigger);
    }

    public String summary() {
        return "window: " + maxContextWindow
                + " | 压缩阈值: " + compressionTriggerTokens() + " tokens (≤" + (int) (compressionTriggerRatio * 100)
                + "% 或预留 " + outputReserveTokens + " 输出)"
                + " | 短期记忆预算: " + shortTermMemoryBudget
                + " | MCP resource 索引: " + (mcpResourceIndexEnabled ? "on" : "off")
                + " | prompt cache: " + promptCacheMode;
    }

    private static int agentBudget(int window) {
        // Agent 单次 run 的 token 上限（input + output 累计），保 20% 余量给响应突发
        return Math.max(4_000, (int) Math.floor(window * 0.8));
    }

    private static int shortTermBudget(int window) {
        return Math.max(4_000, (int) Math.floor(window * 0.45));
    }

    private static int memoryContextTokens(int window) {
        return Math.max(500, Math.min(5_000, window / 200));
    }
}
