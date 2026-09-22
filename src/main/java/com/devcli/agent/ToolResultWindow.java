package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryEntry;
import com.devcli.memory.TokenBudget;
import com.devcli.tool.ToolRegistry.ToolExecutionResult;
import com.devcli.tool.ToolResultArtifact;
import com.devcli.tool.ToolResultArtifactStore;
import com.devcli.tool.ToolSideChannel;

import java.util.ArrayList;
import java.util.List;

/** Final, stable-order admission before results enter history, events and memory. */
final class ToolResultWindow {
    static final int MAX_BATCH_CHARS = 100_000;
    static final int MAX_BATCH_TOKENS = 25_000;
    private static final int MAX_IMAGES = 4;
    private static final int MAX_IMAGE_BASE64_CHARS = 2_800_000;

    static List<ToolExecutionResult> fit(List<ToolExecutionResult> results, int tokenBudget) {
        int tokens = Math.min(MAX_BATCH_TOKENS, Math.max(0, tokenBudget));
        if (results.size() > tokens / 128 || results.size() > MAX_BATCH_CHARS / 512) {
            throw new IllegalStateException("工具结果最小协议超出剩余上下文预算");
        }
        int chars = MAX_BATCH_CHARS;
        int imageCount = 0;
        List<ToolExecutionResult> bounded = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            ToolExecutionResult result = results.get(index);
            int remaining = results.size() - index;
            int tokenShare = tokens / remaining;
            int charShare = chars / remaining;
            List<ToolSideChannel> channels = new ArrayList<>(result.sideChannels());
            String text = ToolCallGovernance.modelResult(result);
            if (text == null) text = "";
            String suffix = "";
            if (!result.imageParts().isEmpty()) suffix = "\n[图片可能因数量、尺寸或上下文预算限制而省略；请缩小后重试]";
            int textBudget = Math.max(0, tokenShare - 32);
            if (text.length() + suffix.length() > charShare
                    || MemoryEntry.estimateTokens(text + suffix) > textBudget) {
                ToolResultArtifact artifact = channels.stream().filter(ToolResultArtifact.class::isInstance)
                        .map(ToolResultArtifact.class::cast).findFirst().orElse(null);
                if (artifact == null && !text.isBlank()) {
                    try {
                        var stored = ToolResultArtifactStore.store(result.id(), text);
                        artifact = new ToolResultArtifact("PERSISTED_PREVIEW", stored.chars(),
                                stored.bytes(), 0, stored.ref(), "0", stored.sha256());
                        channels.add(artifact);
                    } catch (Exception ignored) {
                        // The bounded fallback must not claim a recoverable original.
                    }
                }
                String reference = artifact == null ? "[原文不可恢复]"
                        : "[result_ref=" + artifact.artifactRef() + ", offset=0]";
                String header = "[" + result.status() + "/" + result.errorCode() + "；预览不完整]"
                        + reference + suffix + "\n";
                text = prefix(header + text, charShare, textBudget);
            } else {
                text += suffix;
            }
            List<LlmClient.ContentPart> images = new ArrayList<>();
            int used = MemoryEntry.estimateTokens(text) + 8;
            for (var image : result.imageParts()) {
                if (imageCount >= MAX_IMAGES || !acceptableImage(image)) continue;
                // Reserve the existing estimator's maximum plus image-message framing.
                int cost = 4_128;
                if (used + cost > tokenShare) continue;
                images.add(image);
                imageCount++;
                used += cost;
            }
            if (used > tokenShare || text.length() > charShare) {
                throw new IllegalStateException("工具结果最小协议超出剩余上下文预算");
            }
            tokens -= used;
            chars -= text.length();
            bounded.add(new ToolExecutionResult(result.id(), result.name(), result.argumentsJson(),
                    text, result.elapsedMillis(), result.status(), result.errorCode(), result.retryable(),
                    images, channels, result.presentation()));
        }
        return List.copyOf(bounded);
    }

    static int available(LlmClient client, List<LlmClient.Message> history, List<LlmClient.Tool> tools) {
        long remaining = (long) client.maxContextWindow() - client.maxOutputTokens() - 1_024
                - TokenBudget.estimateMessagesTokens(history) - TokenBudget.estimateToolDefinitionsTokens(tools);
        return (int) Math.max(0, Math.min(MAX_BATCH_TOKENS, remaining));
    }

    private static String prefix(String text, int chars, int tokens) {
        int low = 0;
        int high = Math.min(text.length(), Math.max(0, chars));
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (MemoryEntry.estimateTokens(text.substring(0, middle)) <= tokens) low = middle;
            else high = middle - 1;
        }
        if (low > 0 && low < text.length() && Character.isHighSurrogate(text.charAt(low - 1))) low--;
        return text.substring(0, low);
    }

    private static boolean acceptableImage(LlmClient.ContentPart image) {
        if (image == null || !image.isImage()) return false;
        // Remote image dimensions cannot be verified without fetching untrusted URLs.
        if (image.imageBase64() == null || image.imageBase64().length() > MAX_IMAGE_BASE64_CHARS) return false;
        try (var stream = javax.imageio.ImageIO.createImageInputStream(
                new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(image.imageBase64())))) {
            if (stream == null) return false;
            var readers = javax.imageio.ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;
            var reader = readers.next();
            try {
                reader.setInput(stream);
                return reader.getWidth(0) <= 4096 && reader.getHeight(0) <= 4096;
            } finally {
                reader.dispose();
            }
        } catch (Exception invalid) {
            return false;
        }
    }
}
