package com.devcli.tool;

import com.devcli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具执行的结构化结果。
 */
public record ToolOutput(
        ToolStatus status,
        ToolErrorCode errorCode,
        boolean retryable,
        String text,
        List<LlmClient.ContentPart> imageParts,
        List<String> modifiedResources,
        List<ToolSideChannel> sideChannels
) {
    public ToolOutput {
        status = status == null ? ToolStatus.SUCCESS : status;
        errorCode = errorCode == null ? ToolErrorCode.NONE : errorCode;
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        modifiedResources = modifiedResources == null ? List.of() : List.copyOf(modifiedResources);
        sideChannels = sideChannels == null ? List.of() : List.copyOf(sideChannels);
        if (status == ToolStatus.SUCCESS) {
            errorCode = ToolErrorCode.NONE;
            retryable = false;
        }
    }

    /** 兼容原有六字段结构化结果构造方式。 */
    public ToolOutput(ToolStatus status, ToolErrorCode errorCode, boolean retryable,
                      String text, List<LlmClient.ContentPart> imageParts,
                      List<String> modifiedResources) {
        this(status, errorCode, retryable, text, imageParts, modifiedResources, List.of());
    }

    /** 兼容原有 MCP 图片结果构造方式。 */
    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(ToolStatus.SUCCESS, ToolErrorCode.NONE, false,
                text, imageParts, List.of(), List.of());
    }

    public static ToolOutput text(String text) {
        return success(text);
    }

    public static ToolOutput success(String text) {
        return new ToolOutput(ToolStatus.SUCCESS, ToolErrorCode.NONE, false,
                text, List.of(), List.of(), List.of());
    }

    public static ToolOutput success(String text, List<LlmClient.ContentPart> imageParts) {
        return new ToolOutput(ToolStatus.SUCCESS, ToolErrorCode.NONE, false,
                text, imageParts, List.of(), List.of());
    }

    public static ToolOutput error(ToolErrorCode errorCode, String text, boolean retryable) {
        return new ToolOutput(ToolStatus.ERROR, errorCode, retryable,
                text, List.of(), List.of(), List.of());
    }

    public static ToolOutput rejected(ToolErrorCode errorCode, String text) {
        return rejected(errorCode, text, false);
    }

    public static ToolOutput rejected(ToolErrorCode errorCode, String text, boolean retryable) {
        return new ToolOutput(ToolStatus.REJECTED, errorCode, retryable,
                text, List.of(), List.of(), List.of());
    }

    public static ToolOutput cancelled(String text) {
        return new ToolOutput(ToolStatus.CANCELLED, ToolErrorCode.CANCELLED, false,
                text, List.of(), List.of(), List.of());
    }

    public static ToolOutput timedOut(String text) {
        return new ToolOutput(ToolStatus.TIMEOUT, ToolErrorCode.TIMEOUT, true,
                text, List.of(), List.of(), List.of());
    }

    public ToolOutput withModifiedResources(List<String> resources) {
        return new ToolOutput(status, errorCode, retryable, text, imageParts, resources, sideChannels);
    }

    public ToolOutput withSideChannel(ToolSideChannel sideChannel) {
        if (sideChannel == null) {
            return this;
        }
        List<ToolSideChannel> updated = new ArrayList<>(sideChannels);
        updated.add(sideChannel);
        return new ToolOutput(status, errorCode, retryable, text, imageParts,
                modifiedResources, updated);
    }

    public boolean isSuccess() {
        return status == ToolStatus.SUCCESS;
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
