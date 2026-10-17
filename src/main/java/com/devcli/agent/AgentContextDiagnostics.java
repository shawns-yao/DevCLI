package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 对当前日志渲染结果做只读诊断，不保存或修改会话事实。 */
final class AgentContextDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(AgentContextDiagnostics.class);
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final List<LlmClient.Message> conversation;

    AgentContextDiagnostics(LlmClient client, ToolRegistry tools, MemoryManager memory, List<LlmClient.Message> messages) {
        llmClient = client;
        toolRegistry = tools;
        memoryManager = memory;
        conversation = messages;
    }

    String status() {
        com.devcli.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversation) {
            int t = com.devcli.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.devcli.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < conversation.size(); messageIndex++) {
            LlmClient.Message msg = conversation.get(messageIndex);
            messages++;
            int tokens = com.devcli.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = com.devcli.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /** 把当前预算/耗时/HITL 状态推送给 renderer 状态栏。 */
    void pushStatus(com.devcli.render.Renderer renderer, AgentBudget budget, long startNanos, String phase, String permissionMode) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : com.devcli.context.TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer.updateStatus(com.devcli.render.StatusInfo.tokens(
                    model,
                    contextWindow,
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    permissionMode,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

}
