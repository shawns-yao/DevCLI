package com.devcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.function.Function;

/** 主 Agent 的工作计划与受限记忆读取入口；不开放父会话状态给委派角色。 */
final class AgentContextTools {
    private Function<Map<String, String>, ToolOutput> planUpdater;
    private Function<Map<String, String>, ToolOutput> memoryReader;

    void bind(Function<Map<String, String>, ToolOutput> planUpdater,
              Function<Map<String, String>, ToolOutput> memoryReader) {
        this.planUpdater = planUpdater;
        this.memoryReader = memoryReader;
    }

    void copyHandlersFrom(AgentContextTools source) { bind(source.planUpdater, source.memoryReader); }

    void register(ToolRegistry registry) {
        ObjectNode plan = schema();
        var properties = plan.putObject("properties");
        properties.putObject("goal").put("type", "string").put("minLength", 1).put("maxLength", 1000);
        var steps = properties.putObject("steps").put("type", "array").put("minItems", 1).put("maxItems", 64);
        var item = steps.putObject("items").put("type", "object").put("additionalProperties", false);
        item.putArray("required").add("step").add("status");
        item.putObject("properties").putObject("step").put("type", "string").put("minLength", 1).put("maxLength", 300);
        item.withObject("properties").putObject("status").put("type", "string").putArray("enum")
                .add("pending").add("in_progress").add("completed");
        plan.putArray("required").add("goal").add("steps");
        registry.registerTool(ToolContract.Tool.structured("update_plan",
                "原子更新当前工作计划。用 pending/in_progress/completed 表示步骤，最多一个进行中步骤；"
                        + "当前工作和下一步由这份计划确定，压缩后原样恢复。完成标记不替代真实验证。",
                plan, args -> invoke(planUpdater, args)));

        ObjectNode memory = schema();
        var fields = memory.putObject("properties");
        fields.putObject("name").put("type", "string").put("minLength", 1).put("maxLength", 256)
                .put("description", "记忆索引中的主题文件名，不接受路径");
        fields.putObject("scope").put("type", "string").putArray("enum").add("global").add("project");
        fields.putObject("offset").put("type", "integer").put("minimum", 0);
        fields.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 20000);
        memory.putArray("required").add("name");
        registry.registerTool(ToolContract.Tool.structured("read_memory",
                "按记忆索引中的主题名读取有效长期记忆。默认项目优先，可指定 scope；"
                        + "返回正文与 next_offset，较长主题可继续读取，不开放任意文件路径。",
                memory, args -> invoke(memoryReader, args)));
    }

    private static ObjectNode schema() {
        return new ObjectMapper().createObjectNode().put("type", "object").put("additionalProperties", false);
    }

    private static ToolOutput invoke(Function<Map<String, String>, ToolOutput> handler, Map<String, String> args) {
        return handler == null ? ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "主 Agent 上下文未绑定", false)
                : handler.apply(args);
    }
}
