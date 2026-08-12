package com.devcli.tool.provider;

import com.devcli.policy.CommandGuard;
import com.devcli.policy.PolicyException;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

import java.util.Map;

public final class ShellToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "execute_command",
                "执行短时 Shell 命令。默认使用无网络、最小权限 Docker；宿主机命令仅允许显式 TRUSTED_HOST profile。",
                context.createToolParameters(
                        new ToolParameter("command", "string", "要执行的命令", true),
                        new ToolParameter("profile", "string", "可选命令画像", false,
                                "MAVEN_COMPILE", "MAVEN_TEST", "READ_ONLY_SHELL",
                                "PROJECT_BUILD", "CUSTOM_SANDBOX", "TRUSTED_HOST")),
                args -> executeCommand(args, context)
        ));
    }

    private ToolOutput executeCommand(Map<String, String> args, ToolContext context) {
        String command = args.get("command") == null ? "" : args.get("command").trim();
        if (command.isEmpty()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "执行命令失败: 命令不能为空", false);
        }
        String denyReason = CommandGuard.check(command);
        if (denyReason != null) {
            throw new PolicyException(denyReason);
        }
        return context.executeCommandOutput(command, args.get("profile"));
    }
}
