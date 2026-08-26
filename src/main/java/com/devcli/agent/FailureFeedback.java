package com.devcli.agent;

import com.devcli.runtime.event.RunEvent;

import java.util.List;
import java.util.Locale;

/** 统一承载失败原因、分类、操作建议和恢复动作。 */
final class FailureFeedback {

    enum Category {
        VALIDATION_ERROR("校验错误", "按失败清单逐项修正，并先运行对应校验后重试"),
        RESOURCE_CONFLICT("资源冲突", "等待占用释放，或拆分、更换目标资源后重试"),
        STALE_CONTEXT("上下文过期", "重新读取受影响文件或符号，再基于最新内容重做"),
        BUDGET_EXHAUSTED("预算耗尽", "缩小任务范围、明确优先级或补充关键上下文后重试"),
        ENVIRONMENT_FAILURE("环境故障", "确认依赖、权限和服务状态，恢复环境后重试"),
        TASK_AMBIGUITY("任务歧义", "补充目标、边界、输入输出和验收标准后重新规划"),
        EXECUTION_FAILURE("执行失败", "根据最后失败原因修正最小阻塞点后重试");

        private final String label;
        private final String suggestion;

        Category(String label, String suggestion) {
            this.label = label;
            this.suggestion = suggestion;
        }
    }

    enum ActionType {
        RETRY,
        MANUAL_TAKEOVER,
        ACCEPT_PARTIAL,
        ROLLBACK
    }

    record Action(ActionType type, String label, String instruction) {
    }

    private final String reason;
    private final Category category;
    private final String suggestion;
    private final List<Action> actions;

    private FailureFeedback(String reason, Category category, String retryInstruction) {
        this.reason = normalizeReason(reason);
        this.category = category == null ? Category.EXECUTION_FAILURE : category;
        this.suggestion = this.category.suggestion;
        String retry = retryInstruction == null || retryInstruction.isBlank()
                ? "按操作建议修正后重新提交当前任务"
                : retryInstruction.trim();
        this.actions = List.of(
                new Action(ActionType.RETRY, "重试", retry),
                new Action(ActionType.MANUAL_TAKEOVER, "人工接手",
                        "根据失败原因和已有产物人工修复，再运行对应验证"),
                new Action(ActionType.ACCEPT_PARTIAL, "接受部分结果",
                        "确认保留已完成结果，并明确终止未完成部分"),
                new Action(ActionType.ROLLBACK, "回滚",
                        "如存在 Side-Git 快照，使用 `/restore <N>` 或 `revert_turn` 恢复"));
    }

    static FailureFeedback fromReason(String reason) {
        return new FailureFeedback(reason, classify(reason), "");
    }

    static FailureFeedback forBudget(AgentBudget.ExitReason exitReason, AgentBudget budget) {
        AgentBudget currentBudget = budget == null ? AgentBudget.fromSystemProperties() : budget;
        AgentBudget.ExitReason reason = exitReason == null
                ? AgentBudget.ExitReason.HARD_ITERATION_LIMIT
                : exitReason;
        return new FailureFeedback(
                currentBudget.describeExit(reason),
                Category.BUDGET_EXHAUSTED,
                "缩小任务范围、明确优先级或补充关键上下文后重新提交");
    }

    FailureFeedback withRetryInstruction(String retryInstruction) {
        return new FailureFeedback(reason, category, retryInstruction);
    }

    Category category() {
        return category;
    }

    String render() {
        StringBuilder rendered = new StringBuilder()
                .append("失败原因：").append(reason).append('\n')
                .append("失败分类：").append(category.label).append('\n')
                .append("操作建议：").append(suggestion).append('\n')
                .append("下一步动作：\n");
        for (Action action : actions) {
            rendered.append("- ").append(action.label()).append("：")
                    .append(action.instruction()).append('\n');
        }
        return rendered.toString().stripTrailing();
    }

    RunEvent.FailureGuidance toRunEvent() {
        return new RunEvent.FailureGuidance(
                category.name(),
                reason,
                suggestion,
                actions.stream()
                        .map(action -> new RunEvent.FailureAction(
                                action.type().name(), action.label(), action.instruction()))
                        .toList());
    }

    private static Category classify(String reason) {
        String normalized = normalizeReason(reason).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "任务边界不明确", "需求不明确", "目标不明确", "任务歧义",
                "缺少验收标准", "ambiguous", "ambiguity")) {
            return Category.TASK_AMBIGUITY;
        }
        if (containsAny(normalized, "stale_context", "上下文过期", "上下文版本", "依赖符号已经变化")) {
            return Category.STALE_CONTEXT;
        }
        if (containsAny(normalized, "资源租约", "资源冲突", "resource_conflict", "被占用", "锁冲突")) {
            return Category.RESOURCE_CONFLICT;
        }
        if (containsAny(normalized, "预算", "轮数上限", "重复的工具", "工具错误", "疑似死循环")) {
            return Category.BUDGET_EXHAUSTED;
        }
        if (containsAny(normalized, "docker", "环境不可用", "环境故障", "网络", "连接", "超时",
                "认证", "限流", "服务端", "llm 调用失败")) {
            return Category.ENVIRONMENT_FAILURE;
        }
        if (containsAny(normalized, "校验", "验证", "参数", "schema", "编译失败", "测试失败", "未满足")) {
            return Category.VALIDATION_ERROR;
        }
        return Category.EXECUTION_FAILURE;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "未提供失败原因" : reason.trim();
    }
}
