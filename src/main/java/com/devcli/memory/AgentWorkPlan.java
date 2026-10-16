package com.devcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Agent 主动维护的工作意图；序列化值仅存于 SessionMemory，不替代真实执行证据。 */
public record AgentWorkPlan(String goal, List<Step> steps) {
    public static final String STATE_KEY = "agent_work_plan";
    public static final String OPEN = "<agent_work_plan>\n";
    public static final String CLOSE = "\n</agent_work_plan>";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Step(String step, String status) {
        public Step {
            if (step == null || step.isBlank() || step.length() > 300) {
                throw new IllegalArgumentException("步骤内容必须为 1–300 字符");
            }
            if (!List.of("pending", "in_progress", "completed").contains(status)) {
                throw new IllegalArgumentException("步骤状态只允许 pending/in_progress/completed");
            }
        }
    }

    public AgentWorkPlan {
        if (goal == null || goal.isBlank() || goal.length() > 1000) {
            throw new IllegalArgumentException("目标必须为 1–1000 字符");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > 64) throw new IllegalArgumentException("工作计划需要 1–64 个步骤");
        if (steps.stream().filter(step -> step.status().equals("in_progress")).count() > 1) {
            throw new IllegalArgumentException("同一工作计划最多一个 in_progress 步骤");
        }
    }

    public static AgentWorkPlan parse(String goal, String steps) {
        try {
            return new AgentWorkPlan(goal, JSON.readerForListOf(Step.class).readValue(steps));
        } catch (Exception e) {
            throw new IllegalArgumentException("工作计划无效: " + e.getMessage(), e);
        }
    }

    public String serialized() {
        return JSON.valueToTree(this).toString();
    }

    public static String render(String serialized) {
        return serialized == null || serialized.isBlank() ? "" : OPEN + serialized + CLOSE;
    }

    /** 只还原工作意图，不产生授权、工具成功或验收结论。 */
    public static String extract(String text) {
        if (text == null) return "";
        int start = text.indexOf(OPEN);
        int end = start < 0 ? -1 : text.indexOf(CLOSE, start + OPEN.length());
        if (end < 0) return "";
        try {
            return JSON.readValue(text.substring(start + OPEN.length(), end), AgentWorkPlan.class).serialized();
        } catch (Exception e) { return ""; }
    }
}
