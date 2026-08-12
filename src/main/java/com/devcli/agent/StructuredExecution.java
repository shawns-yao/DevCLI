package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.ToolRegistry;

import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Plan 与 Team 的统一产品入口。
 *
 * <p>现阶段复用两套已经稳定的策略适配器：PLAN_REVIEW 使用单执行者 DAG，
 * TEAM_REVIEW 使用 Worker、Pre-Review、Reviewer。调用方只理解审查策略，
 * 不再直接选择编排实现。</p>
 */
public final class StructuredExecution {
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final PrintStream out;
    private final PlanExecuteAgent.PlanReviewHandler planReviewHandler;
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> stickyMemorySupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;

    public StructuredExecution(LlmClient llmClient,
                               ToolRegistry toolRegistry,
                               MemoryManager memoryManager,
                               PlanExecuteAgent.PlanReviewHandler planReviewHandler,
                               PrintStream out) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.planReviewHandler = planReviewHandler == null
                ? (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
                : planReviewHandler;
        this.out = out == null ? System.out : out;
    }

    public StructuredExecution setExternalContextSupplier(Supplier<String> supplier) {
        externalContextSupplier = supplier == null ? () -> "" : supplier;
        return this;
    }

    public StructuredExecution setStickyMemorySupplier(Supplier<String> supplier) {
        stickyMemorySupplier = supplier == null ? () -> "" : supplier;
        return this;
    }

    public StructuredExecution setSkillSystem(SkillRegistry registry, SkillContextBuffer buffer) {
        skillRegistry = registry;
        skillContextBuffer = buffer;
        return this;
    }

    public String run(ExecutionReviewPolicy policy, String task) {
        ExecutionReviewPolicy effective = Objects.requireNonNull(policy, "policy");
        return switch (effective) {
            case PLAN_REVIEW -> runPlan(task);
            case TEAM_REVIEW -> runTeam(task);
        };
    }

    public String resume(ExecutionReviewPolicy policy, String checkpointId) {
        ExecutionReviewPolicy effective = Objects.requireNonNull(policy, "policy");
        if (effective != ExecutionReviewPolicy.TEAM_REVIEW) {
            throw new IllegalArgumentException("只有 team review 支持 checkpoint 恢复");
        }
        return teamAdapter().resume(checkpointId);
    }

    private String runPlan(String task) {
        PlanExecuteAgent adapter = new PlanExecuteAgent(
                llmClient, toolRegistry, memoryManager, planReviewHandler, out);
        adapter.setExternalContextSupplier(externalContextSupplier);
        adapter.setStickyMemorySupplier(stickyMemorySupplier);
        adapter.setSkillRegistry(skillRegistry);
        adapter.setSkillContextBuffer(skillContextBuffer);
        return adapter.run(task);
    }

    private String runTeam(String task) {
        return teamAdapter().run(task);
    }

    private AgentOrchestrator teamAdapter() {
        AgentOrchestrator adapter = new AgentOrchestrator(
                llmClient, toolRegistry, memoryManager, out);
        adapter.setExternalContextSupplier(externalContextSupplier);
        adapter.setStickyMemorySupplier(stickyMemorySupplier);
        adapter.setSkillSystem(skillRegistry, skillContextBuffer);
        return adapter;
    }
}
