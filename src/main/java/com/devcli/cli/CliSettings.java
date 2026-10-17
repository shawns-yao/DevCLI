package com.devcli.cli;

import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.policy.PermissionMode;
import org.jline.reader.LineReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.cli.CliApplication.*;
import static com.devcli.cli.TerminalInputSupport.*;
import static com.devcli.cli.CliTurnRunner.*;
import static com.devcli.cli.CliPlanReview.*;
import static com.devcli.cli.CliDisplay.*;

/** 启动配置、日志、权限规则与模型选择。 */
final class CliSettings {
    private static final Logger log = LoggerFactory.getLogger(CliSettings.class);

    static final String VERSION = BuildVersion.VALUE;

    static final String ENV_FILE = ".env";

    static final String LOG_DIR_PROPERTY = "devcli.log.dir";

    static final String LOG_LEVEL_PROPERTY = "devcli.log.level";

    static final String LOG_MAX_HISTORY_PROPERTY = "devcli.log.maxHistory";

    static final String LOG_MAX_FILE_SIZE_PROPERTY = "devcli.log.maxFileSize";

    static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "devcli.log.totalSizeCap";

    /** 会话级规则层的状态栏/状态视图摘要；启动时加载后不再变化。 */
    static volatile String permissionRuleSummary = "";

    static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    /**
     * 从用户级配置读取规则层。
     *
     * <p>非法规则显式拒绝并整体降级为「无规则」：一条写错的规则如果被静默丢弃，用户会以为
     * 拒绝规则在生效。语法与校验只保留在 {@code PermissionRule} 里，不在此重复。</p>
     *
     */
    static com.devcli.policy.PermissionRuleSet loadPermissionRules(DevCliConfig config) {
        DevCliConfig.PermissionsConfig permissions = config == null ? null : config.getPermissions();
        if (permissions == null) {
            return com.devcli.policy.PermissionRuleSet.EMPTY;
        }
        try {
            com.devcli.policy.PermissionRuleSet parsed = com.devcli.policy.PermissionRuleSet.parse(
                    permissions.getHardDeny(), permissions.getSoftDeny(),
                    permissions.getAllow(), permissions.getEnvironment());
            return parsed;
        } catch (IllegalArgumentException invalid) {
            System.err.println("⚠️ 权限规则非法，已按无规则处理: " + invalid.getMessage());
            System.err.println("   请检查 ~/.devcli/config.json 的 permissions."
                    + "hard_deny / soft_deny / allow / environment。");
            return com.devcli.policy.PermissionRuleSet.EMPTY;
        }
    }

    /**
     * 启动权限模式：读 {@code permissions.defaultMode}，缺省为 {@link PermissionMode#DEFAULT}。
     *
     * <p>非法值显式拒绝并回落到 {@code default}，与规则层的处理方式一致——一个写错的模式名
     * 若被静默忽略，用户会以为自己已经切到了更严格的模式。</p>
     */
    static PermissionMode startupPermissionMode(DevCliConfig config) {
        DevCliConfig.PermissionsConfig permissions = config == null ? null : config.getPermissions();
        if (permissions == null) {
            return PermissionMode.DEFAULT;
        }
        try {
            return PermissionMode.parse(permissions.getDefaultMode());
        } catch (IllegalArgumentException invalid) {
            System.err.println("⚠️ 权限模式非法，已回落到 default: " + invalid.getMessage());
            System.err.println("   请检查 ~/.devcli/config.json 的 permissions.defaultMode。");
            return PermissionMode.DEFAULT;
        }
    }

    /**
     * 本轮执行期间是否完全不会弹出审批。
     *
     * <p>只有这个条件成立时，主 LineReader 才能与执行并发读取终端——审批读取器和队列读取器
     * 同时抢 stdin 会让输入错乱。</p>
     *
     * <p>{@code dontAsk} 把未决动作收口为拒绝，不会弹；{@code bypassPermissions} 放行未决动作，
     * 但 {@code soft_deny} 仍然会询问。其余模式都可能弹出审批。</p>
     */
    static boolean neverPrompts(PermissionMode mode,
                                com.devcli.policy.PermissionRuleSet rules) {
        return switch (mode.askPolicy()) {
            case DENY -> true;
            case ALLOW -> rules == null || rules.softDeny().isEmpty();
            default -> false;
        };
    }

    /**
     * 装配权限分类器。
     *
     * <p>装配失败返回 {@code null} 并告警，而不是让启动失败：分类器缺失只影响 {@code auto} 模式
     * （该模式下未决动作失败关闭），不该阻塞其余功能。</p>
     */
    static com.devcli.hitl.PermissionClassifier permissionClassifier(
            AtomicReference<LlmClient> llmClientRef) {
        try {
            return com.devcli.hitl.LlmPermissionClassifier.createDefault(llmClientRef::get);
        } catch (RuntimeException failure) {
            System.err.println("⚠️ 权限分类器装配失败，auto 模式下未决动作将按拒绝处理: " + failure.getMessage());
            return null;
        }
    }

    static Duration mcpStartupWait() {
        String configured = System.getProperty("devcli.mcp.startup.wait.seconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_MCP_STARTUP_WAIT_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return Duration.ofSeconds(30);
        }
        try {
            long seconds = Long.parseLong(configured.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(30);
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(30);
        }
    }

    static int configuredPositiveInt(String property, String env, int fallback) {
        String raw = firstNonBlank(System.getProperty(property), System.getenv(env));
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * 从 .env 文件加载 API Key
     */
    static String loadApiKey() {
        return loadConfigValue("GLM_API_KEY", null);
    }

    static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "DEVCLI_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".devcli", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "DEVCLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "DEVCLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "DEVCLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "DEVCLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    static ModelSelection resolveModelSelection(String raw) {
        String value = raw == null ? "" : raw.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "anthropic", "claude" -> new ModelSelection("anthropic", null, false);
            case "glm" -> new ModelSelection("glm", "glm-5.1", true);
            case "deepseek" -> new ModelSelection("deepseek", null, false);
            case "step", "stepfun", "step-fun" -> new ModelSelection("step", null, false);
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> new ModelSelection("kimi", null, false);
            default -> {
                if (normalized.startsWith("glm-")) {
                    yield new ModelSelection("glm", value, true);
                }
                if (normalized.startsWith("deepseek")) {
                    yield new ModelSelection("deepseek", value, true);
                }
                if (normalized.startsWith("step")) {
                    yield new ModelSelection("step", value, true);
                }
                if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) {
                    yield new ModelSelection("kimi", value, true);
                }
                if (normalized.startsWith("claude-")) {
                    yield new ModelSelection("anthropic", value, true);
                }
                yield new ModelSelection(normalized, null, false);
            }
        };
    }

    static DevCliConfig.ProviderConfig ensureProviderConfig(DevCliConfig config, String provider) {
        if (config.getProviders() == null) {
            config.setProviders(new LinkedHashMap<>());
        }
        return config.getProviders().computeIfAbsent(provider, ignored -> new DevCliConfig.ProviderConfig());
    }

    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = userHome.resolve(".devcli").resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 ~/.devcli/mcp.json 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new McpConfigBootstrapResult(false, "");
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }

    record ModelSelection(String provider, String model, boolean explicitModel) {
    }
}
