package com.devcli.policy;

import java.util.Set;
import java.util.regex.Pattern;

/** 主机命令的窄规则与可保存前缀；Shell 复合语义不由前缀匹配授权。 */
public final class CommandPermissionRules {
    private static final Pattern SHELL_SYNTAX = Pattern.compile("[&;|<>`$()%!^\\r\\n]");
    private static final Set<String> INTERPRETERS = Set.of(
            "sh", "bash", "zsh", "fish", "cmd", "cmd.exe", "powershell", "powershell.exe",
            "pwsh", "pwsh.exe", "python", "python3", "python.exe", "node", "node.exe",
            "ruby", "perl", "php", "jshell");

    private CommandPermissionRules() { }

    public static boolean isSimpleCommand(String command) {
        return !SHELL_SYNTAX.matcher(command).find();
    }

    public static boolean isNarrow(PermissionRule rule) {
        String spec = rule.specifier();
        if (!"execute_command".equals(rule.tool()) || spec == null) return false;
        if (!isSimpleCommand(spec)) return false;
        if (!spec.contains("*") && !spec.contains("?")) return true;
        String prefix = spec.substring(0, spec.indexOf('*') < 0 ? spec.indexOf('?') : spec.indexOf('*'));
        if (prefix.endsWith(":")) prefix = prefix.substring(0, prefix.length() - 1);
        String[] words = prefix.trim().split("\\s+");
        return words.length >= 2 && !isInterpreter(words[0]);
    }

    /** 保留当前完整命令作为前缀，不擅自缩短成可执行程序的所有子命令。 */
    public static String suggestedRule(String command) {
        if (SensitiveContentPolicy.inspect(command).sensitive()) return null;
        if (!isSimpleCommand(command) || command.contains("*") || command.contains("?")
                || command.contains("\\") || command.contains("\"") || command.contains("'")) return null;
        String normalized = command.trim().replaceAll("\\s+", " ");
        PermissionRule rule = PermissionRule.parse("execute_command(" + normalized + ":*)");
        return isNarrow(rule) ? rule.raw() : null;
    }

    private static boolean isInterpreter(String executable) {
        String name = executable.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return INTERPRETERS.contains(name);
    }
}
