package com.devcli.llm;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一维护 Provider / model 的能力元数据，避免上下文策略、路由和界面各自维护一份常量。
 */
public final class ModelCapabilityRegistry {
    private static final List<Capabilities> BUILT_INS = List.of(
            new Capabilities("anthropic", "*", 200_000, 8_192,
                    true, "anthropic-messages", true, true, true),
            new Capabilities("openai", "*", 128_000, 8_192,
                    true, "openai-automatic-prefix-cache", true, true, true),
            new Capabilities("glm", "*", 200_000, 8_192,
                    true, "glm-prompt-cache", true, true, true),
            new Capabilities("deepseek", "*", 1_000_000, 8_192,
                    true, "automatic-prefix-cache", true, false, true),
            new Capabilities("step", "*", 256_000, 8_192,
                    true, "step-prefix-cache", true, false, true),
            new Capabilities("kimi", "*", 256_000, 8_192,
                    true, "moonshot-context-cache", true, false, true)
    );

    private static final List<Capabilities> CUSTOM = new CopyOnWriteArrayList<>();

    private ModelCapabilityRegistry() {
    }

    public static Capabilities resolve(String provider, String model) {
        String normalizedProvider = normalizeProvider(provider);
        String normalizedModel = normalize(model);
        for (Capabilities candidate : CUSTOM) {
            if (candidate.matches(normalizedProvider, normalizedModel)) {
                return candidate;
            }
        }
        for (Capabilities candidate : BUILT_INS) {
            if (candidate.matches(normalizedProvider, normalizedModel)) {
                return candidate;
            }
        }
        return Capabilities.generic(normalizedProvider, normalizedModel);
    }

    public static void register(Capabilities capabilities) {
        Capabilities requested = Objects.requireNonNull(capabilities, "capabilities");
        CUSTOM.removeIf(existing -> existing.provider().equals(requested.provider())
                && existing.modelPattern().equals(requested.modelPattern()));
        CUSTOM.add(0, requested);
    }

    public static void clearCustom() {
        CUSTOM.clear();
    }

    public static String normalizeProvider(String provider) {
        String normalized = normalize(provider);
        return switch (normalized) {
            case "stepfun", "step-fun" -> "step";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "gpt", "openai-compatible", "oai" -> "openai";
            case "claude", "anthropic-messages" -> "anthropic";
            default -> normalized;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Capabilities(
            String provider,
            String modelPattern,
            int contextWindow,
            int maxOutputTokens,
            boolean promptCaching,
            String promptCacheMode,
            boolean toolCalls,
            boolean vision,
            boolean reasoning) {
        public Capabilities {
            provider = normalizeProvider(provider);
            modelPattern = normalize(modelPattern);
            contextWindow = Math.max(8_000, contextWindow);
            maxOutputTokens = Math.max(1, maxOutputTokens);
            promptCacheMode = promptCacheMode == null || promptCacheMode.isBlank()
                    ? "none" : promptCacheMode.trim();
        }

        public boolean matches(String actualProvider, String actualModel) {
            if (!provider.isBlank() && !"*".equals(provider) && !provider.equals(actualProvider)) {
                return false;
            }
            return matchesModel(modelPattern, actualModel);
        }

        public static Capabilities generic(String provider, String model) {
            return new Capabilities(provider, model.isBlank() ? "*" : model,
                    128_000, 8_192, false, "none", true, false, false);
        }

        private static boolean matchesModel(String pattern, String actual) {
            if (pattern == null || pattern.isBlank() || "*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith("*")) {
                return actual.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return pattern.equals(actual);
        }
    }
}
