package com.devcli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DevCliConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".devcli");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private String defaultProvider = "anthropic";
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();
    private PermissionsConfig permissions = new PermissionsConfig();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private double temperature = 0.7;  // 默认温度
        private int maxTokens = 8192;      // 默认最大 token 数

        public ProviderConfig() {}

        public ProviderConfig(String apiKey, String baseUrl, String model) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    /**
     * 权限配置段。承载持久授权基线与用户规则层。
     *
     * <p>规则分四类，与参照实现的四个用户规则类别一一对应：{@code hardDeny}（无条件拒绝）、
     * {@code softDeny}（用户声明的边界，明确意图可清除）、{@code allow}（允许例外）、
     * {@code environment}（环境事实，只作上下文不产生判定）。配置键使用
     * {@code hard_deny} / {@code soft_deny} / {@code allow} / {@code environment}，不保留旧别名。</p>
     *
     * <p>这里刻意只保存原始形态（字符串清单与布尔），不引用 {@code com.devcli.policy} 下的类型：
     * {@code config} 是叶子包，不得依赖任何其他 {@code com.devcli} 顶层包（见 PackageBoundaryTest）。
     * 原始值到 {@code TaskGrant} / {@code PermissionRuleSet} 的装配由入口层完成，
     * 非法值在那里显式拒绝。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class PermissionsConfig {
        private String defaultMode = "default";
        private List<String> hardDeny = new ArrayList<>();
        private List<String> softDeny = new ArrayList<>();
        private List<String> allow = new ArrayList<>();
        private List<String> environment = new ArrayList<>();

        /**
         * 启动时的权限模式，取值见 {@code policy.PermissionMode}。
         *
         * <p>默认 {@code default}（危险操作逐个询问）。写成 {@code bypassPermissions} 可回到
         * 引入模式层之前的行为：不询问、直接执行。这里不做校验，非法值由入口层显式拒绝并回落——
         * 与规则的处理方式一致。</p>
         */
        public String getDefaultMode() {
            return defaultMode == null || defaultMode.isBlank() ? "default" : defaultMode;
        }

        public void setDefaultMode(String defaultMode) {
            this.defaultMode = defaultMode;
        }

        /** 安全边界规则：无条件拒绝，用户意图不能清除，允许例外也不能覆盖。 */
        @JsonProperty("hard_deny")
        public List<String> getHardDeny() {
            return hardDeny == null ? List.of() : hardDeny;
        }

        @JsonProperty("hard_deny")
        public void setHardDeny(List<String> hardDeny) {
            this.hardDeny = hardDeny == null ? new ArrayList<>() : new ArrayList<>(hardDeny);
        }

        /** 用户声明的边界规则：明确且具体的用户意图可以清除它。 */
        @JsonProperty("soft_deny")
        public List<String> getSoftDeny() {
            return softDeny == null ? List.of() : softDeny;
        }

        @JsonProperty("soft_deny")
        public void setSoftDeny(List<String> softDeny) {
            this.softDeny = softDeny == null ? new ArrayList<>() : new ArrayList<>(softDeny);
        }

        /** 允许例外规则：只在软阻止未命中时生效。 */
        public List<String> getAllow() {
            return allow == null ? List.of() : allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow == null ? new ArrayList<>() : new ArrayList<>(allow);
        }

        /** 环境事实：可信域名、共享资源、部署目标等，只作为分类器上下文，不产生判定。 */
        public List<String> getEnvironment() {
            return environment == null ? List.of() : environment;
        }

        public void setEnvironment(List<String> environment) {
            this.environment = environment == null ? new ArrayList<>() : new ArrayList<>(environment);
        }

    }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }
    public PermissionsConfig getPermissions() { return permissions; }
    public void setPermissions(PermissionsConfig permissions) {
        this.permissions = permissions == null ? new PermissionsConfig() : permissions;
    }

    public String getApiKey(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getApiKey() != null && !providerConfig.getApiKey().isBlank()) {
            return providerConfig.getApiKey();
        }
        return loadApiKeyFromEnv(provider);
    }

    public String getModel(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getModel() != null && !providerConfig.getModel().isBlank()) {
            return providerConfig.getModel();
        }
        return loadModelFromEnv(provider);
    }

    public String getBaseUrl(String provider) {
        ProviderConfig providerConfig = providers.get(provider);
        if (providerConfig != null && providerConfig.getBaseUrl() != null && !providerConfig.getBaseUrl().isBlank()) {
            return providerConfig.getBaseUrl();
        }
        return loadBaseUrlFromEnv(provider);
    }

    public int getMaxTokens(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        Integer override = positiveInt(System.getProperty("devcli.llm.max.output.tokens"));
        if (override == null && !normalized.isBlank()) {
            override = positiveInt(System.getProperty("devcli." + normalized + ".max.tokens"));
        }
        if (override == null && !normalized.isBlank()) {
            override = positiveInt(getEnvOrDotEnv(normalized.toUpperCase() + "_MAX_TOKENS"));
        }
        if (override != null) {
            return override;
        }
        ProviderConfig providerConfig = providers.get(normalized);
        return providerConfig == null || providerConfig.getMaxTokens() <= 0
                ? 8192
                : providerConfig.getMaxTokens();
    }

    private static Integer positiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static DevCliConfig load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return mapper.readValue(CONFIG_FILE.toFile(), DevCliConfig.class);
            } catch (IOException e) {
                System.err.println("⚠️ 配置文件读取失败，使用默认配置: " + e.getMessage());
            }
        }
        return new DevCliConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("⚠️ 配置保存失败: " + e.getMessage());
        }
    }

    public static String getEnvOrDotEnv(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        String dotEnvValue = readFromDotEnv(key);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }
        return null;
    }

    private static String loadModelFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_MODEL";
            case "deepseek" -> "DEEPSEEK_MODEL";
            case "kimi" -> "KIMI_MODEL";
            default -> provider.toUpperCase() + "_MODEL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_MODEL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_MODEL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadApiKeyFromEnv(String provider) {
        if ("anthropic".equalsIgnoreCase(provider)) {
            String authToken = getEnvOrDotEnv("ANTHROPIC_AUTH_TOKEN");
            if (authToken != null && !authToken.isBlank()) {
                return authToken.trim();
            }
            String apiKey = getEnvOrDotEnv("ANTHROPIC_API_KEY");
            return apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        }

        String envKey = switch (provider.toLowerCase()) {
            case "glm" -> "GLM_API_KEY";
            case "deepseek" -> "DEEPSEEK_API_KEY";
            case "step" -> "STEP_API_KEY";
            case "kimi" -> "KIMI_API_KEY";
            default -> provider.toUpperCase() + "_API_KEY";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_API_KEY");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_API_KEY");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String loadBaseUrlFromEnv(String provider) {
        String envKey = switch (provider.toLowerCase()) {
            case "step" -> "STEP_BASE_URL";
            case "kimi" -> "KIMI_BASE_URL";
            default -> provider.toUpperCase() + "_BASE_URL";
        };

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String dotEnvValue = readFromDotEnv(envKey);
        if (dotEnvValue != null && !dotEnvValue.isBlank()) {
            return dotEnvValue.trim();
        }

        if ("kimi".equalsIgnoreCase(provider)) {
            String moonshotValue = System.getenv("MOONSHOT_BASE_URL");
            if (moonshotValue != null && !moonshotValue.isBlank()) {
                return moonshotValue.trim();
            }
            String moonshotDotEnvValue = readFromDotEnv("MOONSHOT_BASE_URL");
            if (moonshotDotEnvValue != null && !moonshotDotEnvValue.isBlank()) {
                return moonshotDotEnvValue.trim();
            }
        }

        return null;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = { new File(".env"), new File(System.getProperty("user.home"), ".env") };
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }
}
