package com.devcli.cli;

import com.devcli.agent.Agent;
import com.devcli.agent.AgentOrchestrator;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.mcp.McpServerManager;
import com.devcli.memory.RuleContext;
import com.devcli.runtime.event.RunEventSink;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;

import java.io.PrintStream;

/**
 * 负责单次 Plan/Team 编排器的创建、上下文装配与 run/resume 分发。
 */
final class OrchestrationTaskRunner {

    private final DevCliConfig config;
    private final Agent reactAgent;
    private final McpServerManager mcpServerManager;
    private final RuleContext ruleContext;
    private final SkillRegistry skillRegistry;
    private final SkillContextBuffer skillContextBuffer;
    private final AgentOrchestrator.TeamPlanReviewHandler planReviewHandler;
    private final RunEventSink eventSink;
    private final PrintStream out;

    OrchestrationTaskRunner(DevCliConfig config,
                            Agent reactAgent,
                            McpServerManager mcpServerManager,
                            RuleContext ruleContext,
                            SkillRegistry skillRegistry,
                            SkillContextBuffer skillContextBuffer,
                            AgentOrchestrator.TeamPlanReviewHandler planReviewHandler,
                            RunEventSink eventSink,
                            PrintStream out) {
        this.config = config;
        this.reactAgent = reactAgent;
        this.mcpServerManager = mcpServerManager;
        this.ruleContext = ruleContext;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.planReviewHandler = planReviewHandler;
        this.eventSink = eventSink == null ? RunEventSink.NO_OP : eventSink;
        this.out = out == null ? System.out : out;
    }

    String run(LlmClient llmClient, String taskInput) {
        out.println("📋 使用 Plan 模式\n");
        LlmClient reviewerClient = LlmClientFactory.createTeamReviewer(config, llmClient);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, reviewerClient, reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(), out);
        orchestrator.setPlanReviewHandler(planReviewHandler);
        orchestrator.setAdditionalEventSink(eventSink);
        orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
        orchestrator.setRuleContextSupplier(ruleContext::renderForPrompt);
        orchestrator.setSkillSystem(skillRegistry, skillContextBuffer);
        String resumeId = parseResumeId(taskInput);
        return resumeId == null
                ? orchestrator.run(taskInput)
                : orchestrator.resume(resumeId.isBlank() ? null : resumeId);
    }

    static String parseResumeId(String taskInput) {
        if (taskInput == null) {
            return null;
        }
        String trimmed = taskInput.trim();
        if (trimmed.equalsIgnoreCase("resume")) {
            return "";
        }
        if (trimmed.regionMatches(true, 0, "resume ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return null;
    }
}
