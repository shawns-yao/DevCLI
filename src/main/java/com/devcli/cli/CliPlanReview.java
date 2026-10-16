package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentOrchestrator;
import com.devcli.agent.PlanExecuteAgent;
import com.devcli.cli.turn.TerminalInputCoordinator;
import com.devcli.llm.LlmClient;
import com.devcli.plan.ExecutionPlan;
import org.jline.terminal.Terminal;
import org.jline.reader.LineReader;
import java.io.PrintStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.cli.CliApplication.*;
import static com.devcli.cli.TerminalInputSupport.*;
import static com.devcli.cli.CliTurnRunner.*;
import static com.devcli.cli.CliSettings.*;
import static com.devcli.cli.CliDisplay.*;

/** 计划确认交互及输入决策映射。 */
final class CliPlanReview {
    private static final Logger log = LoggerFactory.getLogger(CliPlanReview.class);

    static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                            PlanExecuteAgent.PlanReviewHandler reviewHandler) {
        return new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                reviewHandler,
                System.out
        );
    }

    static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                                    Terminal terminal, LineReader lineReader, PrintStream out) {
        out.println("📋 使用 Plan-and-Execute 模式\n");
        return new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                createPlanReviewHandler(terminal, lineReader, out),
                out
        );
    }

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

    static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandler(Terminal terminal,
                                                                              LineReader lineReader,
                                                                              PrintStream out) {
        return (String goal, ExecutionPlan plan) -> {
            boolean expanded = false;
            out.println(plan.summarize());
            out.println("📝 计划已生成。");
            out.println("   - 回车：按当前计划执行");
            out.println("   - Ctrl+O：展开完整计划");
            out.println("   - ESC：折叠或取消本次计划");
            out.println("   - I：输入补充要求后重新规划\n");

            while (true) {
                KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
                if (keyReadResult.ignoredControlSequence()) {
                    continue;
                }

                Integer key = keyReadResult.key();
                if (key != null) {
                    // Enter
                    if (key == '\n' || key == '\r') {
                        out.println();
                        return PlanExecuteAgent.PlanReviewDecision.execute();
                    }

                    // ESC (27)
                    if (key == 27) {
                        out.println();
                        if (expanded) {
                            expanded = false;
                            out.println(plan.summarize());
                            out.println("📁 已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                            continue;
                        }
                        return PlanExecuteAgent.PlanReviewDecision.cancel();
                    }

                    // I 或 i
                    if (key == 'i' || key == 'I') {
                        out.println();
                        String supplementInput = lineReader.readLine("补充> ").trim();
                        PlanReviewInputParser.Decision supplementDecision =
                                PlanReviewInputParser.parse(supplementInput);
                        return mapReviewDecision(supplementDecision);
                    }

                    // Ctrl+O
                    if (key == CTRL_O) {
                        out.println();
                        out.println(plan.visualize());
                        expanded = true;
                        out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }

                    out.println();
                    out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                // 如果无法读取单键，回退到行输入模式
                String decisionInput = lineReader.readLine("操作/补充> ").trim();
                if (decisionInput.equalsIgnoreCase("/view")) {
                    out.println();
                    out.println(plan.visualize());
                    expanded = true;
                    out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                    continue;
                }
                PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(decisionInput);
                return mapReviewDecision(decision);
            }
        };
    }

    static PlanExecuteAgent.PlanReviewDecision mapReviewDecision(PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
            case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
        };
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
