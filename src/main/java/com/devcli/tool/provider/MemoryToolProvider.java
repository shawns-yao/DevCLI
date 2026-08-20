package com.devcli.tool.provider;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

public final class MemoryToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；不要保存一次性任务请求、临时文件名或模型猜测。",
                context.createToolParameters(new ToolParameter("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true)),
                args -> saveMemory(context, args.get("fact"))
        ));
        context.registerTool(ToolRegistry.Tool.structured(
                "list_memory",
                "只读查询当前已持久化的长期记忆条目；当用户想查看、核对或审计系统记住了什么时使用。不要用它检索项目代码，代码问题仍使用 search_code。",
                context.createToolParameters(new ToolParameter("limit", "integer", "最多返回多少条长期记忆，默认 20", false)),
                args -> listMemory(context, args.get("limit"))
        ));
    }

    private ToolOutput saveMemory(ToolContext context, String fact) {
        if (fact == null || fact.isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "保存长期记忆失败: fact 不能为空", false);
        }
        String normalized = fact.trim();
        ToolRegistry.MemorySaver saveHandler = context.memorySaveHandler();
        if (saveHandler != null) {
            ToolRegistry.MemorySaveResult saveResult = saveHandler.save(normalized);
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
        java.util.function.Consumer<String> memorySaver = context.memorySaver();
        if (memorySaver == null) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "保存长期记忆失败: 记忆保存器未初始化", false);
        }
        memorySaver.accept(normalized);
        return ToolOutput.success("已保存到长期记忆");
    }

    private ToolOutput listMemory(ToolContext context, String limitValue) {
        ToolRegistry.MemoryListHandler listHandler = context.memoryListHandler();
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
}
