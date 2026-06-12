package com.paicli.tool;

import java.util.Map;

/**
 * 工具执行处理器接口
 *
 * 设计目标：
 * - 将 ToolRegistry 的 1353 行拆分为独立的工具处理器
 * - 每个工具类型一个 Handler（FileToolHandler / ShellToolHandler / RagToolHandler 等）
 * - 支持动态注册和测试隔离
 */
public interface ToolHandler {

    /**
     * 获取此 Handler 负责的工具名称列表
     */
    String[] getToolNames();

    /**
     * 执行工具调用
     *
     * @param toolName 工具名称
     * @param args     工具参数（JSON 解析后的 Map）
     * @param context  执行上下文（包含 PathGuard / MemorySaver / LspManager 等）
     * @return 工具执行结果
     */
    String execute(String toolName, Map<String, String> args, ToolContext context);

    /**
     * 获取工具定义（用于 LLM tool calling）
     *
     * @param toolName 工具名称
     * @return Tool 定义（包含 name / description / parameters schema）
     */
    Tool getToolDefinition(String toolName);
}
