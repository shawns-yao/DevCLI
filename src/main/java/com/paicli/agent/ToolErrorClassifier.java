package com.paicli.agent;

import java.util.Locale;

final class ToolErrorClassifier {
    private ToolErrorClassifier() {
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
