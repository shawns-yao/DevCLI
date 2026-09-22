package com.devcli.hitl;

import java.util.Set;

import com.devcli.tool.BuiltInToolPolicy;
import com.devcli.tool.ToolRegistry.ToolEffect;

/**
 * 危险操作识别策略 - 基于已声明的工具契约判断需要人工确认的调用
 *
 * 设计原则：
 * - 需不需要审批由 {@link BuiltInToolPolicy} 的显式声明决定，不看工具名
 * - 风险等级与说明按已声明的资源域和破坏性等级推导，未知工具不假设安全
 * - MCP 工具来自外部 server，默认都需要确认，服务端只读注解不可信
 */
public class ApprovalPolicy {

    // 需要人工确认的工具集合
    private static final Set<String> DANGEROUS_TOOLS = BuiltInToolPolicy.approvalRequiredTools();

    /**
     * 风险等级高于普通单文件写入的展示覆盖项：任意命令执行与批量不可逆回滚。
     * 这两个名字不属于契约推导，只是用户可见的告警分级。
     */
    private static final Set<String> HIGH_DANGER_TOOLS = Set.of("execute_command", "revert_turn");

    private ApprovalPolicy() {
    }

    /**
     * 判断该工具调用是否需要人工确认
     */
    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    /**
     * 获取危险等级描述：按已声明的资源域推导，未知工具按外部变更处理。
     */
    public static String getDangerLevel(String toolName) {
        if (isMcpTool(toolName)) {
            return "🟡 MCP";
        }
        if (HIGH_DANGER_TOOLS.contains(toolName)) {
            return "🔴 高危";
        }
        ToolEffect effect = BuiltInToolPolicy.effectOrDefault(toolName);
        if (effect == ToolEffect.READ_ONLY && BuiltInToolPolicy.requiresApproval(toolName)) {
            // 需要确认的只读工具（例如 web_fetch 的出口授权）不能标成「安全」
            return "🟡 需确认";
        }
        return switch (effect) {
            case PROJECT_MUTATION, EXTERNAL_MUTATION -> "🟡 中危";
            case HOST_PROCESS -> "🔴 高危";
            case READ_ONLY, LOCAL_CONTEXT -> "🟢 安全";
        };
    }

    /**
     * 获取危险操作的风险说明
     */
    public static String getRiskDescription(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "revert_turn" -> "将按 Side-Git 快照批量恢复工作区文件，可能覆盖当前未保存修改";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "edit_file" -> "将精确替换项目文件内容，错误匹配可能破坏现有实现";
            case "apply_patch" -> "将按补丁批量创建、修改、删除或改名项目文件，影响范围可跨多个文件";
            case "delete_files" -> "将删除清单中的项目文件，不进入回收站；成功后恢复需已有快照或备份";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            case "web_fetch" -> "将向外部站点发起请求，URL 与抓取行为会暴露给第三方；"
                    + "仅允许 http/https，环回与内网地址始终被拒绝，但仍需确认目标域名是否可信";
            default -> defaultRiskDescription(toolName);
        };
    }

    private static String defaultRiskDescription(String toolName) {
        if (isMcpTool(toolName)) {
            return "将调用外部 MCP server 提供的工具，可能访问网络、文件或第三方服务；"
                    + "服务端声明只读不代表没有状态流转（已读标记、游标推进等），注解默认不可信";
        }
        return BuiltInToolPolicy.find(toolName)
                .map(policy -> switch (policy.destructiveness()) {
                    case NONE -> "安全的只读操作";
                    case BENIGN -> "读取操作，但会附带不可逆的良性状态流转，不产生资损";
                    case STRUCTURAL -> "将产生实质性环境变更，可能写入或删除业务数据";
                })
                .orElse("未在本地声明工具契约，按可能产生外部状态变更处理，不得视为只读");
    }

    /**
     * 获取所有需要审批的工具名集合（用于测试和展示）
     */
    public static Set<String> getDangerousTools() {
        return DANGEROUS_TOOLS;
    }

    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    public static String mcpServerName(String toolName) {
        if (!isMcpTool(toolName)) {
            return null;
        }
        String[] parts = toolName.split("__", 3);
        return parts.length >= 2 ? parts[1] : null;
    }
}
