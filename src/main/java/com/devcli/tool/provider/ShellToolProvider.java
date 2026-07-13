package com.devcli.tool.provider;

import com.devcli.policy.CommandGuard;
import com.devcli.policy.PolicyException;
import com.devcli.tool.ToolRegistry;

import java.util.Map;

public final class ShellToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "execute_command",
                "执行短时 Shell 命令。隔离任务强制使用无网络、最小权限 Docker 容器，禁止回退到主机。",
                context.createToolParameters(new ToolParameter(
                        "command", "string", "要执行的命令", true)),
                args -> executeCommand(args, context)
        ));
    }

    private String executeCommand(Map<String, String> args, ToolContext context) {
        String command = args.get("command") == null ? "" : args.get("command").trim();
        if (command.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        String denyReason = CommandGuard.check(command);
        if (denyReason != null) {
            throw new PolicyException(denyReason);
        }
        return context.executeCommand(command);
    }
}
