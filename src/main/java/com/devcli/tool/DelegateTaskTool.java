package com.devcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** 主 Agent 的运行级委派入口；子工作区不继承 handler。 */
public final class DelegateTaskTool {
    public static final String NAME = "delegate_task";

    private DelegateTaskTool() { }

    @FunctionalInterface
    public interface Handler {
        ToolOutput execute(Map<String, String> arguments, ToolExecutionContext context);
    }

    static ToolRegistry.Tool definition(ToolRegistry registry) {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("role").put("type", "string").putArray("enum")
                .add("explorer").add("planner").add("worker").add("reviewer");
        properties.putObject("task").put("type", "string").put("minLength", 1)
                .put("maxLength", 16000).put("description", "明确的子任务、范围和完成条件");
        properties.putObject("context").put("type", "string").put("maxLength", 16000)
                .put("description", "可选的必要背景与相关文件；不会自动复制父会话历史");
        properties.putObject("upstream_report_id").put("type", "string").put("maxLength", 128)
                .put("description", "可选的上游结构化报告 ID；由程序原样注入，不要复制报告正文");
        properties.putObject("deliverable").put("type", "string").put("maxLength", 8000)
                .put("description", "子任务必须交付的结果");
        properties.putObject("constraints").put("type", "array").put("maxItems", 32)
                .putObject("items").put("type", "string").put("maxLength", 1000);
        properties.putObject("entry_points").put("type", "array").put("maxItems", 64)
                .putObject("items").put("type", "string").put("maxLength", 500);
        properties.putObject("allowed_tools").put("type", "array").put("maxItems", 32)
                .put("description", "工具偏好；仍受执行范围与 Skill 权限约束")
                .putObject("items").put("type", "string").put("maxLength", 128);
        properties.putObject("allowed_write_paths").put("type", "array").put("maxItems", 128)
                .put("description", "Worker 可修改的项目相对路径 glob；为空表示不额外限制")
                .putObject("items").put("type", "string").put("maxLength", 500);
        ObjectNode budget = properties.putObject("budget").put("type", "object")
                .put("additionalProperties", false);
        ObjectNode budgetProperties = budget.putObject("properties");
        budgetProperties.putObject("max_iterations").put("type", "integer").put("minimum", 1).put("maximum", 100);
        schema.putArray("required").add("role").add("task");
        return ToolRegistry.Tool.contextualStructured(NAME,
                "按需委派一个独立子任务。explorer/planner/reviewer 只读；worker 在隔离工作区修改，"
                        + "成功后按版本检查归并。子 Agent 不能再委派。简单任务直接执行，不必委派。",
                schema, registry::executeDelegation, com.devcli.config.ConfigResolver.intValue(
                        "devcli.delegate.timeout.seconds", "DEVCLI_DELEGATE_TIMEOUT_SECONDS", 300, 1, 3600));
    }
}
