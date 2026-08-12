package com.devcli.context;

import com.devcli.budget.PricingCatalog;
import com.devcli.llm.LlmClient;

import java.time.Instant;
import com.devcli.util.AnsiStyle;

import java.util.Locale;

public final class TokenUsageFormatter {
    private TokenUsageFormatter() {
    }

    public static String format(LlmClient llmClient, int inputTokens, int outputTokens,
                                int cachedInputTokens, long startNanos) {
        ContextProfile profile = ContextProfile.from(llmClient);
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        int total = Math.max(0, inputTokens) + Math.max(0, outputTokens);
        String cost = estimatedCost(llmClient, inputTokens, outputTokens, cachedInputTokens);
        // 此处展示单次请求的上下文窗口；Run 累计预算由状态投影单独展示。
        return AnsiStyle.subtle(String.format(Locale.ROOT,
                "📊 Token: 已用 %d / %d (cached: %d, 估算 %s) | 输入 %d / 输出 %d | ⏱ %.1fs",
                total,
                profile.maxContextWindow(),
                Math.max(0, cachedInputTokens),
                cost,
                Math.max(0, inputTokens),
                Math.max(0, outputTokens),
                elapsedSeconds));
    }

    public static String estimatedCost(LlmClient llmClient, int inputTokens,
                                       int outputTokens, int cachedInputTokens) {
        PricingCatalog.Cost cost = PricingCatalog.empty().estimate(
                llmClient == null ? "" : llmClient.getProviderName(),
                llmClient == null ? "" : llmClient.getModelName(),
                inputTokens, cachedInputTokens, outputTokens, Instant.now());
        return cost.display();
    }

    /** @deprecated 使用明确价格目录；未知模型必须返回 cost=unknown。 */
    @Deprecated(forRemoval = false)
    public static String estimatedCostCny(LlmClient llmClient, int inputTokens,
                                          int outputTokens, int cachedInputTokens) {
        return estimatedCost(llmClient, inputTokens, outputTokens, cachedInputTokens);
    }
}
