package com.devcli.llm;

import com.devcli.config.DevCliConfig;
import com.devcli.config.ConfigResolver;

public class LlmClientFactory {

    private LlmClientFactory() {}

    public static LlmClient create(String provider, DevCliConfig config) {
        return create(provider, config, null);
    }

    private static LlmClient create(String provider, DevCliConfig config, String modelOverride) {
        if (provider == null) return null;

        String normalized = ModelCapabilityRegistry.normalizeProvider(provider);
        String configuredProvider = provider.trim().toLowerCase();
        String apiKey = config.getApiKey(normalized);
        if ((apiKey == null || apiKey.isBlank()) && !configuredProvider.equals(normalized)) {
            apiKey = config.getApiKey(configuredProvider);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        String model = firstConfigured(modelOverride, firstConfigured(config.getModel(normalized),
                configuredProvider.equals(normalized) ? null : config.getModel(configuredProvider)));
        String baseUrl = firstConfigured(config.getBaseUrl(normalized),
                configuredProvider.equals(normalized) ? null : config.getBaseUrl(configuredProvider));

        return switch (normalized) {
            case "anthropic" -> new AnthropicClient(
                    apiKey, model, baseUrl, config.getMaxTokens(normalized));
            case "glm" -> new GLMClient(apiKey, model);
            case "deepseek" -> new DeepSeekClient(apiKey, model, baseUrl);
            case "step" -> new StepClient(apiKey, model, baseUrl);
            case "kimi" -> new KimiClient(apiKey, model, baseUrl);
            case "openai" -> new OpenAiClient(apiKey, model, baseUrl);
            default -> null;
        };
    }

    public static LlmClient createFromConfig(DevCliConfig config) {
        LlmClient client = create(config.getDefaultProvider(), config);
        if (client != null) {
            return client;
        }

        for (String provider : new String[]{"anthropic", "openai", "glm", "deepseek", "step", "kimi"}) {
            client = create(provider, config);
            if (client != null) {
                return client;
            }
        }

        return null;
    }

    /**
     * Team Reviewer 的可选独立模型路由。未配置时保持兼容并复用主模型；
     * 一旦显式配置，缺少凭据或 provider 无效时失败关闭，避免静默退化为自我评审。
     */
    public static LlmClient createTeamReviewer(DevCliConfig config, LlmClient primary) {
        String provider = ConfigResolver.optional(
                "devcli.team.reviewer.provider", "DEVCLI_TEAM_REVIEWER_PROVIDER");
        String model = ConfigResolver.optional(
                "devcli.team.reviewer.model", "DEVCLI_TEAM_REVIEWER_MODEL");
        if (provider == null && model == null) {
            return primary;
        }
        String effectiveProvider = provider == null ? primary.getProviderName() : provider;
        LlmClient reviewer = create(effectiveProvider, config, model);
        if (reviewer == null) {
            throw new IllegalArgumentException(
                    "独立 Reviewer 配置无效或缺少凭据: " + effectiveProvider);
        }
        return reviewer;
    }

    private static String firstConfigured(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    /** 未配置的角色直接复用本轮主模型；仅显式配置时读取 provider 配置。 */
    public static LlmClient createDelegatedAgent(LlmClient primary, String role) {
        if (delegationSetting(role, "provider") == null && delegationSetting(role, "model") == null) {
            return primary;
        }
        return createDelegatedAgent(DevCliConfig.load(), primary, role);
    }

    public static LlmClient createDelegatedAgent(DevCliConfig config, LlmClient primary, String role) {
        String provider = delegationSetting(role, "provider");
        String model = delegationSetting(role, "model");
        if (provider == null && model == null) return primary;
        String effectiveProvider = provider == null ? primary.getProviderName() : provider;
        LlmClient client = create(effectiveProvider, config, model);
        if (client == null) {
            throw new IllegalArgumentException("子 Agent 模型配置无效或缺少凭据: " + role + "/" + effectiveProvider);
        }
        return client;
    }

    private static String delegationSetting(String role, String field) {
        if (!java.util.Set.of("explorer", "planner", "worker", "reviewer").contains(role)) {
            throw new IllegalArgumentException("未知子 Agent 角色: " + role);
        }
        return ConfigResolver.optional("devcli.delegate." + role + "." + field,
                "DEVCLI_DELEGATE_" + role.toUpperCase(java.util.Locale.ROOT) + "_" + field.toUpperCase(java.util.Locale.ROOT));
    }
}
