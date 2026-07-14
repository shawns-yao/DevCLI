package com.devcli.agent;

import java.util.Objects;

/** Multi-Agent Worker 的执行协议守卫。 */
final class TeamWorkerProtocol {
    static final int MAX_EMPTY_RESULT_REPAIRS = 1;

    private TeamWorkerProtocol() {
    }

    static boolean needsMandatoryToolRepair(AgentMessage result, SubAgent.ExecutionEvidence evidence) {
        if (result == null || result.type() == AgentMessage.Type.ERROR) {
            return false;
        }
        if (result.content() != null && !result.content().isBlank()) {
            return false;
        }
        return evidence == null || !evidence.hasSuccessfulToolCall();
    }

    static String buildMandatoryToolContext(String existingContext, String stepDescription, int attempt) {
        String base = existingContext == null ? "" : existingContext.trim();
        String repair = """
                [Worker 执行协议修复]
                上一次 Worker 未产生可验收结果：最终 content 为空，并且没有成功工具证据。
                这不是规划轮次。不要继续解释准备怎么做，不要输出未来时计划。
                当前轮必须先调用第一个具体工具，再根据工具结果继续执行。
                - 文件或代码实现任务：必须调用 write_file 产生真实修改，再调用 execute_command 做最小验证。
                - 读取或分析任务：必须调用 read_file、list_dir、grep_code 或 search_code 获取真实证据。
                - 工具失败时根据结构化错误修正参数；无法完成时返回明确错误，不能以空 content 结束。
                完成后输出 changed_files、verification、acceptance_criteria、remaining_risk。

                当前步骤：
                %s

                协议修复次数：%d
                """.formatted(Objects.toString(stepDescription, ""), attempt);
        return base.isBlank() ? repair : base + "\n\n" + repair;
    }
}
