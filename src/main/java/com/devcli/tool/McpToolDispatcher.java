package com.devcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 稳定的模型工具入口；动态 MCP 定义经 search_tools 返回到消息正文。 */
final class McpToolDispatcher {
    static final String NAME = "call_mcp_tool";

    private McpToolDispatcher() { }

    static void register(ToolRegistry registry) {
        var schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object").put("additionalProperties", false);
        var properties = schema.putObject("properties");
        properties.putObject("tool_name").put("type", "string").put("minLength", 1)
                .put("description", "search_tools 返回的完整 MCP 工具名");
        properties.putObject("arguments").put("type", "object")
                .put("description", "严格符合搜索结果 input_schema 的参数对象");
        schema.putArray("required").add("tool_name").add("arguments");
        registry.registerTool(ToolContract.Tool.contextualStructured(NAME,
                "调用 search_tools 已发现的 MCP 工具。传入完整工具名和 arguments；"
                        + "目标调用仍需通过权限、Skill、参数、人工审批、策略和审计。",
                schema, (args, context) -> registry.dispatchMcpTool(args.get("tool_name"),
                        args.get("arguments"), context), -1));
    }
}
