package com.devcli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class McpConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> POLICY_FIELDS = Set.of(
            "trustReadOnlyAnnotations", "readOnlyTools", "deniedTools", "disabled");
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*|PROJECT_DIR|HOME)}");

    private final Path userConfig;
    private final Path projectConfig;
    private final Path projectDir;
    private final Path codexConfig;

    public McpConfigLoader(Path projectDir) {
        this(
                Path.of(System.getProperty("user.home"), ".devcli", "mcp.json"),
                projectDir.resolve(".devcli").resolve("mcp.json"),
                projectDir,
                CodexMcpConfigLoader.defaultConfigPath()
        );
    }

    public McpConfigLoader(Path userConfig, Path projectConfig, Path projectDir) {
        this(userConfig, projectConfig, projectDir, null);
    }

    public McpConfigLoader(Path userConfig, Path projectConfig, Path projectDir, Path codexConfig) {
        this.userConfig = userConfig;
        this.projectConfig = projectConfig;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.codexConfig = codexConfig;
    }

    /**
     * 只读取并合并配置，不做 {@code ${VAR}} 展开。展开 / 校验由 {@link com.devcli.mcp.McpServerManager}
     * 在启动单个 server 时进行，确保单个 server 配置错误（如缺失环境变量）不会阻塞其他 server。
     */
    public Map<String, McpServerConfig> load() throws IOException {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>(CodexMcpConfigLoader.load(codexConfig));
        if (Files.exists(userConfig)) {
            merge(merged, userConfig);
        }
        if (Files.exists(projectConfig)) {
            merge(merged, projectConfig);
        }
        return merged;
    }

    /**
     * 对单个 server 配置展开 {@code ${VAR}} 并校验 transport 选择。
     * 失败抛 {@link IllegalArgumentException}，调用方负责把单个失败转成该 server 的 ERROR 状态。
     */
    public void prepare(McpServerConfig config) {
        expand(config);
        validate(config);
    }

    private void merge(Map<String, McpServerConfig> merged, Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        McpConfigFile configFile = MAPPER.treeToValue(root, McpConfigFile.class);
        for (var entry : configFile.getMcpServers().entrySet()) {
            JsonNode override = root.path("mcpServers").path(entry.getKey());
            McpServerConfig existing = merged.get(entry.getKey());
            if (existing != null && policyOnly(override)) {
                // Local tool policy can extend an imported connection without copying its credentials.
                // Connection replacements remain whole-server overrides and never inherit old trust.
                ObjectNode inherited = MAPPER.valueToTree(existing);
                override.fields().forEachRemaining(field -> inherited.set(field.getKey(), field.getValue()));
                merged.put(entry.getKey(), MAPPER.treeToValue(inherited, McpServerConfig.class));
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean policyOnly(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!POLICY_FIELDS.contains(fields.next())) return false;
        }
        return true;
    }

    private void expand(McpServerConfig config) {
        if (config.getCommand() != null) {
            config.setCommand(expandString(config.getCommand()));
        }
        List<String> expandedArgs = new ArrayList<>();
        for (String arg : config.getArgs()) {
            expandedArgs.add(expandString(arg));
        }
        config.setArgs(expandedArgs);
        config.setEnv(expandMap(config.getEnv()));
        if (config.getUrl() != null) {
            config.setUrl(expandString(config.getUrl()));
        }
        config.setHeaders(expandMap(config.getHeaders()));
    }

    private Map<String, String> expandMap(Map<String, String> raw) {
        Map<String, String> expanded = new LinkedHashMap<>();
        if (raw == null) {
            return expanded;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            expanded.put(entry.getKey(), expandString(entry.getValue()));
        }
        return expanded;
    }

    private String expandString(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = VAR_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "PROJECT_DIR" -> projectDir.toString();
                case "HOME" -> System.getProperty("user.home");
                default -> System.getenv(name);
            };
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("MCP 配置引用了未设置的环境变量: " + name);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void validate(McpServerConfig config) {
        if (!"HOST".equals(config.getSandbox()) && !"WINDOWS_NATIVE".equals(config.getSandbox()))
            throw new IllegalArgumentException("MCP sandbox must be HOST or WINDOWS_NATIVE");
        if (config.isWindowsNativeSandbox() && (!config.isStdio() || !config.getEnv().isEmpty()))
            throw new IllegalArgumentException("WINDOWS_NATIVE MCP requires local stdio and an empty env; offline read-only profile");
        if (config.isStdio() == config.isHttp()) {
            throw new IllegalArgumentException("MCP server 必须且只能配置 command 或 url");
        }
    }
}
