package com.devcli.tool.provider;

import com.devcli.tool.ToolContract;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

public final class MemoryToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolContract.Tool.structured(
                "save_memory",
                "保存用户明确要求的稳定事实，或主动提出有证据的 project/feedback 经验；均需单次审批。"
                        + "intent 默认 agent_experience，不能覆盖已有主题；user_request 仅用于用户明确要求保存或更新。默认写当前项目。",
                context.createToolParameters(
                        new ToolParameter("fact", "string", "要长期保存的稳定事实或用户偏好", true),
                        new ToolParameter("name", "string", "稳定的主题名；留空时从 fact 首句推导", false),
                        new ToolParameter("description", "string", "供长期记忆索引使用的一句话描述", false),
                        new ToolParameter("intent", "string", "保存意图；默认 agent_experience，user_request 必须有用户明确请求", false,
                                java.util.List.of("user_request", "agent_experience")),
                        new ToolParameter("type", "string", "记忆类型", false,
                                java.util.List.of("user", "feedback", "project", "reference")),
                        new ToolParameter("scope", "string", "作用域；留空默认当前项目", false,
                                java.util.List.of("global", "project")),
                        new ToolParameter("valid_days", "integer", "可选有效天数，1–3650；留空为长期有效", false)),
                args -> saveMemory(context, args)
        ));
        context.registerTool(ToolContract.Tool.structured(
                "list_memory",
                "只读查询当前已持久化的长期记忆条目；当用户想查看、核对或审计系统记住了什么时使用。代码定位使用 grep_code。",
                context.createToolParameters(new ToolParameter("limit", "integer", "最多返回多少条长期记忆，默认 20", false)),
                args -> listMemory(context, args.get("limit"))
        ));
    }

    private ToolOutput saveMemory(ToolContext context, java.util.Map<String, String> args) {
        String fact = args.get("fact");
        String normalized = fact.trim();
        boolean explicitRequest = "user_request".equals(args.get("intent"));
        ToolContract.MemorySaver saveHandler = context.memorySaveHandler();
        if (saveHandler != null) {
            Integer validDays;
            try {
                validDays = parseOptionalInt(args.get("valid_days"));
            } catch (IllegalArgumentException e) {
                return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, e.getMessage(), false);
            }
            ToolContract.MemorySaveResult saveResult = saveHandler.save(
                    new ToolContract.MemorySaveRequest(normalized, args.get("name"),
                            args.get("description"), args.get("type"), args.get("scope"),
                            validDays, explicitRequest));
            if (saveResult == null) {
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                        "保存长期记忆失败: 记忆保存器未返回结果", false);
            }
            if (!saveResult.stored()) {
                String message = saveResult.message() == null || saveResult.message().isBlank()
                        ? "长期记忆策略拒绝保存"
                        : saveResult.message();
                return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, message);
            }
            String message = saveResult.message() == null || saveResult.message().isBlank()
                    ? "已保存到长期记忆"
                    : saveResult.message();
            return ToolOutput.success(message);
        }
        return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                "保存长期记忆失败: 记忆保存器未初始化", false);
    }

    private ToolOutput listMemory(ToolContext context, String limitValue) {
        ToolContract.MemoryListHandler listHandler = context.memoryListHandler();
        if (listHandler == null) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "查询长期记忆失败: 记忆查询器未初始化", false);
        }
        int limit = parseInt(limitValue, 20);
        return ToolOutput.success(listHandler.list(Math.max(1, limit)));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer parseOptionalInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("valid_days 必须是整数");
        }
    }
}
