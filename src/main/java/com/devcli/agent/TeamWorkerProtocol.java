package com.devcli.agent;

import com.devcli.llm.LlmClient;

import java.util.Locale;
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

    static LlmClient.ToolChoice requiredToolChoice(String stepType) {
        String normalized = normalizeStepType(stepType);
        if (normalized.contains("WRITE") || normalized.contains("INTEGRATION")) {
            return LlmClient.ToolChoice.required("write_file");
        }
        if (normalized.contains("COMMAND")) {
            return LlmClient.ToolChoice.required("execute_command");
        }
        return LlmClient.ToolChoice.required("list_dir");
    }

    static String completionToolName(String stepType, LlmClient.ToolChoice toolChoice) {
        if (toolChoice != null && toolChoice.hasSpecificTool()) {
            return toolChoice.toolName();
        }
        String normalized = normalizeStepType(stepType);
        return normalized.contains("WRITE") || normalized.contains("INTEGRATION")
                ? "write_file"
                : "";
    }

    private static String normalizeStepType(String stepType) {
        return Objects.toString(stepType, "").toUpperCase(Locale.ROOT);
    }

    static String buildMandatoryToolTask(
            String stepDescription, int attempt, String toolName) {
        return """
                原始步骤：
                %s

                [Worker 执行协议修复]
                上一次 Worker 未产生可验收结果：最终 content 为空，并且没有成功工具证据。
                这不是规划轮次。禁止复述需求、设计方案、伪代码或未来时计划。
                本次响应的第一个动作必须是工具调用，不允许先输出 reasoning 或 content。
                - 文件或代码实现任务：立即调用 write_file 产生真实修改，再调用 execute_command 做最小验证。
                - 读取或分析任务：立即调用 read_file、list_dir、grep_code 或 search_code 获取真实证据。
                - 工具失败时根据结构化错误修正参数；无法完成时返回明确错误，不能以空 content 结束。
                - 如果 Provider 未生成原生工具调用，只输出以下严格 JSON，不要输出 Markdown、代码围栏或解释：
                %s
                完成后输出 changed_files、verification、acceptance_criteria、remaining_risk。
                协议修复次数：%d

                现在立即调用工具。
                """.formatted(
                Objects.toString(stepDescription, ""), toolEnvelopeTemplate(toolName), attempt);
    }

    static String buildToolEnvelopeRepairPrompt(String toolName) {
        return "Provider 未生成原生工具调用。只输出严格 JSON，不要输出 Markdown、代码围栏或解释：\n"
                + toolEnvelopeTemplate(toolName);
    }

    private static String toolEnvelopeTemplate(String toolName) {
        return switch (Objects.toString(toolName, "")) {
            case "write_file" -> "{\"name\":\"write_file\",\"arguments\":{\"path\":\"真实项目相对路径\",\"content\":\"完整文件内容\"}}";
            case "execute_command" -> "{\"name\":\"execute_command\",\"arguments\":{\"command\":\"实际命令\"}}";
            default -> "{\"name\":\"" + Objects.toString(toolName, "list_dir")
                    + "\",\"arguments\":{\"path\":\".\"}}";
        };
    }
}
