package com.devcli.tool.provider;

import com.devcli.tool.ToolContract;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;

import java.util.Map;

public final class ShellToolProvider implements ToolProvider {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolContract.Tool.contextualStructured(
                "execute_command",
                "执行短时 Shell 命令。隔离任务默认使用无网络、最小权限 Docker；"
                        + "仅显式配置 HOST_RESTRICTED（兼容 HOST_WARN）时在主机执行；"
                        + "命令白名单不隔离项目构建代码，仅适用于可信项目。"
                        + "普通主 Agent 命令走主机；可信项目中窄 allow 规则直接放行，auto 对未决命令分类，未信任项目逐次确认。",
                context.createToolParameters(
                        new ToolParameter("command", "string", "要执行的命令", true),
                        new ToolParameter("timeout", "integer", "超时秒数，1-86400；可覆盖默认命令和批次期限，不改变父任务取消", false)),
                new ToolContract.ContextualStructuredToolExecutor() {
                    public ToolOutput executeStructured(Map<String, String> args, ToolExecutionContext executionContext) {
                        return executeCommand(args, context, executionContext);
                    }

                    public long executionTimeoutSeconds(String argumentsJson, long configuredSeconds) {
                        try {
                            var arguments = JSON.readTree(argumentsJson);
                            var timeout = arguments == null ? null : arguments.get("timeout");
                            if (timeout != null && timeout.isIntegralNumber() && timeout.canConvertToLong()
                                    && timeout.longValue() >= 1 && timeout.longValue() <= 86400) return timeout.longValue();
                        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                            // 执行器尚未运行；非法外部参数由参数阶段原样返回错误。
                        }
                        return configuredSeconds;
                    }
                },
                context.commandTimeoutSeconds()
        ));
    }

    private ToolOutput executeCommand(Map<String, String> args, ToolContext context,
                                      ToolExecutionContext executionContext) {
        String command = args.get("command").trim();
        long timeout = args.containsKey("timeout") ? Long.parseLong(args.get("timeout"))
                : context.commandTimeoutSeconds();
        return context.runApprovedCommand(command, timeout, executionContext);
    }
}
