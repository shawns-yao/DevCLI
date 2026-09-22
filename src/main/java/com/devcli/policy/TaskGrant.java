package com.devcli.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 任务级授权范围：用户在任务开始前显式授予的确定性权限。
 *
 * <p>只表达「用户已经授权什么」，不表达「系统策略允许什么」。判定永远先由策略硬边界
 * （项目根围栏、命令黑名单、网络 scheme 与 SSRF 拦截）拒绝，再由本授权决定是否还需要
 * 人工审批；授权只能收窄策略允许集，永远不能放宽，因此策略拒绝不会被任何授权覆盖。</p>
 *
 * <p>两个资源维度都是显式清单而不是布尔开关：写入是项目相对路径 glob
 * （`/grant write` 等价于 {@link WriteGlobSet#WHOLE_PROJECT}），网络是域名
 * （`/grant net github.com` 同时授权其子域）。刻意不包含任何由模型推断的字段：
 * 授权只能来自用户显式命令或已批准的计划边界。</p>
 */
public record TaskGrant(List<String> writeGlobs, List<String> networkHosts, boolean projectCommands) {

    public TaskGrant {
        writeGlobs = normalizeGlobs(writeGlobs);
        networkHosts = normalizeHosts(networkHosts);
    }

    /** 兼容既有两参调用：不授权任何网络域名。 */
    public TaskGrant(List<String> writeGlobs, boolean projectCommands) {
        this(writeGlobs, List.of(), projectCommands);
    }

    /** 未授权：所有需要审批的操作继续逐次确认。 */
    public static final TaskGrant NONE = new TaskGrant(List.of(), false);

    /** 允许写入项目内任意文件。 */
    public static final TaskGrant WORKSPACE_WRITES =
            new TaskGrant(List.of(WriteGlobSet.WHOLE_PROJECT), false);

    /** 允许运行项目构建与测试命令。 */
    public static final TaskGrant PROJECT_COMMANDS = new TaskGrant(List.of(), true);

    /** 同时允许项目内写入与项目构建测试命令。 */
    public static final TaskGrant WORKSPACE_WRITES_AND_COMMANDS =
            new TaskGrant(List.of(WriteGlobSet.WHOLE_PROJECT), true);

    /** 只授权给定项目相对 glob 的写入，并保留其他维度原有授权。 */
    public TaskGrant withWriteGlobs(List<String> globs) {
        return new TaskGrant(globs, networkHosts, projectCommands);
    }

    /** 只授权给定域名（含其子域）的访问，并保留其他维度原有授权。 */
    public TaskGrant withNetworkHosts(List<String> hosts) {
        return new TaskGrant(writeGlobs, hosts, projectCommands);
    }

    public boolean workspaceWrites() {
        return !writeGlobs.isEmpty();
    }

    public boolean networkAllowed() {
        return !networkHosts.isEmpty();
    }

    public boolean isEmpty() {
        return !workspaceWrites() && !networkAllowed() && !projectCommands;
    }

    /**
     * 该主机是否已被授权：域名相等，或主机的父域等于授权域名（即授权 `.` 级子域）。
     *
     * <p>刻意不支持通配符：`github.com` 授权 `github.com` 与 `api.github.com`，
     * 但不会授权 `notgithub.com`；端口与路径不参与判定。</p>
     */
    public boolean authorizesHost(String host) {
        if (host == null || host.isBlank() || networkHosts.isEmpty()) {
            return false;
        }
        String normalized = normalizeHost(host);
        if (normalized == null) {
            return false;
        }
        for (String allowed : networkHosts) {
            if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用户可见的授权摘要。未授权时必须显式说明「未授权」，不能留空或含糊表达。
     */
    public String summary() {
        if (isEmpty()) {
            return "本次任务未授权自动放行：写入、命令、网络等操作仍需逐次确认";
        }
        List<String> granted = new ArrayList<>();
        if (workspaceWrites()) {
            boolean wholeProject = writeGlobs.size() == 1
                    && WriteGlobSet.WHOLE_PROJECT.equals(writeGlobs.get(0));
            granted.add(wholeProject
                    ? "写入项目内文件"
                    : "写入项目内指定路径 " + String.join("、", writeGlobs));
        }
        if (networkAllowed()) {
            granted.add("访问域名 " + String.join("、", networkHosts) + "（含子域）");
        }
        if (projectCommands) {
            granted.add("运行项目构建与测试命令（Maven / javac / 只读 Git）");
        }
        return "本次任务已授权：" + String.join("；", granted) + "；其他操作仍需逐次确认";
    }

    /**
     * 状态栏用的单行摘要；未授权时返回空串。
     *
     * <p>与 {@link #summary()} 的分工：那个面向用户主动查看授权时的完整说明，这个只用于底部
     * 常驻栏，必须短到不会挤掉其他状态位。返回空串而非「未授权」，是为了让状态栏在无基线时
     * 不出现一个长期占位的空字段。</p>
     */
    public String compactSummary() {
        if (isEmpty()) {
            return "";
        }
        List<String> granted = new ArrayList<>();
        if (workspaceWrites()) {
            boolean wholeProject = writeGlobs.size() == 1
                    && WriteGlobSet.WHOLE_PROJECT.equals(writeGlobs.get(0));
            granted.add(wholeProject ? "write **" : "write " + String.join(",", writeGlobs));
        }
        if (networkAllowed()) {
            granted.add("net " + String.join(",", networkHosts));
        }
        if (projectCommands) {
            granted.add("commands");
        }
        return String.join(" · ", granted);
    }

    private static List<String> normalizeGlobs(List<String> globs) {
        List<String> validated = new ArrayList<>();
        for (String raw : globs == null ? List.<String>of() : globs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String pattern = WriteGlobSet.requireProjectRelativeGlob(raw);
            if (!validated.contains(pattern)) {
                validated.add(pattern);
            }
        }
        return List.copyOf(validated);
    }

    private static List<String> normalizeHosts(List<String> hosts) {
        List<String> validated = new ArrayList<>();
        for (String raw : hosts == null ? List.<String>of() : hosts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String host = requireHost(raw);
            if (!validated.contains(host)) {
                validated.add(host);
            }
        }
        return List.copyOf(validated);
    }

    /** 只接受裸域名；拒绝 scheme、路径、端口与通配符，避免授权语义含糊。 */
    private static String requireHost(String raw) {
        String host = normalizeHost(raw);
        if (host == null) {
            throw new IllegalArgumentException("授权域名不能为空");
        }
        if (host.contains("/") || host.contains(":") || host.contains("*")
                || host.contains("@") || host.contains(" ") || host.startsWith(".")) {
            throw new IllegalArgumentException(
                    "授权域名只能是裸域名（不含 scheme/路径/端口/通配符）: " + raw);
        }
        return host;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? null : normalized;
    }
}
