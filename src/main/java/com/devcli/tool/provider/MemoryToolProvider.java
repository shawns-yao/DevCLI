package com.devcli.tool.provider;

import com.devcli.tool.ToolRegistry;

public final class MemoryToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；不要保存一次性任务请求、临时文件名或模型猜测。",
                context.createToolParameters(new ToolParameter("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true)),
                args -> saveMemory(context, args.get("fact"))
        ));
        context.registerTool(new ToolRegistry.Tool(
                "list_memory",
                "只读查询当前已持久化的长期记忆条目；当用户想查看、核对或审计系统记住了什么时使用。不要用它检索项目代码，代码问题仍使用 search_code。",
                context.createToolParameters(new ToolParameter("limit", "integer", "最多返回多少条长期记忆，默认 20", false)),
                args -> listMemory(context, args.get("limit"))
        ));
    }

    private String saveMemory(ToolContext context, String fact) {
        if (fact == null || fact.isBlank()) {
            return "保存长期记忆失败: fact 不能为空";
        }
        String normalized = fact.trim();
        ToolRegistry.MemorySaver saveHandler = context.memorySaveHandler();
        if (saveHandler != null) {
            ToolRegistry.MemorySaveResult saveResult = saveHandler.save(normalized);
            if (saveResult == null) {
                return "保存长期记忆失败: 记忆保存器未返回结果";
            }
            if (!saveResult.stored()) {
                return saveResult.message() == null || saveResult.message().isBlank()
                        ? "长期记忆策略拒绝保存"
                        : saveResult.message();
            }
            return "💾 已保存到长期记忆: " + normalized;
        }
        java.util.function.Consumer<String> memorySaver = context.memorySaver();
        if (memorySaver == null) {
            return "保存长期记忆失败: 记忆保存器未初始化";
        }
        memorySaver.accept(normalized);
        return "💾 已保存到长期记忆: " + normalized;
    }

    private String listMemory(ToolContext context, String limitValue) {
        ToolRegistry.MemoryListHandler listHandler = context.memoryListHandler();
        if (listHandler == null) {
            return "查询长期记忆失败: 记忆查询器未初始化";
        }
        int limit = parseInt(limitValue, 20);
        return listHandler.list(Math.max(1, limit));
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
