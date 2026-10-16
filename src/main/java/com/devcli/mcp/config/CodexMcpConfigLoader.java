package com.devcli.mcp.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads shared server connections without inheriting another client's approval rules. */
final class CodexMcpConfigLoader {
    private CodexMcpConfigLoader() { }

    static Path defaultConfigPath() {
        String configured = System.getenv("CODEX_HOME");
        Path home = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(configured);
        return home.resolve("config.toml");
    }

    static Map<String, McpServerConfig> load(Path file) throws IOException {
        Map<String, McpServerConfig> result = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return result;
        }
        TomlParseResult parsed = Toml.parse(file);
        if (parsed.hasErrors()) {
            throw new IOException("Codex MCP TOML is invalid at " + parsed.errors().get(0).position());
        }
        TomlTable servers = parsed.getTable("mcp_servers");
        if (servers == null) {
            return result;
        }
        for (String name : servers.keySet()) {
            TomlTable server = servers.getTable(List.of(name));
            if (server == null) {
                continue;
            }
            McpServerConfig config = new McpServerConfig();
            config.setCommand(server.getString("command"));
            config.setArgs(strings(server.getArray("args")));
            config.setUrl(server.getString("url"));
            config.setDisabled(Boolean.FALSE.equals(server.getBoolean("enabled"))
                    || Boolean.TRUE.equals(server.getBoolean("disabled")));
            Map<String, String> environment = stringMap(server.getTable("env"));
            for (String variable : strings(server.getArray("env_vars"))) {
                String value = System.getenv(variable);
                if (value != null) {
                    environment.putIfAbsent(variable, value);
                }
            }
            config.setEnv(environment);
            Map<String, String> headers = stringMap(server.getTable("http_headers"));
            stringMap(server.getTable("env_http_headers")).forEach((header, variable) ->
                    headers.put(header, "${" + variable + "}"));
            String bearer = server.getString("bearer_token_env_var");
            if (bearer != null && !bearer.isBlank()) {
                headers.put("Authorization", "Bearer ${" + bearer + "}");
            }
            config.setHeaders(headers);
            config.setDeniedTools(strings(server.getArray("disabled_tools")));
            result.put(name, config);
        }
        return result;
    }

    private static List<String> strings(TomlArray values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (int index = 0; index < values.size(); index++) {
                result.add(values.getString(index));
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(TomlTable values) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) {
            for (String key : values.keySet()) {
                result.put(key, values.getString(List.of(key)));
            }
        }
        return result;
    }
}
