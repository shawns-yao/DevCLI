package com.devcli.cli;

import com.devcli.agent.AgentOrchestrator;
import com.devcli.cli.turn.TerminalInputCoordinator;
import java.io.PrintStream;
import org.jline.reader.LineReader;

/** 统一计划编排的确认交互与输入决策映射。 */
final class CliPlanReview {
    static AgentOrchestrator.TeamPlanReviewHandler createTeamPlanReviewHandler(
            LineReader lineReader, PrintStream out, TerminalInputCoordinator terminalInput) {
        return request -> terminalInput.interact(() -> {
            out.println(formatTeamPlanReview(request));
            out.println("   - 回车：按当前计划执行");
            out.println("   - 输入补充要求：重新规划后再次评审");
            out.println("   - /cancel：取消本次计划\n");
            String input = lineReader.readLine("Plan 确认> ");
            return mapTeamReviewDecision(PlanReviewInputParser.parse(input));
        });
    }

    static String formatTeamPlanReview(AgentOrchestrator.TeamPlanReviewRequest request) {
        StringBuilder text = new StringBuilder("📋 Plan 执行前评审\n")
                .append(request.planSummary())
                .append("\n验收标准：\n");
        for (AgentOrchestrator.AcceptanceCriterionView criterion : request.criteria()) {
            text.append("- ").append(criterion.id())
                    .append(" [").append(criterion.verificationMethod()).append("] ")
                    .append(criterion.description())
                    .append("\n  验证者：").append(criterion.verifier())
                    .append("\n  通过证据：").append(criterion.testSignal())
                    .append("\n  作用节点：").append(criterion.appliesTo())
                    .append('\n');
        }
        if (request.requiresHumanReview()) {
            text.append("⚠️ 当前计划包含需要人工验收的标准，执行前必须明确确认。\n");
        }
        if (request.semanticReviewExecuted()) {
            text.append(request.semanticReviewApproved()
                            ? "✅ Reviewer 计划语义评审："
                            : "⚠️ Reviewer 计划语义评审建议（不阻断）：")
                    .append(request.semanticReviewSummary().isBlank()
                            ? (request.semanticReviewApproved() ? "已通过" : "未提供有效结论")
                            : request.semanticReviewSummary())
                    .append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 解析 Plan 的 resume 子命令：
     * "resume" → ""（恢复最近 checkpoint）；"resume orch-xxxx" → "orch-xxxx"；
     * 其他输入 → null（按普通任务文本走 run）。
     */
    static String parsePlanResumeId(String taskInput) {
        return OrchestrationTaskRunner.parseResumeId(taskInput);
    }

    static AgentOrchestrator.TeamPlanReviewDecision mapTeamReviewDecision(
            PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> AgentOrchestrator.TeamPlanReviewDecision.execute();
            case CANCEL -> AgentOrchestrator.TeamPlanReviewDecision.cancel("用户取消本次 Plan");
            case SUPPLEMENT -> AgentOrchestrator.TeamPlanReviewDecision.supplement(decision.feedback());
        };
    }
}
