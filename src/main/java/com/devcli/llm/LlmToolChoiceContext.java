package com.devcli.llm;

import java.io.IOException;

/** 同步 LLM 请求范围内的工具选择策略，保持既有 chat 重写点兼容。 */
final class LlmToolChoiceContext {
    private static final ThreadLocal<LlmClient.ToolChoice> CURRENT =
            ThreadLocal.withInitial(() -> LlmClient.ToolChoice.AUTO);

    private LlmToolChoiceContext() {
    }

    static LlmClient.ToolChoice current() {
        LlmClient.ToolChoice choice = CURRENT.get();
        return choice == null ? LlmClient.ToolChoice.AUTO : choice;
    }

    static <T> T call(LlmClient.ToolChoice choice, IoSupplier<T> action) throws IOException {
        LlmClient.ToolChoice previous = current();
        CURRENT.set(choice == null ? LlmClient.ToolChoice.AUTO : choice);
        try {
            return action.get();
        } finally {
            CURRENT.set(previous);
        }
    }

    @FunctionalInterface
    interface IoSupplier<T> {
        T get() throws IOException;
    }
}
