package com.devcli.tool.command;

import com.devcli.config.ConfigResolver;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** 命令进程不继承模型凭据；额外放行只接受明确的环境变量名。 */
final class CommandEnvironment {
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(?:KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH|COOKIE).*"
    );

    private CommandEnvironment() { }

    static void filter(Map<String, String> environment) {
        String configured = ConfigResolver.optional("devcli.command.env.allow", "DEVCLI_COMMAND_ENV_ALLOW");
        Set<String> allowed = configured == null || configured.isBlank() ? Set.of()
                : Arrays.stream(configured.split(",", -1)).map(String::trim).map(name -> {
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new IllegalArgumentException("命令环境放行项必须是明确变量名，不能含通配符或值");
                    }
                    return name.toUpperCase(Locale.ROOT);
                }).collect(Collectors.toUnmodifiableSet());
        environment.keySet().removeIf(name -> SENSITIVE_NAME.matcher(name).matches()
                && !allowed.contains(name.toUpperCase(Locale.ROOT)));
    }
}
