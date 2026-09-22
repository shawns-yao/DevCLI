package com.devcli.hitl;

import com.devcli.policy.CommandGuard;
import com.devcli.policy.TaskGrant;
import com.devcli.policy.ToolResourceSlot;
import com.devcli.policy.WriteGlobSet;
import com.devcli.tool.command.HostWarnCommandPolicy;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 审批三值判定：把「哪几类工具必须审批」换成「这次具体操作是否落在用户已授权的范围内」。
 *
 * <p>判定顺序固定为策略优先：先用系统策略硬边界拒绝（路径越界、命令黑名单），
 * 再判断用户授权是否覆盖本次参数，最后才落到人工审批。授权只能收窄策略允许集，
 * 永远不能放宽，所以 {@link Decision#DENY} 不会被任何授权或自动审批覆盖。</p>
 *
 * <p>参数级判定依赖「参数里哪个字段是资源地址」这件事，映射集中在
 * {@link ToolResourceSlot}；未声明的工具（含全部 MCP 工具）一律回到人工审批，不做猜测。</p>
 */
public final class ApprovalGate {

    public enum Decision {
        /** 落在已授权范围内，静默放行。 */
        ALLOW,
        /** 需要人工确认。 */
        HITL,
        /** 违反系统策略，不允许执行，也不允许用户在此处批准。 */
        DENY
    }

    public record Result(Decision decision, String reason) {
        static Result allow(String reason) {
            return new Result(Decision.ALLOW, reason);
        }

        static Result hitl(String reason) {
            return new Result(Decision.HITL, reason);
        }

        static Result deny(String reason) {
            return new Result(Decision.DENY, reason);
        }
    }

    private static final Set<String> ALLOWED_URL_SCHEMES = Set.of("http", "https");

    private ApprovalGate() {
    }

    /**
     * 审批判定需要的路径能力。
     *
     * <p>由调用方提供，避免判定逻辑直接依赖注册表：`resolve` 负责策略边界，
     * `relativeKey` 负责把路径映射为项目相对键用于 glob 判定。</p>
     */
    public interface PathScope {
        /** 解析为项目内安全路径；越界时抛异常。 */
        Path resolve(String path);

        /** 项目相对路径键；无法解析时返回 null。 */
        String relativeKey(String path);
    }

    /**
     * @param arguments 已通过参数校验的参数对象；为 null 时按人工审批处理
     * @param grant     本次任务的授权范围；null 或空表示未授权
     * @param pathScope 项目根围栏与相对键解析；null 时退化为人工审批
     */
    public static Result decide(String toolName, JsonNode arguments, TaskGrant grant,
                                PathScope pathScope) {
        TaskGrant effective = grant == null ? TaskGrant.NONE : grant;
        String name = toolName == null ? "" : toolName;
        ToolResourceSlot slot = ToolResourceSlot.of(name);
        return switch (slot) {
            // create_project 的资源槽是项目名：它会在该目录下写出整个骨架，授权必须覆盖该目录
            case PATH, PROJECT_NAME -> decideWorkspaceWrite(name, arguments, effective, pathScope, slot);
            case COMMAND -> decideCommand(arguments, effective);
            case PATH_LIST -> decideDeletion(arguments, effective, pathScope);
            // web_fetch 的网络目的地就是 URL 参数，可以按域名判定是否属于已授权出口
            case HOST -> decideWebFetch(arguments, effective);
            // revert_turn 批量回写整个工作区、MCP 工具没有可信的参数级资源槽
            case NONE -> Result.hitl("未声明参数级授权选择器，按人工审批处理");
        };
    }

    /**
     * 按资源槽提取本次调用的资源值，供规则层匹配。
     *
     * <p>路径类返回项目相对键，项目名与命令行原样返回，网络类返回解析出的主机名。
     * 无法解析时返回 {@code null}——调用方必须按「带 specifier 的规则不命中」处理并退回
     * 人工审批，不能当成空清单，否则不带 specifier 的规则会意外命中。</p>
     */
    public static List<String> resourceValues(String toolName, JsonNode arguments, PathScope pathScope) {
        ToolResourceSlot slot = ToolResourceSlot.of(toolName);
        return switch (slot) {
            case PATH -> singleValue(relativeKeyOrNull(text(arguments, slot.argumentName()), pathScope));
            case PROJECT_NAME, COMMAND -> singleValue(blankToNull(text(arguments, slot.argumentName())));
            case HOST -> singleValue(hostOrNull(text(arguments, slot.argumentName())));
            case PATH_LIST -> pathListValues(arguments, pathScope);
            // 没有资源槽的工具只能按整个工具匹配，规则层不会为它解释 specifier
            case NONE -> List.of();
        };
    }

    private static Result decideWebFetch(JsonNode arguments, TaskGrant grant) {
        String url = text(arguments, ToolResourceSlot.HOST.argumentName());
        if (url.isBlank()) {
            return Result.hitl("缺少 " + ToolResourceSlot.HOST.argumentName() + " 参数，无法判定授权范围");
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.trim());
        } catch (RuntimeException invalid) {
            return Result.deny("URL 格式非法：" + invalid.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_URL_SCHEMES.contains(scheme)) {
            // 与 NetworkPolicy 的 scheme 白名单一致：必然被拒的请求不先打扰用户
            return Result.deny("URL scheme 不在允许范围（仅 http、https）：" + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Result.hitl("无法解析 URL 主机，按人工审批处理：" + url);
        }
        if (grant.authorizesHost(host)) {
            return Result.allow("已授权访问域名 " + host);
        }
        return Result.hitl("本次任务未授权访问陌生域名 " + host);
    }

    private static Result decideWorkspaceWrite(String toolName, JsonNode arguments, TaskGrant grant,
                                               PathScope pathScope, ToolResourceSlot slot) {
        String path = text(arguments, slot.argumentName());
        return decideWorkspacePath(toolName, path, grant, pathScope);
    }

    private static Result decideDeletion(JsonNode arguments, TaskGrant grant, PathScope pathScope) {
        String field = ToolResourceSlot.PATH_LIST.argumentName();
        if (arguments == null || !arguments.path(field).isArray() || arguments.path(field).isEmpty()) {
            return Result.hitl("缺少删除清单");
        }
        Result pending = null;
        for (JsonNode path : arguments.path(field)) {
            Result result = decideWorkspacePath("delete_files", path.asText(), grant, pathScope);
            if (result.decision() == Decision.DENY) return result;
            if (result.decision() == Decision.HITL) pending = result;
        }
        return pending == null ? Result.allow("删除清单全部位于本次任务写入授权范围内") : pending;
    }

    private static Result decideWorkspacePath(String toolName, String path, TaskGrant grant,
                                              PathScope pathScope) {
        if (path.isBlank()) {
            return Result.hitl("缺少目标路径，无法判定授权范围");
        }
        if (pathScope == null) {
            return Result.hitl("缺少路径解析能力，无法判定授权范围");
        }
        try {
            pathScope.resolve(path);
        } catch (RuntimeException outOfPolicy) {
            // 策略硬边界优先：越界写不需要也不允许用户在审批里放行，先拒绝再谈授权
            return Result.deny("路径超出策略边界：" + outOfPolicy.getMessage());
        }
        if (!grant.workspaceWrites()) {
            return Result.hitl("本次任务未授权写入项目内文件");
        }
        String relativeKey = pathScope.relativeKey(path);
        if (relativeKey == null) {
            return Result.hitl("无法解析项目相对路径，按人工审批处理：" + path);
        }
        if (!WriteGlobSet.matches(grant.writeGlobs(), relativeKey)) {
            return Result.hitl("写入路径不在本次授权范围内：" + relativeKey
                    + "（已授权 " + String.join("、", grant.writeGlobs()) + "）");
        }
        return Result.allow("已授权写入 " + relativeKey + "：" + toolName + " " + path);
    }

    private static Result decideCommand(JsonNode arguments, TaskGrant grant) {
        String command = text(arguments, ToolResourceSlot.COMMAND.argumentName());
        if (command.isBlank()) {
            return Result.hitl("缺少 " + ToolResourceSlot.COMMAND.argumentName() + " 参数，无法判定授权范围");
        }
        String blacklisted = CommandGuard.check(command);
        if (blacklisted != null) {
            return Result.deny("命令命中策略黑名单：" + blacklisted);
        }
        if (CommandGuard.containsDeletion(command)) {
            return Result.hitl("命令包含删除操作，不复用项目构建测试授权");
        }
        if (!grant.projectCommands()) {
            return Result.hitl("本次任务未授权自动运行命令");
        }
        if (!HostWarnCommandPolicy.isProjectBuildOrReadOnlyGit(command)) {
            // 含管道/重定向、git push、任意插件等：白名单外一律让人确认，不自动放行
            return Result.hitl("命令不在项目构建/测试白名单内，需要人工确认");
        }
        return Result.allow("已授权运行项目构建/测试命令：" + command);
    }

    private static List<String> singleValue(String value) {
        return value == null ? null : List.of(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String relativeKeyOrNull(String path, PathScope pathScope) {
        if (pathScope == null || path == null || path.isBlank()) {
            return null;
        }
        try {
            return pathScope.relativeKey(path);
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static List<String> pathListValues(JsonNode arguments, PathScope pathScope) {
        String field = ToolResourceSlot.PATH_LIST.argumentName();
        if (arguments == null || !arguments.path(field).isArray() || arguments.path(field).isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode path : arguments.path(field)) {
            String key = relativeKeyOrNull(path.asText(), pathScope);
            if (key == null) {
                return null;
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    private static String hostOrNull(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!ALLOWED_URL_SCHEMES.contains(scheme)) {
                return null;
            }
            String host = uri.getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        return value == null || !value.isTextual() ? "" : value.asText().trim();
    }
}
