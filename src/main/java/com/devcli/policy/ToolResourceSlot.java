package com.devcli.policy;

import java.util.Locale;

/**
 * 内置工具的参数级资源槽：一次调用里「真正被作用的资源」是哪个参数。
 *
 * <p>资源槽是参数级权限判定的唯一入口。只有在此显式声明的工具才有可信的资源地址；
 * 未声明的工具（含全部 MCP 工具）没有资源槽，规则不能为它写 specifier，参数级授权也不会
 * 为它生效——判定不做猜测。</p>
 *
 * <p>本枚举是「工具名 → 资源槽」的唯一映射。规则匹配与 {@code ApprovalGate} 的判定分支
 * 共用它，避免出现两份会漂移的工具清单。</p>
 */
public enum ToolResourceSlot {

    /** 没有可信资源槽：只能按整个工具授权或拒绝。 */
    NONE,
    /** 单个项目相对路径。 */
    PATH,
    /** 项目相对路径清单。 */
    PATH_LIST,
    /** 项目名：{@code create_project} 会在该名字下写出整个骨架。 */
    PROJECT_NAME,
    /** 命令行。 */
    COMMAND,
    /** 网络主机，从 URL 中解析。 */
    HOST;

    /** 资源槽只由工具名决定，不读取参数内容。 */
    public static ToolResourceSlot of(String toolName) {
        String name = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "write_file", "edit_file" -> PATH;
            case "delete_files" -> PATH_LIST;
            case "create_project" -> PROJECT_NAME;
            case "execute_command" -> COMMAND;
            case "web_fetch" -> HOST;
            default -> NONE;
        };
    }

    /** 承载资源的参数名；{@link #NONE} 没有参数名。 */
    public String argumentName() {
        return switch (this) {
            case PATH -> "path";
            case PATH_LIST -> "paths";
            case PROJECT_NAME -> "name";
            case COMMAND -> "command";
            case HOST -> "url";
            case NONE -> "";
        };
    }

    /** 规则是否可以为该资源槽写 specifier；没有资源槽时只能写裸工具名。 */
    public boolean specifiable() {
        return this != NONE;
    }
}
