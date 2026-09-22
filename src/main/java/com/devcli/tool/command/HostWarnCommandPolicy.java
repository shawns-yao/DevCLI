package com.devcli.tool.command;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HOST_RESTRICTED（兼容 HOST_WARN）的命令白名单，不隔离项目构建代码。
 *
 * <p>同时被任务级授权复用：判定某条命令是否属于「项目构建/测试」这一类可授权动作。</p>
 */
public final class HostWarnCommandPolicy {
    private static final Set<String> BUILD_COMMANDS = Set.of(
            "mvn", "mvn.cmd", "mvnw", "mvnw.cmd", "javac");
    private static final Set<String> MAVEN_LIFECYCLE_PHASES = Set.of(
            "clean", "validate", "compile", "test-compile", "test", "package", "verify");
    private static final Set<String> READ_ONLY_GIT_COMMANDS = Set.of(
            "status", "diff", "log", "show", "rev-parse", "ls-files", "grep");

    private HostWarnCommandPolicy() {
    }

    /**
     * 该命令是否属于已授权的项目构建/测试类动作：Maven 生命周期、javac 或只读 Git，
     * 且不含 Shell 控制符。复用同一份白名单，避免第二处 allowlist。
     *
     * <p>只做分类，不执行；拒绝原因对调用方无意义，因此不向外暴露。</p>
     */
    public static boolean isProjectBuildOrReadOnlyGit(String command) {
        try {
            validateAndNormalize(command);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    static String validateAndNormalize(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        if (containsShellControlOperator(command)) {
            throw rejected("不允许命令串、管道、重定向或命令替换");
        }
        List<String> tokens = command.isBlank() ? List.of() : List.of(command.split("\\s+"));
        if (tokens.isEmpty()) {
            throw rejected("命令不能为空");
        }

        String executable = executableName(tokens.get(0));
        if ("git".equals(executable)) {
            if (tokens.size() < 2
                    || !READ_ONLY_GIT_COMMANDS.contains(tokens.get(1).toLowerCase(Locale.ROOT))) {
                throw rejected("Git 仅允许只读子命令");
            }
            return command;
        }
        if (!BUILD_COMMANDS.contains(executable)) {
            throw rejected("仅允许 Maven、javac 和只读 Git 命令");
        }
        if (Set.of("mvn", "mvn.cmd", "mvnw", "mvnw.cmd").contains(executable)) {
            validateMavenLifecycle(tokens);
            if (tokens.stream().noneMatch(token -> "-o".equals(token) || "--offline".equals(token))) {
                return tokens.get(0) + " -o" + command.substring(tokens.get(0).length());
            }
        }
        return command;
    }

    private static void validateMavenLifecycle(List<String> tokens) {
        boolean hasLifecyclePhase = false;
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("-")) {
                continue;
            }
            String phase = token.toLowerCase(Locale.ROOT);
            if (!MAVEN_LIFECYCLE_PHASES.contains(phase)) {
                throw rejected("Maven 仅允许 clean/validate/compile/test-compile/test/package/verify");
            }
            hasLifecyclePhase = true;
        }
        if (!hasLifecyclePhase) {
            throw rejected("Maven 命令必须声明允许的生命周期阶段");
        }
    }

    private static boolean containsShellControlOperator(String command) {
        return command.contains(";") || command.contains("&&") || command.contains("||")
                || command.contains("|") || command.contains(">") || command.contains("<")
                || command.contains("`") || command.contains("$(")
                || command.contains("\n") || command.contains("\r");
    }

    private static String executableName(String token) {
        String normalized = token.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized)
                .toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException rejected(String reason) {
        return new IllegalArgumentException("HOST_RESTRICTED（兼容 HOST_WARN）主机执行已拒绝：" + reason);
    }
}
