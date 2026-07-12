package com.devcli.agent;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolStatus;

import java.util.Locale;

final class ToolErrorClassifier {
    private ToolErrorClassifier() {
    }

    static String classify(ToolStatus status, ToolErrorCode errorCode) {
        if (status == null || status == ToolStatus.SUCCESS) {
            return "";
        }
        ToolErrorCode code = errorCode == null ? ToolErrorCode.NONE : errorCode;
        return switch (code) {
            case NONE -> status.name().toLowerCase(Locale.ROOT);
            case UNKNOWN_TOOL -> "unknown-tool";
            case INVALID_ARGUMENTS -> "schema";
            case SKILL_PERMISSION_DENIED, HITL_REJECTED, POLICY_DENIED -> "policy";
            case RESOURCE_CONFLICT -> "resource-conflict";
            case EXECUTION_FAILED -> "execution";
            case MCP_ERROR -> "mcp";
            case CANCELLED -> "cancelled";
            case TIMEOUT -> "timeout";
        };
    }

    static String classify(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        String normalized = result.toLowerCase(Locale.ROOT);
        if (normalized.contains("mcp 参数校验失败")
                || normalized.contains("is required")
                || normalized.contains("must be")) {
            return "schema";
        }
        if (normalized.contains("未知工具") || normalized.contains("unknown tool")) {
            return "unknown-tool";
        }
        if (normalized.contains("策略拒绝") || normalized.contains("policy")) {
            return "policy";
        }
        if (normalized.contains("工具执行超时") || normalized.contains("timeout")) {
            return "timeout";
        }
        if (normalized.contains("工具执行失败")
                || normalized.contains("执行命令失败")
                || normalized.contains("no such file")
                || normalized.contains("not found")) {
            return "execution";
        }
        return "";
    }
}
