package com.devcli.agent;

import com.devcli.config.ConfigResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.plan.ExecutionArtifact;
import com.devcli.plan.ExecutionNode;
import com.devcli.runtime.CancellationContext;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import com.devcli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Agent 编排器 - Multi-Agent 系统的"主"
 *
 * 负责管理团队、分配任务、路由消息、解决冲突。
 * 采用主从架构：编排器是主，子代理是从。
 *
 * 协作流程：
 * 1. 用户提交任务 -> 编排器交给规划者
 * 2. 规划者拆解任务 -> 编排器解析计划
 * 3. 编排器按依赖顺序将子任务分配给执行者
 * 4. 执行者返回结果 -> 编排器交给检查者
 * 5. 检查者通过则完成，否则带上反馈重新分配给执行者
 * 6. 所有子任务完成后，编排器汇总返回最终结果
 *
 * 并行策略：
 * - 同一依赖批次内部 **并行** 执行（最多 Worker 池大小并发，默认 2）
 * - 多步批次委托 {@link MultiAgentBatchExecutor} 做资源分波、独立输出缓冲与稳定顺序归并
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过公平锁串行化同一实例的任务，避免对话历史竞争
 * - Reviewer 在并行路径中按步骤即时创建独立实例
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    /** Worker 默认数量；可经 -Ddevcli.team.workers / DEVCLI_TEAM_WORKERS 覆盖。 */
    static final int DEFAULT_WORKER_COUNT = 2;
    /** Worker 数量保护上限：过多并发 Worker 会放大 LLM 限流与终端输出竞争。 */
    static final int MAX_WORKER_COUNT = OrchestrationProfile.TEAM.maxParallelism();
    private static final int SUBAGENT_CONTEXT_SCHEMA_VERSION = 1;
    /**
     * 失败步骤的在位重做上限。失败步骤保持原 id/依赖在 DAG 原位换思路重做，而非生成平行恢复计划——
     * 恢复始终长在原 DAG 上、通过依赖关系看到已完成成果，从机制上消除"平行计划 vs 已落盘成果"冲突。
     */
    private static final int MAX_REDO_PER_STEP = 1;
    private static final int MAX_PLAN_REVIEW_REVISIONS = 3;
    private final LlmClient llmClient;
    private final LlmClient reviewerLlmClient;
    private final SubAgent planner;
    private List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final ReviewCoordinator reviewCoordinator;
    private final CheckpointCoordinator checkpointCoordinator;
    private final PlanCoordinator planCoordinator;
    private final OrchestrationNarrative orchestrationNarrative;
    private final StepExecutionCoordinator stepExecutionCoordinator;
    private final PrintStream out;
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> ruleContextSupplier = () -> "";
    private volatile com.devcli.runtime.event.RunEventSink additionalEventSink =
            com.devcli.runtime.event.RunEventSink.NO_OP;
    private com.devcli.skill.SkillRegistry skillRegistry;
    private com.devcli.skill.SkillContextBuffer skillContextBuffer;
    private final TraceRecorder traceRecorder = new TraceRecorder();
    private final OrchestrationRunState runState = new OrchestrationRunState(MAX_REDO_PER_STEP);
    private TeamPlanReviewHandler planReviewHandler = request -> request.requiresHumanReview()
            ? TeamPlanReviewDecision.cancel("计划包含人工验收标准，需要人工确认后才能执行")
            : TeamPlanReviewDecision.execute();
    private boolean planSemanticReviewEnabled = ConfigResolver.booleanValue(
            "devcli.team.plan.review.enabled", "DEVCLI_TEAM_PLAN_REVIEW_ENABLED", true);
    private final WorkspaceCommitCoordinator workspaceCommitCoordinator =
            new WorkspaceCommitCoordinator();

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                         List<String> dependencies, ExecutionArtifact artifact)
            implements ExecutionNode {
        ExecutionStep {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            artifact = artifact == null ? ExecutionArtifact.pending(id) : artifact;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public List<String> dependencies() {
            return dependencies;
        }

        @Override
        public ExecutionArtifact artifact() {
            return artifact;
        }

        ExecutionStep(String id, String description, String type, List<String> dependencies,
                      String result, StepStatus status, List<String> modifiedFiles) {
            this(id, description, type, dependencies,
                    legacyArtifact(id, result, status, modifiedFiles));
        }

        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, ExecutionArtifact.pending(id));
        }

        String result() {
            return artifact.output().isBlank() ? artifact.summary() : artifact.output();
        }

        StepStatus status() {
            return switch (artifact.state()) {
                case PENDING -> StepStatus.PENDING;
                case RUNNING -> StepStatus.RUNNING;
                case COMPLETED -> StepStatus.COMPLETED;
                case FAILED -> StepStatus.FAILED;
            };
        }

        List<String> modifiedFiles() {
            return artifact.modifiedResources();
        }

        ExecutionStep withResult(String result) {
            return withResult(result, result);
        }

        ExecutionStep withResult(String result, String trustedSummary) {
            return new ExecutionStep(id, description, type, dependencies,
                    artifact.complete(result, trustedSummary, artifact.modifiedResources(),
                            System.currentTimeMillis()));
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies,
                    artifact.fail(result, result, artifact.modifiedResources(), System.currentTimeMillis()));
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies,
                    artifact.start(System.currentTimeMillis()));
        }

        ExecutionStep withRedoPending() {
            return new ExecutionStep(id, description, type, dependencies, artifact.resetForRetry());
        }

        ExecutionStep withModifiedFiles(List<String> modifiedFiles) {
            return new ExecutionStep(id, description, type, dependencies,
                    artifact.withModifiedResources(modifiedFiles));
        }

        private static ExecutionArtifact legacyArtifact(String id, String result, StepStatus status,
                                                        List<String> modifiedFiles) {
            List<String> resources = modifiedFiles == null ? List.of() : modifiedFiles;
            String text = result == null ? "" : result;
            return switch (status == null ? StepStatus.PENDING : status) {
                case PENDING -> ExecutionArtifact.pending(id).withModifiedResources(resources);
                case RUNNING -> ExecutionArtifact.pending(id).start(System.currentTimeMillis())
                        .withOutput(text).withSummary(text).withModifiedResources(resources);
                case COMPLETED -> ExecutionArtifact.completed(id, text, text, resources);
                case FAILED -> ExecutionArtifact.failed(id, text, text, resources);
            };
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public interface TeamPlanReviewHandler {
        TeamPlanReviewDecision review(TeamPlanReviewRequest request);
    }

    public enum TeamPlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record AcceptanceCriterionView(
            String id,
            String description,
            String verificationMethod,
            String verifier,
            String testSignal,
            String severity,
            List<String> appliesTo
    ) {
        public AcceptanceCriterionView {
            appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
        }
    }

    public record TeamPlanReviewRequest(
            String goal,
            String planSummary,
            List<AcceptanceCriterionView> criteria,
            boolean requiresHumanReview,
            boolean semanticReviewExecuted,
            boolean semanticReviewApproved,
            String semanticReviewSummary
    ) {
        public TeamPlanReviewRequest {
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
            semanticReviewSummary = semanticReviewSummary == null ? "" : semanticReviewSummary.trim();
        }

        public TeamPlanReviewRequest(String goal, String planSummary,
                                     List<AcceptanceCriterionView> criteria,
                                     boolean requiresHumanReview) {
            this(goal, planSummary, criteria, requiresHumanReview, false, true, "");
        }

        public TeamPlanReviewRequest(String goal, String planSummary,
                                     List<AcceptanceCriterionView> criteria,
                                     boolean requiresHumanReview,
                                     boolean semanticReviewExecuted,
                                     String semanticReviewSummary) {
            this(goal, planSummary, criteria, requiresHumanReview,
                    semanticReviewExecuted, true, semanticReviewSummary);
        }
    }

    public record TeamPlanReviewDecision(TeamPlanReviewAction action, String feedback) {
        public static TeamPlanReviewDecision execute() {
            return new TeamPlanReviewDecision(TeamPlanReviewAction.EXECUTE, "");
        }

        public static TeamPlanReviewDecision supplement(String feedback) {
            return new TeamPlanReviewDecision(TeamPlanReviewAction.SUPPLEMENT,
                    feedback == null ? "" : feedback.trim());
        }

        public static TeamPlanReviewDecision cancel(String reason) {
            return new TeamPlanReviewDecision(TeamPlanReviewAction.CANCEL,
                    reason == null ? "" : reason.trim());
        }
    }

    public AgentOrchestrator(LlmClient llmClient) {
        this(llmClient, new ToolRegistry(), new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, new MemoryManager(llmClient));
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out);
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry,
                             MemoryManager memoryManager, PrintStream out) {
        this(llmClient, llmClient, toolRegistry, memoryManager, out);
    }

    public AgentOrchestrator(LlmClient llmClient, LlmClient reviewerLlmClient,
                             ToolRegistry toolRegistry, MemoryManager memoryManager, PrintStream out) {
        this.llmClient = llmClient;
        this.reviewerLlmClient = reviewerLlmClient == null ? llmClient : reviewerLlmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.checkpointCoordinator = new CheckpointCoordinator(
                runState, this.memoryManager, workspaceCommitCoordinator);
        this.reviewCoordinator = new ReviewCoordinator(
                this.memoryManager,
                this::activeToolRegistry,
                runState::userTask,
                runState::acceptanceCriteria,
                AgentOrchestrator::isFinalIntegrationStep,
                new ReviewCoordinator.Journal() {
                    @Override
                    public void recordMessage(String agentId, String stepId,
                                              String phase, AgentMessage message) {
                        recordAgentMessage(agentId, stepId, phase, message);
                    }

                    @Override
                    public void recordEvent(String agentId, String stepId,
                                            String phase, String summary) {
                        recordAgentEvent(agentId, stepId, phase, summary);
                    }
                });
        AgentRuntimeSupport.bindMemory(this.toolRegistry, this.memoryManager);
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = buildWorkers(resolveWorkerCount(), llmClient, toolRegistry);
        this.reviewer = new SubAgent(
                "reviewer", AgentRole.REVIEWER, this.reviewerLlmClient, toolRegistry);
        this.planCoordinator = new PlanCoordinator(
                planner, reviewer, toolRegistry, runState, this.out, planSemanticReviewEnabled);
        this.orchestrationNarrative = new OrchestrationNarrative(runState, reviewCoordinator);
        this.stepExecutionCoordinator = new StepExecutionCoordinator(
                this.out, () -> workers, reviewer, llmClient, this.reviewerLlmClient,
                toolRegistry, memoryManager, traceRecorder, runState, planCoordinator,
                reviewCoordinator, checkpointCoordinator,
                new StepExecutionCoordinator.Hooks(
                        this::configureSubAgent,
                        AgentOrchestrator::applyRecoveryContext,
                        orchestrationNarrative::buildStepContext,
                        orchestrationNarrative::buildFinalResult));
        configureSubAgent(planner);
        workers.forEach(this::configureSubAgent);
        configureSubAgent(reviewer);
    }

    /**
     * 解析 Worker 数量：系统属性 {@code devcli.team.workers} 优先，其次环境变量
     * {@code DEVCLI_TEAM_WORKERS}，缺省 {@link #DEFAULT_WORKER_COUNT}；显式非法值直接拒绝。
     */
    static int resolveWorkerCount() {
        return ConfigResolver.intValue(
                "devcli.team.workers", "DEVCLI_TEAM_WORKERS",
                DEFAULT_WORKER_COUNT, 1, MAX_WORKER_COUNT);
    }

    private static List<SubAgent> buildWorkers(int count, LlmClient llmClient, ToolRegistry toolRegistry) {
        List<String> names = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            names.add("worker-" + i);
        }
        return buildWorkers(names, llmClient, toolRegistry);
    }

    private static List<SubAgent> buildWorkers(List<String> names, LlmClient llmClient,
                                               ToolRegistry toolRegistry) {
        List<SubAgent> built = new ArrayList<>(names.size());
        for (String name : names) {
            built.add(new SubAgent(name, AgentRole.WORKER, llmClient, toolRegistry));
        }
        return List.copyOf(built);
    }

    private List<AgentCheckpoint.AgentIdentityRecord> currentAgentIdentities() {
        long now = System.currentTimeMillis();
        List<AgentCheckpoint.AgentIdentityRecord> identities = new ArrayList<>();
        identities.add(agentIdentity(planner, now));
        workers.forEach(worker -> identities.add(agentIdentity(worker, now)));
        identities.add(agentIdentity(reviewer, now));
        return List.copyOf(identities);
    }

    private static AgentCheckpoint.AgentIdentityRecord agentIdentity(SubAgent agent, long now) {
        return new AgentCheckpoint.AgentIdentityRecord(
                agent.getName(), agent.getRole().name(), agent.getName(),
                SUBAGENT_CONTEXT_SCHEMA_VERSION, now, now);
    }

    private AgentCheckpoint.RecoveryState restoreAgentTopology(AgentCheckpoint loaded) {
        AgentCheckpoint.RecoveryState recovery = loaded.recoveryState();
        if (recovery.agentIdentities().isEmpty()) {
            loaded.ensureAgentIdentities(currentAgentIdentities());
            saveCheckpointStrict();
            recovery = loaded.recoveryState();
        }
        List<AgentCheckpoint.AgentIdentityRecord> identities = recovery.agentIdentities();
        long planners = identities.stream()
                .filter(identity -> "PLANNER".equalsIgnoreCase(identity.role()))
                .filter(identity -> planner.getName().equals(identity.agentId()))
                .count();
        long reviewers = identities.stream()
                .filter(identity -> "REVIEWER".equalsIgnoreCase(identity.role()))
                .filter(identity -> reviewer.getName().equals(identity.agentId()))
                .count();
        List<String> workerIds = identities.stream()
                .filter(identity -> "WORKER".equalsIgnoreCase(identity.role()))
                .map(AgentCheckpoint.AgentIdentityRecord::agentId)
                .toList();
        if (planners != 1 || reviewers != 1 || workerIds.isEmpty()
                || workerIds.size() > MAX_WORKER_COUNT) {
            throw new IllegalStateException("checkpoint 子代理身份拓扑无效");
        }
        if (new HashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalStateException("checkpoint Worker 身份重复");
        }
        List<String> currentWorkerIds = workers.stream().map(SubAgent::getName).toList();
        if (!currentWorkerIds.equals(workerIds)) {
            workers = buildWorkers(workerIds, llmClient, toolRegistry);
            workers.forEach(this::configureSubAgent);
        }
        loaded.ensureAgentIdentities(currentAgentIdentities());
        AgentCheckpoint.RecoveryState restoredRecovery = recovery;
        applyRecoveryContext(planner, restoredRecovery);
        workers.forEach(worker -> applyRecoveryContext(worker, restoredRecovery));
        applyRecoveryContext(reviewer, restoredRecovery);
        return restoredRecovery;
    }

    private static void applyRecoveryContext(SubAgent agent, AgentCheckpoint.RecoveryState recovery) {
        AgentCheckpoint.AgentIdentityRecord identity = recovery.agentIdentities().stream()
                .filter(candidate -> candidate.agentId().equals(agent.getName()))
                .findFirst()
                .orElse(null);
        AgentCheckpoint.AgentCursorRecord cursor = recovery.agentCursors().get(agent.getName());
        if (identity == null
                || identity.contextSchemaVersion() != SUBAGENT_CONTEXT_SCHEMA_VERSION
                || cursor == null || cursor.lastMessageSeq() <= 0) {
            agent.setRecoveryContext("");
            return;
        }
        StringBuilder context = new StringBuilder()
                .append("身份: ").append(agent.getName())
                .append("\n消息游标: ").append(cursor.lastMessageSeq())
                .append("\n约束: 最近摘要只用于恢复上下文，步骤终态以 ExecutionArtifact 为准");
        if (!cursor.lastStepId().isBlank()) {
            context.append("\n最近步骤: ").append(cursor.lastStepId());
        }
        if (!cursor.summary().isBlank()) {
            context.append("\n最近摘要: ").append(cursor.summary());
        }
        agent.setRecoveryContext(context.toString());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
        planner.setExternalContextSupplier(this.externalContextSupplier);
        workers.forEach(worker -> worker.setExternalContextSupplier(this.externalContextSupplier));
        reviewer.setExternalContextSupplier(this.externalContextSupplier);
    }

    public void setPlanReviewHandler(TeamPlanReviewHandler planReviewHandler) {
        if (planReviewHandler != null) {
            this.planReviewHandler = planReviewHandler;
        }
    }

    void setPlanSemanticReviewEnabled(boolean enabled) {
        this.planSemanticReviewEnabled = enabled;
        this.planCoordinator.setSemanticReviewEnabled(enabled);
    }

    /**
     * 注入 Sticky Memory（PR-B）：把 supplier 同时下发到 planner / workers / reviewer，
     * 让团队三角色都看到统一的稳定事实层。
     */
    public void setRuleContextSupplier(Supplier<String> ruleContextSupplier) {
        this.ruleContextSupplier = ruleContextSupplier == null ? () -> "" : ruleContextSupplier;
        memoryManager.setRuleContextSupplier(this.ruleContextSupplier);
        planner.setRuleContextSupplier(this.ruleContextSupplier);
        workers.forEach(worker -> worker.setRuleContextSupplier(this.ruleContextSupplier));
        reviewer.setRuleContextSupplier(this.ruleContextSupplier);
    }

    /** @deprecated 使用 {@link #setRuleContextSupplier(Supplier)}。 */
    @Deprecated
    public void setStickyMemorySupplier(Supplier<String> supplier) { setRuleContextSupplier(supplier); }

    /**
     * 把 Skill 系统下发给所有 SubAgent。Multi-Agent 三个角色共享同一 SkillRegistry（索引一致），
     * 每个角色拿到 SkillContextBuffer 的独立副本，避免并行 Worker / Reviewer 互相消费 skill body。
     * SubAgent 调用 load_skill 时会通过 ToolRegistry 的线程本地覆盖写回自己的 buffer。
     */
    void setPreReviewVerifier(PreReviewVerifier preReviewVerifier) {
        reviewCoordinator.setPreReviewVerifier(
                Objects.requireNonNull(preReviewVerifier, "preReviewVerifier"));
    }

    void setRequireWorkerToolEvidence(boolean requireWorkerToolEvidence) {
        this.stepExecutionCoordinator.setRequireWorkerToolEvidence(requireWorkerToolEvidence);
    }


    public void setSkillSystem(com.devcli.skill.SkillRegistry skillRegistry,
                               com.devcli.skill.SkillContextBuffer skillContextBuffer) {
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        configureSubAgent(planner);
        workers.forEach(this::configureSubAgent);
        configureSubAgent(reviewer);
    }

    private void configureSubAgent(SubAgent agent) {
        agent.setExternalContextSupplier(externalContextSupplier);
        agent.setRuleContextSupplier(ruleContextSupplier);
        agent.setMemoryContextSupplier(() -> memoryManager.buildContextForQuery(
                "multi-agent " + agent.getRole().name().toLowerCase(Locale.ROOT),
                memoryManager.getContextProfile().memoryContextTokens()));
        agent.setSessionMemorySupplier(() -> memoryManager.buildSessionMemorySectionForAgent(
                agent.getRole().name().toLowerCase(Locale.ROOT)));
        agent.setPostCompactRestoreSupplier(() -> memoryManager.buildPostCompactRestoreSectionForAgent(
                agent.getRole().name().toLowerCase(Locale.ROOT)));
        agent.setStructuredToolResultConsumer(memoryManager::addToolResult);
        agent.setPostToolInstructionSupplier(memoryManager::drainCurrentStateConflictInstruction);
        agent.setSkillRegistry(skillRegistry);
        agent.setSkillContextBuffer(skillContextBuffer == null ? null : skillContextBuffer.copy());
        agent.setAdditionalEventSink(additionalEventSink);
    }

    /**
     * 注入额外结构化事件出口（如 Execution Trace 落盘），透传给全部 Planner/Worker/Reviewer；
     * 与各 SubAgent 自身流式渲染 sink 并列，不替代渲染。
     */
    public void setAdditionalEventSink(com.devcli.runtime.event.RunEventSink sink) {
        this.additionalEventSink = sink == null
                ? com.devcli.runtime.event.RunEventSink.NO_OP : sink;
        planner.setAdditionalEventSink(this.additionalEventSink);
        workers.forEach(worker -> worker.setAdditionalEventSink(this.additionalEventSink));
        reviewer.setAdditionalEventSink(this.additionalEventSink);
    }

    private record PlanGenerationResult(AgentMessage message, List<ExecutionStep> steps,
                                        TeamPlanReviewProtocol.Evaluation semanticReview,
                                        String failureReason) {
        private PlanGenerationResult {
            steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
            semanticReview = semanticReview == null
                    ? TeamPlanReviewProtocol.Evaluation.skipped() : semanticReview;
            failureReason = failureReason == null ? "" : failureReason.trim();
        }
    }

    private PlanGenerationResult requestValidatedPlan(String userInput) {
        PlanCoordinator.GenerationResult result = planCoordinator.requestValidatedPlan(userInput);
        return new PlanGenerationResult(result.message(), result.steps(), result.semanticReview(),
                result.failureReason());
    }

    private TeamPlanReviewProtocol.Evaluation reviewGeneratedPlan(
            String userInput, List<ExecutionStep> steps) {
        return planCoordinator.reviewGeneratedPlan(userInput, steps);
    }

    /**
     * 运行统一 Plan 任务；串行或并行由 DAG 就绪节点决定。
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        String sessionTaskId = "plan-run-" + UUID.randomUUID().toString().substring(0, 8);
        memoryManager.beginTask(sessionTaskId);
        memoryManager.setActiveProjectScope(toolRegistry.getProjectPath());
        TraceContext traceContext = TraceContext.root("plan");
        traceRecorder.record(traceContext, "run.start", Map.of(
                "inputChars", userInput == null ? 0 : userInput.length(),
                "workers", workers.size()
        ));
        memoryManager.addUserMessage(userInput);
        runState.beginNew(userInput);
        toolRegistry.prefetchToolDefinitionsForInput(runState.userTask());
        // 回收上一轮崩溃残留的超时租约，避免历史租约阻塞本轮写入
        toolRegistry.pruneExpiredLeases();
        memoryManager.setTaskState("context_epoch",
                Long.toString(toolRegistry.contextVersionLedger().currentGeneration()));
        String finalResultForSummary = "";
        try {
            if (CancellationContext.isCancelled()) {
                finalResultForSummary = "⏹️ 已取消当前 Plan 任务。";
                return finalResultForSummary;
            }
            // 1. 规划阶段：让规划者拆解任务
            out.println(AnsiStyle.heading("📋 第一阶段：规划"));
            out.println("🧑‍💼 规划者正在分析任务...\n");

            PlanGenerationResult planGeneration = requestValidatedPlan(userInput);
            AgentMessage planResult = planGeneration.message();
            if (CancellationContext.isCancelled()) {
                finalResultForSummary = "⏹️ 已取消当前 Plan 任务。";
                return finalResultForSummary;
            }

            if (planResult.type() == AgentMessage.Type.ERROR) {
                finalResultForSummary = "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
                return finalResultForSummary;
            }

            // 2. 解析计划（requestValidatedPlan 已完成协议校验和超步数粗化）
            List<ExecutionStep> steps = planGeneration.steps();
            if (steps.isEmpty()) {
                String reason = planGeneration.failureReason().isBlank()
                        ? "无法解析执行计划" : planGeneration.failureReason();
                finalResultForSummary = "❌ 规划失败：" + reason + "\n原始输出:\n"
                        + Objects.toString(planResult.content(), "");
                return finalResultForSummary;
            }

            int reviewRevision = 0;
            while (true) {
                AcceptanceCriteriaPreflight.Report preflight = acceptanceCriteriaPreflight();
                TeamPlanReviewDecision decision = planReviewHandler.review(
                        buildTeamPlanReviewRequest(runState.userTask(), steps, preflight,
                                planGeneration.semanticReview()));
                if (decision == null || decision.action() == TeamPlanReviewAction.EXECUTE) {
                    break;
                }
                if (decision.action() == TeamPlanReviewAction.CANCEL) {
                    String reason = decision.feedback().isBlank()
                            ? "已取消本次 Plan 执行"
                            : decision.feedback();
                    finalResultForSummary = "⏹️ " + reason;
                    return finalResultForSummary;
                }
                if (decision.feedback().isBlank() || reviewRevision >= MAX_PLAN_REVIEW_REVISIONS) {
                    finalResultForSummary = "❌ 执行前评审未形成可执行决策";
                    return finalResultForSummary;
                }
                reviewRevision++;
                runState.appendUserTaskRequirement(decision.feedback());
                planGeneration = requestValidatedPlan(runState.userTask());
                planResult = planGeneration.message();
                steps = planGeneration.steps();
                if (planResult.type() == AgentMessage.Type.ERROR || steps.isEmpty()) {
                    finalResultForSummary = "❌ 规划失败：执行前评审修订未生成可执行计划";
                    return finalResultForSummary;
                }
            }
            steps = appendFinalIntegrationStep(steps);
            checkpointCoordinator.create(
                    "orch-" + UUID.randomUUID().toString().substring(0, 8),
                    runState.userTask(),
                    steps,
                    runState.acceptanceCriteria(),
                    currentAgentIdentities(),
                    planner.getName(),
                    "计划已生成：" + steps.size() + " 个步骤，"
                            + runState.acceptanceCriteria().size() + " 条验收标准");

            out.println(AnsiStyle.heading("📋 执行计划"));
            out.println(summarizeSteps(steps) + "\n");

            finalResultForSummary = executeSteps(steps, traceContext);
            return finalResultForSummary;
        } finally {
            memoryManager.completeTask(sessionTaskId, userInput, finalResultForSummary,
                    toolRegistry.getProjectPath());
            memoryManager.endTask(sessionTaskId);
            scheduleSessionPreSummaryMaintenance(userInput, finalResultForSummary);
        }
    }

    private TeamPlanReviewRequest buildTeamPlanReviewRequest(
            String goal, List<ExecutionStep> steps, AcceptanceCriteriaPreflight.Report preflight) {
        return buildTeamPlanReviewRequest(
                goal, steps, preflight, TeamPlanReviewProtocol.Evaluation.skipped());
    }

    private TeamPlanReviewRequest buildTeamPlanReviewRequest(
            String goal, List<ExecutionStep> steps, AcceptanceCriteriaPreflight.Report preflight,
            TeamPlanReviewProtocol.Evaluation semanticReview) {
        List<AcceptanceCriterionView> criteria = runState.acceptanceCriteria().stream()
                .map(criterion -> new AcceptanceCriterionView(
                        criterion.id(),
                        criterion.description(),
                        criterion.verificationMethod() == null ? "" : criterion.verificationMethod().name(),
                        criterion.verifier(),
                        criterion.testSignal(),
                        criterion.severity(),
                        criterion.appliesTo()))
                .toList();
        return new TeamPlanReviewRequest(
                goal,
                summarizeSteps(steps),
                criteria,
                preflight.requiresHumanReview(),
                planSemanticReviewEnabled,
                semanticReview.approved(),
                semanticReviewSummary(semanticReview));
    }

    private String semanticReviewSummary(TeamPlanReviewProtocol.Evaluation semanticReview) {
        String summary = semanticReview.summary();
        if (semanticReview.approved() || semanticReview.issues().isBlank()) {
            return summary;
        }
        return summary.isBlank()
                ? semanticReview.issues()
                : summary + "；" + semanticReview.issues();
    }

    private void scheduleSessionPreSummaryMaintenance(String userInput, String result) {
        List<LlmClient.Message> turnHistory = new ArrayList<>();
        turnHistory.add(LlmClient.Message.system("TEAM_TURN"));
        turnHistory.add(LlmClient.Message.user(userInput == null ? "" : userInput));
        if (result != null && !result.isBlank()) {
            turnHistory.add(LlmClient.Message.assistant(result));
        }
        memoryManager.maintainSessionPreSummaryAfterTurnAsync(turnHistory, 0, 0);
    }

    /**
     * 从磁盘 checkpoint 恢复执行（/plan resume 入口）。
     *
     * <p>恢复范围：计划（步骤/依赖/验收点）与进度（已完成步骤带回完整 result 与产物文件，
     * 其余——包括上次失败的、被阻塞的——重置为 PENDING 重新执行）。
     * <b>不恢复</b> SessionMemory / 会话记忆：Worker 上下文完全来自 checkpoint 内的步骤 result。
     *
     * @param orchestrationIdOrNull 指定 checkpoint id；为空时取最近一次保存的 checkpoint
     */
    public String resume(String orchestrationIdOrNull) {
        Path projectRoot = Path.of(toolRegistry.getProjectPath());
        CheckpointCoordinator.OpenResult openResult =
                checkpointCoordinator.openForResume(orchestrationIdOrNull, projectRoot);
        if (!openResult.opened()) {
            return openResult.error();
        }
        AgentCheckpoint loaded = openResult.checkpoint();
        int loadedProtocolVersion = loaded.getProtocolVersion();
        AgentCheckpoint.RecoveryState recovery;
        try {
            recovery = restoreAgentTopology(loaded);
        } catch (RuntimeException e) {
            runState.clearCheckpoint();
            return "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] 子代理身份恢复失败：" + e.getMessage();
        }
        if (recovery.planSteps().isEmpty()) {
            return "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] 缺少计划数据（旧格式落盘），无法恢复；请重新发起 /plan 任务。";
        }
        long completedCount = recovery.artifacts().values().stream()
                .filter(ExecutionArtifact::successful)
                .count();
        log.info("Multi-Agent resume started: checkpoint={}, protocol={}, completed={}/{}",
                loaded.getOrchestrationId(), recovery.protocolVersion(),
                completedCount, recovery.planSteps().size());
        TraceContext traceContext = TraceContext.root("plan-resume");
        traceRecorder.record(traceContext, "resume.start", Map.of(
                "checkpoint", loaded.getOrchestrationId(),
                "completedSteps", completedCount,
                "planSteps", recovery.planSteps().size()
        ));

        toolRegistry.pruneExpiredLeases();
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前 Plan 任务。";
        }

        CheckpointCoordinator.RestoredPlan restoredPlan = checkpointCoordinator.restorePlan(
                recovery, loadedProtocolVersion < 6);
        List<ExecutionStep> steps = restoredPlan.steps();
        memoryManager.addUserMessage(runState.userTask());
        boolean hasPendingSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.PENDING);
        if (hasPendingSteps) {
            AcceptanceCriteriaPreflight.Report preflight = acceptanceCriteriaPreflight();
            if (!preflight.executable()) {
                return "❌ checkpoint [" + loaded.getOrchestrationId()
                        + "] 缺少可执行的验收标准，不能继续恢复：" + preflight.describeIssues()
                        + "。请重新发起 /plan 任务。";
            }
            TeamPlanReviewProtocol.Evaluation semanticReview = reviewGeneratedPlan(
                    runState.userTask(), steps.stream()
                            .filter(step -> !isFinalIntegrationStep(step))
                            .toList());
            if (!semanticReview.protocolValid() || !semanticReview.approved()) {
                out.println("⚠️ checkpoint [" + loaded.getOrchestrationId()
                        + "] 计划语义评审给出建议，恢复继续："
                        + semanticReviewSummary(semanticReview) + "\n");
            }
            if (loadedProtocolVersion < 6) {
                TeamPlanReviewDecision decision = planReviewHandler.review(
                        buildTeamPlanReviewRequest(runState.userTask(), steps, preflight));
                if (decision == null || decision.action() == TeamPlanReviewAction.EXECUTE) {
                    // 旧协议验收字段已安全迁移并由调用方确认，继续恢复。
                } else if (decision.action() == TeamPlanReviewAction.CANCEL) {
                    String reason = decision.feedback().isBlank()
                            ? "已取消旧 checkpoint 的恢复执行"
                            : decision.feedback();
                    return "⏹️ " + reason;
                } else {
                    return "⏹️ 旧 checkpoint 的计划不能原位补充；请根据反馈重新发起 /plan 任务："
                            + decision.feedback();
                }
            }
        }
        restoreCheckpointArtifactsIntoSessionMemory(recovery);

        out.println(AnsiStyle.heading("🔁 恢复执行 checkpoint [" + loaded.getOrchestrationId() + "]"
                + "（已完成 " + completedCount + "/" + steps.size() + " 步）"));
        out.println(summarizeSteps(steps) + "\n");

        return executeSteps(steps, traceContext);
    }

    /** 兼容旧调用入口，恢复语义统一委托给结构化协议。 */
    private List<ExecutionStep> rebuildStepsFromCheckpoint(AgentCheckpoint checkpoint) {
        return checkpointCoordinator.rebuildSteps(checkpoint);
    }

    /** checkpoint 计划层 + 结构化产物 → 可调度的步骤列表。 */
    private List<ExecutionStep> rebuildStepsFromCheckpoint(AgentCheckpoint.RecoveryState recovery) {
        return checkpointCoordinator.rebuildSteps(recovery);
    }

    private void restoreCheckpointArtifactsIntoSessionMemory(AgentCheckpoint.RecoveryState recovery) {
        runState.restoreCheckpointArtifacts(recovery);
        for (Map.Entry<String, ExecutionArtifact> entry : recovery.artifacts().entrySet()) {
            String source = entry.getValue().successful()
                    ? "checkpoint 已完成步骤"
                    : "checkpoint 失败步骤";
            addStepModifiedFilesFact(
                    entry.getKey(), entry.getValue().modifiedResources(), source);
        }
    }

    private void addStepModifiedFilesFact(String stepId, List<String> modifiedFiles, String source) {
        if (modifiedFiles != null && !modifiedFiles.isEmpty()) {
            memoryManager.addVolatileFact(source + " [" + stepId + "] 修改文件: "
                    + String.join(", ", modifiedFiles));
        }
    }

    /**
     * 执行阶段共享循环：依赖调度（单步串行 / 多步冲突分波并行）、失败有界重规划、
     * 残留步骤提示、最终汇总与 checkpoint 收尾。run() 与 resume() 共用。
     */
    private String executeSteps(List<ExecutionStep> steps, TraceContext traceContext) {
        return stepExecutionCoordinator.execute(steps, traceContext);
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        return planCoordinator.parsePlan(planJson);
    }

    List<AcceptanceCriterion> parseAcceptanceCriteria(JsonNode criteriaNode) {
        return planCoordinator.parseAcceptanceCriteria(criteriaNode);
    }

    AcceptanceCriteriaPreflight.Report acceptanceCriteriaPreflight() {
        return planCoordinator.acceptanceCriteriaPreflight();
    }

    List<ExecutionStep> coarsenPlanIfNeeded(List<ExecutionStep> steps) {
        return planCoordinator.coarsenPlanIfNeeded(steps);
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        return planCoordinator.getExecutableSteps(steps);
    }

    boolean shouldFuseFinalIntegration(List<ExecutionStep> steps) {
        return planCoordinator.shouldFuseFinalIntegration(steps);
    }

    List<ExecutionStep> appendFinalIntegrationStep(List<ExecutionStep> steps) {
        return planCoordinator.appendFinalIntegrationStep(steps);
    }

    static boolean isFinalIntegrationStep(ExecutionStep step) {
        String id = step.id() == null ? "" : step.id().toLowerCase(Locale.ROOT);
        String type = step.type() == null ? "" : step.type().toLowerCase(Locale.ROOT);
        String description = step.description() == null ? "" : step.description().toLowerCase(Locale.ROOT);
        return id.contains("final_integration")
                || description.contains("最终集成")
                || type.equals("integration")
                || type.equals("final_integration");
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        return reviewCoordinator.parseApproval(reviewContent);
    }

    /**
     * 解析检查者反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        return reviewCoordinator.parseIssues(reviewContent);
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /**
     * 获取工具注册表（用于同步项目路径）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private ToolRegistry activeToolRegistry() {
        return stepExecutionCoordinator.activeToolRegistry();
    }

    private synchronized void saveCheckpointStrict() {
        checkpointCoordinator.saveStrict();
    }

    private synchronized SubAgent resolveAssignedWorker(String stepId, int preferredIndex) {
        return stepExecutionCoordinator.resolveAssignedWorker(stepId, preferredIndex);
    }

    private synchronized void recordAgentMessage(String agentId, String stepId,
                                                 String phase, AgentMessage message) {
        checkpointCoordinator.recordAgentMessage(agentId, stepId, phase, message);
    }

    private synchronized void recordAgentEvent(String agentId, String stepId,
                                               String phase, String summary) {
        checkpointCoordinator.recordAgentEvent(agentId, stepId, phase, summary);
    }

    static String resolveWorkerResultContent(
            String content, SubAgent.ExecutionEvidence evidence) {
        return StepExecutionCoordinator.resolveWorkerResultContent(content, evidence);
    }

    List<String> missingDeclaredVerifierTools(List<String> reviewToolCalls) {
        return reviewCoordinator.missingDeclaredVerifierTools(reviewToolCalls);
    }

    ReviewCoordinator.PreReviewResult runPreReviewHook(ExecutionStep step) {
        return reviewCoordinator.runPreReviewHook(step);
    }

    private boolean requiresJavaHardCheck(ExecutionStep step) {
        return reviewCoordinator.requiresJavaHardCheck(step);
    }

    String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        return orchestrationNarrative.buildStepContext(steps, currentStep);
    }

    private static String previewDependencyResult(String result) {
        return OrchestrationNarrative.previewDependencyResult(result);
    }

    List<AcceptanceCriterion> acceptanceCriteriaForStep(ExecutionStep step) {
        return reviewCoordinator.acceptanceCriteriaForStep(step);
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        return planCoordinator.summarizeSteps(steps);
    }

    static String formatFailureEscalation(List<ExecutionStep> steps,
                                          Map<String, Integer> reviewerRetries,
                                          Map<String, Integer> redoCounts,
                                          String orchestrationId) {
        return OrchestrationNarrative.formatFailureEscalation(
                steps, reviewerRetries, redoCounts, orchestrationId);
    }

    String pendingHumanAcceptanceSummary() {
        return orchestrationNarrative.pendingHumanAcceptanceSummary();
    }

}
