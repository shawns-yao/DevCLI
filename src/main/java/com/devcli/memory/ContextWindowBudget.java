package com.devcli.memory;

import com.devcli.llm.LlmClient;

import java.util.List;

/** 单次模型请求的上下文窗口容量，不记录 Run 累计消耗。 */
public class ContextWindowBudget {
    private final int contextWindow;
    private final int reservedForSystem;
    private final int reservedForTools;
    private final int reservedForResponse;

    public ContextWindowBudget(int contextWindow) {
        this(contextWindow, 500, 800, 2_000);
    }

    public ContextWindowBudget(int contextWindow, int reservedForSystem,
                               int reservedForTools, int reservedForResponse) {
        if (contextWindow <= 0) throw new IllegalArgumentException("contextWindow must be positive");
        if (reservedForSystem < 0 || reservedForTools < 0 || reservedForResponse < 0) {
            throw new IllegalArgumentException("context reserves must be non-negative");
        }
        this.contextWindow = contextWindow;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
        this.reservedForResponse = reservedForResponse;
    }

    public int availableConversationTokens() {
        return Math.max(0, contextWindow - reservedForSystem - reservedForTools - reservedForResponse);
    }

    public boolean fits(List<LlmClient.Message> messages) {
        return estimateMessagesTokens(messages) <= availableConversationTokens();
    }

    public int contextWindow() { return contextWindow; }

    public static int estimateMessagesTokens(List<LlmClient.Message> messages) {
        return TokenBudget.estimateMessagesTokens(messages);
    }

    public static int estimateToolDefinitionsTokens(List<LlmClient.Tool> tools) {
        return TokenBudget.estimateToolDefinitionsTokens(tools);
    }
}
