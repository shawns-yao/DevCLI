package com.devcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.plan.ExecutionArtifact;
import com.devcli.plan.ExecutionGraph;
import com.devcli.plan.ResourceConflictDetector;
import com.devcli.runtime.CancellationContext;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import com.devcli.util.AnsiStyle;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
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
 * - 每个并行步骤使用独立的 PrintStream 缓冲流式输出，批次结束后按 step_id 顺序 flush 到 stdout，
 *   避免多线程写同一个终端流造成交错，同时仍让用户看到结构化的执行过程
 * - 单步批次仍走直连流式路径，保持"实时打字"的观感
 * - Worker 通过 {@link java.util.concurrent.BlockingQueue} 池化分配，确保同一 Worker 不会被两个步骤并发占用
 * - Reviewer 在并行路径中按步骤即时创建独立实例，避免对话历史竞争
 */
public class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RETRIES_PER_STEP = 2;
    /** Worker 默认数量；可经 -Ddevcli.team.workers / DEVCLI_TEAM_WORKERS 覆盖。 */
    static final int DEFAULT_WORKER_COUNT = 2;
    /** Worker 数量保护上限：过多并发 Worker 会放大 LLM 限流与终端输出竞争。 */
    static final int MAX_WORKER_COUNT = 8;
    private static final int SUBAGENT_CONTEXT_SCHEMA_VERSION = 1;
    private static final double MIN_REVIEW_SCORE = 0.6;
    private static final double REQUIRED_FUNCTIONAL_SCORE = 1.0;
    private static final double FINAL_INTEGRATION_FAILURE_RATIO_LIMIT = 0.5;
    private static final int MAX_PLANNER_STEPS = 5;
    /**
     * 失败步骤的在位重做上限。失败步骤保持原 id/依赖在 DAG 原位换思路重做，而非生成平行恢复计划——
     * 恢复始终长在原 DAG 上、通过依赖关系看到已完成成果，从机制上消除"平行计划 vs 已落盘成果"冲突。
     */
    private static final int MAX_REDO_PER_STEP = 1;
    /**
     * Reviewer 输出 JSON 解析失败时的否定语义识别。
     * 覆盖"未通过/不通过/未全部通过/没有通过/未能通过"等变体，
     * 避免"测试未全部通过"因含"通过"二字被误判为批准。
     */
    private static final java.util.regex.Pattern NEGATIVE_REVIEW_PATTERN =
            java.util.regex.Pattern.compile("[未没不][^。\\n]{0,6}通过");

    private final LlmClient llmClient;
    private final SubAgent planner;
    private List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private String currentUserTask = "";
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> stickyMemorySupplier = () -> "";
    private com.devcli.skill.SkillRegistry skillRegistry;
    private com.devcli.skill.SkillContextBuffer skillContextBuffer;
    private final TraceRecorder traceRecorder = new TraceRecorder();
    private List<AcceptanceCriterion> currentAcceptanceCriteria = List.of();
    /** 当前 run 的进度 checkpoint：步骤完成/失败即落盘，全部成功后删除。崩溃后可凭文件做事后排查。 */
    private AgentCheckpoint checkpoint;
    /** 失败步骤在位重做的状态与决策（计数 + 上次失败原因），与调度循环解耦，见 {@link StepRedoTracker}。 */
    private final StepRedoTracker redoTracker = new StepRedoTracker(MAX_REDO_PER_STEP);
    /** resume 时从 checkpoint 载入的失败步骤产物（stepId → 已写文件 + 失败摘要），注入重做上下文；run() 新任务清空。 */
    private Map<String, ExecutionArtifact> restoredFailedArtifacts = new HashMap<>();
    private final ThreadLocal<ToolRegistry> activeStepToolRegistry = new ThreadLocal<>();
    private final ThreadLocal<StepUpdateBuffer> activeStepUpdate = new ThreadLocal<>();
    private PreReviewVerifier preReviewVerifier = new PreReviewVerifier();
    private boolean requireWorkerToolEvidence;
    private final WorkspaceCommitCoordinator workspaceCommitCoordinator =
            new WorkspaceCommitCoordinator();

    private static final class StepUpdateBuffer {
        private final String stepId;
        private ExecutionStep updated;

        private StepUpdateBuffer(String stepId) {
            this.stepId = stepId;
        }
    }

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                         List<String> dependencies, ExecutionArtifact artifact) {
        ExecutionStep {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            artifact = artifact == null ? ExecutionArtifact.pending(id) : artifact;
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
            return new ExecutionStep(id, description, type, dependencies,
                    artifact.complete(result, result, artifact.modifiedResources(), System.currentTimeMillis()));
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

    record PreReviewResult(boolean passed, boolean hardCheckExecuted, String feedback) {
        static PreReviewResult skipped() {
            return new PreReviewResult(true, false, "");
        }

        static PreReviewResult failed(String feedback) {
            return new PreReviewResult(false, true,
                    feedback == null ? "Pre-review hard check failed" : feedback);
        }
    }

    record AcceptanceCriterion(String id, String category, String description, String testSignal, String severity) {
        boolean isValid() {
            return !id.isBlank() && !description.isBlank();
        }

        String formatForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("- ").append(id);
            if (!category.isBlank()) {
                sb.append(" [").append(category).append("]");
            }
            if (!severity.isBlank()) {
                sb.append(" severity=").append(severity);
            }
            sb.append(": ").append(description);
            if (!testSignal.isBlank()) {
                sb.append("；test_signal: ").append(testSignal);
            }
            return sb.toString();
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
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setMemorySaver(this.memoryManager::storeFact);
        this.toolRegistry.setMemorySaveHandler(fact -> {
            MemoryManager.StoreResult result = this.memoryManager.storeFactWithPolicy(fact, true);
            return new ToolRegistry.MemorySaveResult(result.stored(), result.message());
        });
        this.toolRegistry.setMemoryListHandler(this.memoryManager::listLongTermMemory);
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = buildWorkers(resolveWorkerCount(), llmClient, toolRegistry);
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
        configureSubAgent(planner);
        workers.forEach(this::configureSubAgent);
        configureSubAgent(reviewer);
    }

    /**
     * 解析 Worker 数量：系统属性 {@code devcli.team.workers} 优先，其次环境变量
     * {@code DEVCLI_TEAM_WORKERS}，缺省 {@link #DEFAULT_WORKER_COUNT}，并夹在 [1, {@link #MAX_WORKER_COUNT}]。
     */
    static int resolveWorkerCount() {
        String raw = System.getProperty("devcli.team.workers");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("DEVCLI_TEAM_WORKERS");
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WORKER_COUNT;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            int clamped = Math.max(1, Math.min(MAX_WORKER_COUNT, n));
            if (clamped != n) {
                log.warn("devcli.team.workers={} 超出范围 [1,{}]，已夹取为 {}", n, MAX_WORKER_COUNT, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            log.warn("非法 devcli.team.workers={}，使用默认 {}", raw, DEFAULT_WORKER_COUNT);
            return DEFAULT_WORKER_COUNT;
        }
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

    /**
     * 注入 Sticky Memory（PR-B）：把 supplier 同时下发到 planner / workers / reviewer，
     * 让团队三角色都看到统一的稳定事实层。
     */
    public void setStickyMemorySupplier(Supplier<String> stickyMemorySupplier) {
        this.stickyMemorySupplier = stickyMemorySupplier == null ? () -> "" : stickyMemorySupplier;
        planner.setStickyMemorySupplier(this.stickyMemorySupplier);
        workers.forEach(worker -> worker.setStickyMemorySupplier(this.stickyMemorySupplier));
        reviewer.setStickyMemorySupplier(this.stickyMemorySupplier);
    }

    /**
     * 把 Skill 系统下发给所有 SubAgent。Multi-Agent 三个角色共享同一 SkillRegistry（索引一致），
     * 每个角色拿到 SkillContextBuffer 的独立副本，避免并行 Worker / Reviewer 互相消费 skill body。
     * SubAgent 调用 load_skill 时会通过 ToolRegistry 的线程本地覆盖写回自己的 buffer。
     */
    void setPreReviewVerifier(PreReviewVerifier preReviewVerifier) {
        this.preReviewVerifier = Objects.requireNonNull(preReviewVerifier, "preReviewVerifier");
    }

    void setRequireWorkerToolEvidence(boolean requireWorkerToolEvidence) {
        this.requireWorkerToolEvidence = requireWorkerToolEvidence;
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
        agent.setStickyMemorySupplier(stickyMemorySupplier);
        agent.setMemoryContextSupplier(() -> memoryManager.buildContextForQuery(
                "multi-agent " + agent.getRole().name().toLowerCase(Locale.ROOT),
                memoryManager.getContextProfile().memoryContextTokens()));
        agent.setWorkingMemorySupplier(() -> memoryManager.buildWorkingMemorySectionForAgent(
                agent.getRole().name().toLowerCase(Locale.ROOT)));
        agent.setPostCompactRestoreSupplier(() -> memoryManager.buildPostCompactRestoreSectionForAgent(
                agent.getRole().name().toLowerCase(Locale.ROOT)));
        agent.setStructuredToolResultConsumer(result -> memoryManager.addToolResult(
                result.name(), result.argumentsJson(), result.result(), result.sideChannels()));
        agent.setSkillRegistry(skillRegistry);
        agent.setSkillContextBuffer(skillContextBuffer == null ? null : skillContextBuffer.copy());
    }

    private record PlanGenerationResult(AgentMessage message, List<ExecutionStep> steps) {
        private PlanGenerationResult {
            steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
        }
    }

    private PlanGenerationResult requestValidatedPlan(String userInput) {
        AgentMessage result = executePlanner(AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + Objects.toString(userInput, "")));
        if (result.type() == AgentMessage.Type.ERROR) {
            return new PlanGenerationResult(result, List.of());
        }

        List<ExecutionStep> steps = parsePlan(result.content());
        String planIssue = TeamPlannerProtocol.validate(
                steps, userInput, ExecutionStep::id, ExecutionStep::description, ExecutionStep::dependencies);
        int maxRepairAttempts = TeamPlannerProtocol.resolveRepairAttempts();
        for (int attempt = 1; planIssue != null && attempt <= maxRepairAttempts; attempt++) {
            if (CancellationContext.isCancelled()) {
                break;
            }
            out.println("⚠️ 规划输出未通过协议或结构校验，正在请求修复 (" + attempt
                    + "/" + maxRepairAttempts + ")...\n");
            result = executePlanner(AgentMessage.task("orchestrator",
                    TeamPlannerProtocol.buildRepairPrompt(
                            userInput, result.content(), planIssue, attempt)));
            if (result.type() == AgentMessage.Type.ERROR) {
                return new PlanGenerationResult(result, List.of());
            }
            steps = parsePlan(result.content());
            planIssue = TeamPlannerProtocol.validate(
                    steps, userInput, ExecutionStep::id, ExecutionStep::description, ExecutionStep::dependencies);
        }
        if (planIssue != null) {
            log.warn("Planner output remained invalid after repair attempts: {}", planIssue);
            return new PlanGenerationResult(result, List.of());
        }
        return new PlanGenerationResult(result, steps);
    }

    private AgentMessage executePlanner(AgentMessage message) {
        try {
            return planner.execute(message, out);
        } finally {
            planner.clearHistory();
        }
    }

    /**
     * 运行多 Agent 协作任务
     */
    public String run(String userInput) {
        log.info("Multi-Agent run started: inputLength={}", userInput == null ? 0 : userInput.length());
        TraceContext traceContext = TraceContext.root("team");
        traceRecorder.record(traceContext, "run.start", Map.of(
                "inputChars", userInput == null ? 0 : userInput.length(),
                "workers", workers.size()
        ));
        memoryManager.addUserMessage(userInput);
        currentUserTask = userInput == null ? "" : userInput;
        toolRegistry.prefetchToolDefinitionsForInput(currentUserTask);
        restoredFailedArtifacts.clear();
        currentAcceptanceCriteria = List.of();
        // 回收上一轮崩溃残留的超时租约，避免历史租约阻塞本轮写入
        toolRegistry.pruneExpiredLeases();
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }
        String finalResultForSummary = "";
        try {
            // 1. 规划阶段：让规划者拆解任务
            out.println(AnsiStyle.heading("📋 第一阶段：规划"));
            out.println("🧑‍💼 规划者正在分析任务...\n");

            PlanGenerationResult planGeneration = requestValidatedPlan(userInput);
            AgentMessage planResult = planGeneration.message();
            if (CancellationContext.isCancelled()) {
                finalResultForSummary = "⏹️ 已取消当前多 Agent 任务。";
                return finalResultForSummary;
            }

            if (planResult.type() == AgentMessage.Type.ERROR) {
                finalResultForSummary = "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
                return finalResultForSummary;
            }

            // 2. 解析计划（requestValidatedPlan 已完成协议校验和超步数粗化）
            List<ExecutionStep> steps = planGeneration.steps();
            if (steps.isEmpty()) {
                finalResultForSummary = "❌ 规划失败：无法解析执行计划\n原始输出:\n"
                        + Objects.toString(planResult.content(), "");
                return finalResultForSummary;
            }
            steps = appendFinalIntegrationStep(steps);
            checkpoint = new AgentCheckpoint(
                    "orch-" + UUID.randomUUID().toString().substring(0, 8),
                    currentUserTask);
            checkpoint.setPlanSteps(toPlanSteps(steps));
            checkpoint.setAcceptanceCriteria(toCriterionRecords(currentAcceptanceCriteria));
            checkpoint.ensureAgentIdentities(currentAgentIdentities());
            checkpoint.advanceAgentCursor(planner.getName(), "",
                    "计划已生成：" + steps.size() + " 个步骤，"
                            + currentAcceptanceCriteria.size() + " 条验收标准");
            checkpoint.save();

            out.println(AnsiStyle.heading("📋 执行计划"));
            out.println(summarizeSteps(steps) + "\n");

            finalResultForSummary = executeSteps(steps, traceContext);
            return finalResultForSummary;
        } finally {
            scheduleSessionPreSummaryMaintenance(userInput, finalResultForSummary);
        }
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
     * 从磁盘 checkpoint 恢复执行（/team resume 入口）。
     *
     * <p>恢复范围：计划（步骤/依赖/验收点）与进度（已完成步骤带回完整 result 与产物文件，
     * 其余——包括上次失败的、被阻塞的——重置为 PENDING 重新执行）。
     * <b>不恢复</b> WorkingMemory / 会话记忆：Worker 上下文完全来自 checkpoint 内的步骤 result。
     *
     * @param orchestrationIdOrNull 指定 checkpoint id；为空时取最近一次保存的 checkpoint
     */
    public String resume(String orchestrationIdOrNull) {
        AgentCheckpoint.LoadResult loadResult =
                (orchestrationIdOrNull == null || orchestrationIdOrNull.isBlank())
                        ? AgentCheckpoint.loadLatestResult()
                        : AgentCheckpoint.loadResult(orchestrationIdOrNull.trim());
        if (loadResult.status() == AgentCheckpoint.LoadStatus.INCOMPATIBLE) {
            return "❌ " + loadResult.message();
        }
        AgentCheckpoint loaded = loadResult.checkpoint();
        if (loaded == null) {
            return formatNoCheckpointMessage(orchestrationIdOrNull);
        }
        Path projectRoot = Path.of(toolRegistry.getProjectPath());
        AgentCheckpoint.PatchReconcileResult patchReconcile;
        try {
            patchReconcile = workspaceCommitCoordinator.reconcile(loaded, projectRoot);
        } catch (Exception e) {
            return "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] PatchSet 恢复对账保存失败：" + e.getMessage();
        }
        if (!patchReconcile.failures().isEmpty()) {
            return "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] 存在无法自动回滚的 PatchSet 写前日志："
                    + patchReconcile.failures();
        }
        checkpoint = loaded;
        AgentCheckpoint.RecoveryState recovery;
        try {
            recovery = restoreAgentTopology(loaded);
        } catch (RuntimeException e) {
            checkpoint = null;
            return "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] 子代理身份恢复失败：" + e.getMessage();
        }
        if (recovery.planSteps().isEmpty()) {
            return "❌ checkpoint [" + loaded.getOrchestrationId()
                    + "] 缺少计划数据（旧格式落盘），无法恢复；请重新发起 /team 任务。";
        }
        long completedCount = recovery.artifacts().values().stream()
                .filter(ExecutionArtifact::successful)
                .count();
        log.info("Multi-Agent resume started: checkpoint={}, protocol={}, completed={}/{}",
                loaded.getOrchestrationId(), recovery.protocolVersion(),
                completedCount, recovery.planSteps().size());
        TraceContext traceContext = TraceContext.root("team-resume");
        traceRecorder.record(traceContext, "resume.start", Map.of(
                "checkpoint", loaded.getOrchestrationId(),
                "completedSteps", completedCount,
                "planSteps", recovery.planSteps().size()
        ));

        currentUserTask = recovery.goal() == null ? "" : recovery.goal();
        memoryManager.addUserMessage(currentUserTask);
        currentAcceptanceCriteria = fromCriterionRecords(recovery.acceptanceCriteria());
        restoredFailedArtifacts = recovery.artifacts().entrySet().stream()
                .filter(entry -> entry.getValue().state() == ExecutionGraph.NodeState.FAILED)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        toolRegistry.pruneExpiredLeases();
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        List<ExecutionStep> steps = rebuildStepsFromCheckpoint(recovery);
        restoreCheckpointArtifactsIntoWorkingMemory(recovery);

        out.println(AnsiStyle.heading("🔁 恢复执行 checkpoint [" + loaded.getOrchestrationId() + "]"
                + "（已完成 " + completedCount + "/" + steps.size() + " 步）"));
        out.println(summarizeSteps(steps) + "\n");

        return executeSteps(steps, traceContext);
    }

    /** 兼容旧调用入口，恢复语义统一委托给结构化协议。 */
    private List<ExecutionStep> rebuildStepsFromCheckpoint(AgentCheckpoint checkpoint) {
        return rebuildStepsFromCheckpoint(checkpoint.recoveryState());
    }

    /** checkpoint 计划层 + 结构化产物 → 可调度的步骤列表。 */
    private List<ExecutionStep> rebuildStepsFromCheckpoint(AgentCheckpoint.RecoveryState recovery) {
        List<ExecutionStep> steps = new ArrayList<>();
        for (AgentCheckpoint.PlanStep planStep : recovery.planSteps()) {
            List<String> deps = planStep.dependencies() == null ? List.of() : planStep.dependencies();
            ExecutionArtifact artifact = recovery.artifacts().get(planStep.id());
            if (artifact != null && artifact.successful()) {
                steps.add(new ExecutionStep(
                        planStep.id(), planStep.description(), planStep.type(), deps, artifact));
            } else {
                steps.add(ExecutionStep.pending(
                        planStep.id(), planStep.description(), planStep.type(), deps));
            }
        }
        return steps;
    }

    private void restoreCheckpointArtifactsIntoWorkingMemory(AgentCheckpoint.RecoveryState recovery) {
        for (Map.Entry<String, ExecutionArtifact> entry : recovery.artifacts().entrySet()) {
            String source = entry.getValue().successful()
                    ? "checkpoint 已完成步骤"
                    : "checkpoint 失败步骤";
            addStepModifiedFilesFact(
                    entry.getKey(), entry.getValue().modifiedResources(), source);
        }
    }

    private String formatNoCheckpointMessage(String requestedId) {
        StringBuilder sb = new StringBuilder();
        if (requestedId == null || requestedId.isBlank()) {
            sb.append("❌ 没有可恢复的 checkpoint。");
        } else {
            sb.append("❌ 未找到 checkpoint [").append(requestedId.trim()).append("]。");
        }
        List<AgentCheckpoint.CheckpointInfo> available = AgentCheckpoint.listAvailable();
        if (!available.isEmpty()) {
            sb.append("\n可用的 checkpoint：\n");
            for (AgentCheckpoint.CheckpointInfo info : available) {
                sb.append("  - ").append(info.orchestrationId())
                        .append("（完成 ").append(info.completedSteps())
                        .append(" 步，").append(info.timestamp()).append("）：")
                        .append(abbreviate(info.goal(), 80)).append("\n");
            }
            sb.append("使用 /team resume <id> 恢复指定任务。");
        }
        return sb.toString();
    }

    private List<AgentCheckpoint.PlanStep> toPlanSteps(List<ExecutionStep> steps) {
        return steps.stream()
                .map(step -> new AgentCheckpoint.PlanStep(
                        step.id(), step.description(), step.type(), step.dependencies()))
                .toList();
    }

    private List<AgentCheckpoint.CriterionRecord> toCriterionRecords(List<AcceptanceCriterion> criteria) {
        return criteria.stream()
                .map(c -> new AgentCheckpoint.CriterionRecord(
                        c.id(), c.category(), c.description(), c.testSignal(), c.severity()))
                .toList();
    }

    private List<AcceptanceCriterion> fromCriterionRecords(List<AgentCheckpoint.CriterionRecord> records) {
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(r -> new AcceptanceCriterion(
                        r.id() == null ? "" : r.id(),
                        r.category() == null ? "" : r.category(),
                        r.description() == null ? "" : r.description(),
                        r.testSignal() == null ? "" : r.testSignal(),
                        r.severity() == null || r.severity().isBlank() ? "high" : r.severity()))
                .filter(AcceptanceCriterion::isValid)
                .toList();
    }

    /**
     * 执行阶段共享循环：依赖调度（单步串行 / 多步冲突分波并行）、失败有界重规划、
     * 残留步骤提示、最终汇总与 checkpoint 收尾。run() 与 resume() 共用。
     */
    private String executeSteps(List<ExecutionStep> steps, TraceContext traceContext) {
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        redoTracker.reset();
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前多 Agent 任务。";
            }
            List<ExecutionStep> executable = getExecutableSteps(steps);
            // 失败步骤的在位重做：当正常步骤全部走完（只剩最终集成或无可执行步骤）且存在失败步骤时，
            // 把可重做的失败步骤重置为 PENDING、保持原 id/依赖在 DAG 原位换思路重做，而非生成平行恢复计划。
            // 这样恢复步骤天然通过依赖关系看到已完成成果，不会产生"平行计划 vs 已落盘成果"冲突。
            boolean onlyFinalLeft = !executable.isEmpty()
                    && executable.stream().allMatch(this::isFinalIntegrationStep);
            if ((executable.isEmpty() || onlyFinalLeft) && resetFailedStepsForRedo(steps)) {
                continue;
            }
            if (executable.isEmpty()) {
                break;
            }
            if (executable.size() == 1 && isFinalIntegrationStep(executable.get(0))
                    && shouldFuseFinalIntegration(steps)) {
                ExecutionStep finalStep = executable.get(0);
                String reason = "Final integration 熔断：失败步骤比例过高，停止让最终集成阶段强行修补。";
                updateStep(steps, finalStep.id(), finalStep.withFailed(reason));
                out.println("⛔ 步骤 [" + finalStep.id() + "] " + reason + "\n");
                continue;
            }
            batchIndex++;

            if (executable.size() == 1) {
                // 单步批次：直接串行流式输出，保持实时打字观感
                ExecutionStep step = executable.get(0);
                SubAgent worker = resolveAssignedWorker(step.id(), singleStepCursor);
                singleStepCursor++;
                String context = buildStepContext(steps, step);
                runStep(step, steps, retryCount, worker, reviewer, context, out);
                worker.clearHistory();
            } else {
                // 多步批次：真正并行执行，每步用独立的 PrintStream 缓冲，完成后按 step_id 顺序 flush
                List<List<ExecutionStep>> waves = ResourceConflictDetector.splitConflictFree(
                        executable, ExecutionStep::id, ExecutionStep::description, ExecutionStep::type);
                for (List<ExecutionStep> wave : waves) {
                    traceRecorder.record(traceContext, "batch.wave", Map.of(
                            "batchIndex", batchIndex,
                            "size", wave.size(),
                            "stepIds", wave.stream().map(ExecutionStep::id).toList().toString()
                    ));
                    out.println("⚡ 批次 #" + batchIndex + "：" + wave.size()
                            + " 个独立步骤并行执行（最多 " + workers.size() + " 个并发 Worker）\n");
                    runBatchParallel(wave, steps, retryCount);
                }
            }
        }

        // 5. 处理因前置失败而无法执行的残留步骤（显式提示用户）
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: " + step.description());
            }
        }

        // 6. 汇总结果
        String finalResult = buildFinalResult(steps);
        memoryManager.addAssistantMessage("[多Agent结果] " + finalResult);

        if (checkpoint != null) {
            boolean allCompleted = steps.stream().allMatch(step ->
                    step.status() == StepStatus.COMPLETED);
            if (allCompleted) {
                checkpoint.delete();
            } else {
                checkpoint.save();
                log.info("orchestration checkpoint retained for resume/post-mortem: {}",
                        checkpoint.getOrchestrationId());
            }
        }

        return finalResult;
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            log.debug("Parsing plan JSON, input length={}", planJson == null ? 0 : planJson.length());
            if (log.isDebugEnabled() && planJson != null && planJson.length() < 2000) {
                log.debug("Plan JSON full content:\n{}", planJson);
            } else if (log.isDebugEnabled() && planJson != null) {
                log.debug("Plan JSON first 500 chars:\n{}", planJson.substring(0, Math.min(500, planJson.length())));
                log.debug("Plan JSON last 500 chars:\n{}", planJson.substring(Math.max(0, planJson.length() - 500)));
            }

            String cleaned = Objects.toString(planJson, "")
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            log.debug("After cleaning, JSON length={}", cleaned.length());
            JsonNode root = TeamPlannerProtocol.parsePlanRoot(mapper, cleaned);
            if (root == null) {
                log.warn("Planner output does not contain a complete plan JSON object");
                currentAcceptanceCriteria = List.of();
                return List.of();
            }
            currentAcceptanceCriteria = parseAcceptanceCriteria(firstPresent(root,
                    "acceptance_criteria", "acceptanceCriteria", "acceptancecriteria"));
            log.debug("Parsed acceptance criteria: {} items", currentAcceptanceCriteria.size());

            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

            log.debug("Found {} steps in plan", stepsNode.size());

            List<ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;

            // 第一遍：创建步骤（重编号）
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);

                String description = stepNode.path("description").asText();
                String type = stepNode.path("type").asText("COMMAND");
                log.debug("Step {}: id={}, type={}, description={}", stepIndex - 1, newId, type,
                        description.length() > 100 ? description.substring(0, 100) + "..." : description);
                steps.add(ExecutionStep.pending(newId, description, type, new ArrayList<>()));
            }

            // 第二遍：建立依赖
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String newId = "step_" + stepIndex++;
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    List<String> deps = new ArrayList<>();
                    for (JsonNode dep : depsNode) {
                        String mapped = idMapping.getOrDefault(dep.asText(), dep.asText());
                        deps.add(mapped);
                    }
                    // 替换步骤的依赖
                    int idx = stepIndex - 2;
                    if (idx >= 0 && idx < steps.size()) {
                        ExecutionStep old = steps.get(idx);
                        steps.set(idx, new ExecutionStep(old.id(), old.description(), old.type(),
                                deps, old.result(), old.status(), old.modifiedFiles()));
                    }
                }
            }

            List<ExecutionStep> normalizedSteps = coarsenPlanIfNeeded(steps);
            ExecutionGraph.ValidationResult validation = ExecutionGraph.validate(
                    normalizedSteps, ExecutionStep::id, ExecutionStep::dependencies);
            if (!validation.valid()) {
                log.warn("Plan graph validation failed: {}", validation.errors());
                currentAcceptanceCriteria = List.of();
                return List.of();
            }
            log.debug("Final validated plan: {} steps", normalizedSteps.size());
            return normalizedSteps;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            currentAcceptanceCriteria = List.of();
            return List.of();
        }
    }

    List<AcceptanceCriterion> parseAcceptanceCriteria(JsonNode criteriaNode) {
        if (criteriaNode == null || !criteriaNode.isArray() || criteriaNode.isEmpty()) {
            return List.of();
        }
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        int index = 1;
        for (JsonNode node : criteriaNode) {
            if (!node.isObject()) {
                continue;
            }
            String id = node.path("id").asText("AC-" + String.format(Locale.ROOT, "%02d", index));
            String category = node.path("category").asText("");
            String description = node.path("description").asText("");
            String testSignal = firstPresent(node, "test_signal", "testSignal", "testsignal").asText("");
            String severity = node.path("severity").asText("high");
            AcceptanceCriterion criterion = new AcceptanceCriterion(id, category, description, testSignal, severity);
            if (criterion.isValid()) {
                criteria.add(criterion);
                index++;
            }
        }
        return List.copyOf(criteria);
    }

    private JsonNode firstPresent(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return mapper.missingNode();
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return mapper.missingNode();
    }

    List<ExecutionStep> coarsenPlanIfNeeded(List<ExecutionStep> steps) {
        if (steps == null || steps.size() <= MAX_PLANNER_STEPS) {
            return steps;
        }
        List<ExecutionStep> analysisSteps = new ArrayList<>();
        List<ExecutionStep> verificationSteps = new ArrayList<>();
        List<ExecutionStep> implementationSteps = new ArrayList<>();
        for (ExecutionStep step : steps) {
            String type = step.type() == null ? "" : step.type().toUpperCase(Locale.ROOT);
            String text = ((step.type() == null ? "" : step.type()) + " " + step.description()).toLowerCase(Locale.ROOT);
            if (type.contains("VERIFICATION") || text.contains("验证") || text.contains("test")) {
                verificationSteps.add(step);
            } else if (type.contains("ANALYSIS") || type.contains("FILE_READ") || text.contains("分析") || text.contains("读取")) {
                analysisSteps.add(step);
            } else {
                implementationSteps.add(step);
            }
        }

        List<ExecutionStep> coarse = new ArrayList<>();
        if (!analysisSteps.isEmpty()) {
            coarse.add(ExecutionStep.pending("step_1", mergeStepDescriptions("分析与准备", analysisSteps),
                    "ANALYSIS", List.of()));
        }
        if (!implementationSteps.isEmpty()) {
            List<String> deps = coarse.isEmpty() ? List.of() : List.of(coarse.get(coarse.size() - 1).id());
            coarse.add(ExecutionStep.pending("step_" + (coarse.size() + 1),
                    mergeStepDescriptions("核心实现", implementationSteps), "FILE_WRITE", deps));
        }
        if (!verificationSteps.isEmpty()) {
            List<String> deps = coarse.isEmpty() ? List.of() : List.of(coarse.get(coarse.size() - 1).id());
            coarse.add(ExecutionStep.pending("step_" + (coarse.size() + 1),
                    mergeStepDescriptions("验证与修正", verificationSteps), "VERIFICATION", deps));
        }
        if (coarse.isEmpty()) {
            coarse.add(ExecutionStep.pending("step_1", mergeStepDescriptions("完成任务", steps),
                    "FILE_WRITE", List.of()));
        }
        return coarse;
    }

    private String mergeStepDescriptions(String title, List<ExecutionStep> steps) {
        StringBuilder description = new StringBuilder(title).append("：");
        for (ExecutionStep step : steps) {
            description.append("\n- ").append(step.description());
        }
        description.append("\n按原始需求交付完整可用结果，不要只完成局部文件或口头说明。");
        return description.toString();
    }

    /**
     * 获取当前可执行的步骤（依赖已全部完成）
     */
    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        return ExecutionGraph.ready(
                steps,
                ExecutionStep::id,
                ExecutionStep::dependencies,
                step -> graphState(step.status()),
                this::isFinalIntegrationStep);
    }

    private static ExecutionGraph.NodeState graphState(StepStatus status) {
        return switch (status) {
            case PENDING -> ExecutionGraph.NodeState.PENDING;
            case RUNNING -> ExecutionGraph.NodeState.RUNNING;
            case COMPLETED -> ExecutionGraph.NodeState.COMPLETED;
            case FAILED -> ExecutionGraph.NodeState.FAILED;
        };
    }

    boolean shouldFuseFinalIntegration(List<ExecutionStep> steps) {
        List<ExecutionStep> normalSteps = steps.stream()
                .filter(step -> !isFinalIntegrationStep(step))
                .toList();
        if (normalSteps.isEmpty()) {
            return false;
        }
        long failed = normalSteps.stream()
                .filter(step -> step.status() == StepStatus.FAILED)
                .count();
        double failureRatio = (double) failed / normalSteps.size();
        return failureRatio >= FINAL_INTEGRATION_FAILURE_RATIO_LIMIT;
    }

    List<ExecutionStep> appendFinalIntegrationStep(List<ExecutionStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        boolean exists = steps.stream().anyMatch(step -> {
            String text = (step.id() + " " + step.description()).toLowerCase(Locale.ROOT);
            return text.contains("final_integration") || text.contains("最终集成") || text.contains("integration");
        });
        if (exists) {
            return steps;
        }
        Set<String> depended = steps.stream()
                .flatMap(step -> step.dependencies().stream())
                .collect(Collectors.toSet());
        List<String> leafStepIds = steps.stream()
                .map(ExecutionStep::id)
                .filter(id -> !depended.contains(id))
                .toList();
        String finalId = "step_" + (steps.size() + 1);
        String description = """
                最终集成验收：基于原始用户任务检查并补齐整体功能入口、跨模块联动、默认参数、错误处理和端到端可运行性。
                先读取现有生产文件，确认已存在的 class / method / signature，不要创建第二套入口。
                你只负责胶水代码、入口 main、对外 API 导出、默认参数注入和跨模块联动。
                不要重写或大改已 COMPLETED 的底层模块；如果核心依赖失败或缺失，直接说明风险，不要强行擦屁股。
                完成后运行最小编译或自检命令，修复集成层问题。
                """;
        List<ExecutionStep> withFinal = new ArrayList<>(steps);
        withFinal.add(ExecutionStep.pending(finalId, description, "INTEGRATION", leafStepIds));
        return withFinal;
    }

    private boolean isFinalIntegrationStep(ExecutionStep step) {
        String id = step.id() == null ? "" : step.id().toLowerCase(Locale.ROOT);
        String type = step.type() == null ? "" : step.type().toLowerCase(Locale.ROOT);
        String description = step.description() == null ? "" : step.description().toLowerCase(Locale.ROOT);
        return id.contains("final_integration")
                || description.contains("最终集成")
                || type.equals("integration")
                || type.equals("final_integration");
    }

    /**
     * 失败步骤在位重做：把可重做的失败普通步骤重置为 PENDING（保持原 id/依赖），在 DAG 原位
     * 换思路重做，而非生成平行恢复计划。返回是否有步骤被重置（有则调度循环 continue 重新调度）。
     *
     * <p>失败原因存入 {@link StepRedoTracker}，重做时由 {@link #buildStepContext} 注入"换思路"提示；
     * redo 用尽的失败步骤保持 FAILED 终态，交由最终集成熔断与汇总处理。恢复始终长在原 DAG 上、
     * 通过依赖关系看到已完成成果，不会产生"平行计划 vs 已落盘成果"冲突。
     */
    private boolean resetFailedStepsForRedo(List<ExecutionStep> steps) {
        boolean anyReset = false;
        for (int i = 0; i < steps.size(); i++) {
            ExecutionStep step = steps.get(i);
            if (isFinalIntegrationStep(step) || step.status() != StepStatus.FAILED) {
                continue;
            }
            if (!redoTracker.canRedo(step.id())) {
                continue;
            }
            int attempt = redoTracker.markRedo(step.id(), step.result());
            out.println(AnsiStyle.heading("🔁 步骤 [" + step.id() + "] 失败，在原位换思路重做（第 "
                    + attempt + "/" + redoTracker.maxRedoPerStep() + " 次）"));
            steps.set(i, step.withRedoPending());
            anyReset = true;
        }
        return anyReset;
    }

    /**
     * 解析检查者的审批结果
     *
     * 解析失败时采取保守策略：默认判为"不通过"，避免在审查者异常输出时让问题结果直接放行。
     */
    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return false;
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            JsonNode approvedNode = root.path("approved");
            if (approvedNode.isMissingNode() || approvedNode.isNull()) {
                log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
                return false;
            }
            boolean approved = approvedNode.asBoolean(false);
            if (!approved) {
                return false;
            }
            if (hasFailedBlockingCriteria(root.path("criteria_results"))) {
                log.warn("Reviewer approved despite failed blocking acceptance criteria, defaulting to rejected");
                return false;
            }
            if (hasMissingAcceptanceCriteriaCoverage(root.path("criteria_results"))) {
                log.warn("Reviewer JSON missing acceptance criteria coverage, defaulting to rejected");
                return false;
            }
            JsonNode scoresNode = root.path("scores");
            if (scoresNode.isMissingNode() || scoresNode.isNull() || !scoresNode.isObject()) {
                log.warn("Reviewer JSON missing structured scores, defaulting to rejected");
                return false;
            }
            double functional = scoresNode.path("functional_correctness").asDouble(-1.0);
            double integration = scoresNode.path("integration_completeness").asDouble(-1.0);
            double quality = scoresNode.path("code_quality").asDouble(-1.0);
            if (functional < REQUIRED_FUNCTIONAL_SCORE) {
                log.warn("Reviewer functional_correctness score {} below required {}", functional, REQUIRED_FUNCTIONAL_SCORE);
                return false;
            }
            if (integration < MIN_REVIEW_SCORE || quality < MIN_REVIEW_SCORE) {
                log.warn("Reviewer scores below threshold: integration={}, quality={}, threshold={}",
                        integration, quality, MIN_REVIEW_SCORE);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Reviewer output is not valid JSON, defaulting to rejected");
            return false;
        }
    }

    private boolean hasFailedBlockingCriteria(JsonNode criteriaResultsNode) {
        if (criteriaResultsNode == null || !criteriaResultsNode.isArray()) {
            return false;
        }
        for (JsonNode result : criteriaResultsNode) {
            boolean passed = result.path("passed").asBoolean(false);
            String id = result.path("id").asText("");
            String severity = result.path("severity").asText("").toLowerCase(Locale.ROOT);
            if (!passed && (isBlockingSeverity(severity) || isBlockingSeverity(plannedSeverityFor(id)))) {
                return true;
            }
        }
        return false;
    }

    private String plannedSeverityFor(String criterionId) {
        if (criterionId == null || criterionId.isBlank()
                || currentAcceptanceCriteria == null || currentAcceptanceCriteria.isEmpty()) {
            return "";
        }
        for (AcceptanceCriterion criterion : currentAcceptanceCriteria) {
            if (criterion.id().equals(criterionId)) {
                return criterion.severity();
            }
        }
        return "";
    }

    private static boolean isBlockingSeverity(String severity) {
        if (severity == null) {
            return false;
        }
        String normalized = severity.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("critical") || normalized.equals("high");
    }

    private boolean hasMissingAcceptanceCriteriaCoverage(JsonNode criteriaResultsNode) {
        if (currentAcceptanceCriteria == null || currentAcceptanceCriteria.isEmpty()) {
            return false;
        }
        if (criteriaResultsNode == null || !criteriaResultsNode.isArray() || criteriaResultsNode.isEmpty()) {
            return true;
        }
        Set<String> coveredIds = new HashSet<>();
        for (JsonNode result : criteriaResultsNode) {
            String id = result.path("id").asText("");
            if (!id.isBlank()) {
                coveredIds.add(id);
            }
        }
        for (AcceptanceCriterion criterion : currentAcceptanceCriteria) {
            if (!coveredIds.contains(criterion.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析检查者反馈的问题
     */
    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isEmpty()) {
            return "";
        }
        try {
            String cleaned = reviewContent.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = mapper.readTree(cleaned);
            String criteriaIssues = formatFailedCriteriaResults(root.path("criteria_results"));

            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray() && !issuesNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode issue : issuesNode) {
                    sb.append("- ").append(formatReviewIssue(issue)).append("\n");
                }
                if (!criteriaIssues.isBlank()) {
                    sb.append(criteriaIssues).append("\n");
                }
                return sb.toString().trim();
            }

            JsonNode suggestionsNode = root.path("suggestions");
            if (suggestionsNode.isArray() && !suggestionsNode.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode suggestion : suggestionsNode) {
                    sb.append("- ").append(formatReviewIssue(suggestion)).append("\n");
                }
                if (!criteriaIssues.isBlank()) {
                    sb.append(criteriaIssues).append("\n");
                }
                return sb.toString().trim();
            }

            if (!criteriaIssues.isBlank()) {
                return criteriaIssues;
            }

            // 返回 summary 作为备选
            String summary = root.path("summary").asText();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    private String formatFailedCriteriaResults(JsonNode criteriaResultsNode) {
        if (criteriaResultsNode == null || !criteriaResultsNode.isArray() || criteriaResultsNode.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode result : criteriaResultsNode) {
            if (result.path("passed").asBoolean(false)) {
                continue;
            }
            String id = result.path("id").asText("");
            String severity = result.path("severity").asText("");
            String evidence = result.path("evidence").asText("");
            sb.append("- 验收失败");
            if (!id.isBlank()) {
                sb.append(" ").append(id);
            }
            if (!severity.isBlank()) {
                sb.append(" severity=").append(severity);
            }
            if (!evidence.isBlank()) {
                sb.append(": ").append(evidence);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String formatReviewIssue(JsonNode issue) {
        if (issue == null || issue.isNull()) {
            return "";
        }
        if (!issue.isObject()) {
            return issue.asText();
        }
        List<String> parts = new ArrayList<>();
        String type = issue.path("type").asText("");
        String severity = issue.path("severity").asText("");
        String description = issue.path("description").asText("");
        if (!type.isBlank()) {
            parts.add("type=" + type);
        }
        if (!severity.isBlank()) {
            parts.add("severity=" + severity);
        }
        if (!description.isBlank()) {
            parts.add(description);
        }
        if (parts.isEmpty()) {
            return issue.toString();
        }
        return String.join(", ", parts);
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

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        StepUpdateBuffer buffer = activeStepUpdate.get();
        ExecutionStep effective = attachModifiedFiles(stepId, updated);
        if (buffer != null && buffer.stepId.equals(stepId)) {
            buffer.updated = effective;
            return;
        }
        commitStepUpdate(steps, stepId, effective);
    }

    private synchronized void commitStepUpdate(List<ExecutionStep> steps, String stepId,
                                               ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                addStepModifiedFilesFact(stepId, updated.modifiedFiles(),
                        updated.status() == StepStatus.COMPLETED
                                ? "Multi-Agent 步骤完成"
                                : "Multi-Agent 步骤失败");
                recordStepToCheckpoint(stepId, updated);
                return;
            }
        }
    }

    private ExecutionStep attachModifiedFiles(String stepId, ExecutionStep updated) {
        if (updated.status() != StepStatus.COMPLETED && updated.status() != StepStatus.FAILED) {
            return updated;
        }
        ToolRegistry registry = activeToolRegistry();
        List<String> consumed = registry.consumeStepModifiedFiles(stepId);
        List<String> modifiedFiles = consumed.isEmpty() ? updated.modifiedFiles() : consumed;
        return updated.withModifiedFiles(modifiedFiles);
    }

    private ToolRegistry activeToolRegistry() {
        ToolRegistry active = activeStepToolRegistry.get();
        return active == null ? toolRegistry : active;
    }

    private void addStepModifiedFilesFact(String stepId, List<String> modifiedFiles, String source) {
        if (modifiedFiles == null || modifiedFiles.isEmpty()) {
            return;
        }
        memoryManager.addVolatileFact(source + " [" + stepId + "] 修改文件: " + String.join(", ", modifiedFiles));
    }

    /** 步骤终态写入 checkpoint（updateStep 已同步，无并发问题）。 */
    private void recordStepToCheckpoint(String stepId, ExecutionStep updated) {
        if (checkpoint == null) {
            return;
        }
        if (updated.status() == StepStatus.COMPLETED) {
            // 完整 result 落盘（上限见 AgentCheckpoint.MAX_SUMMARY_LENGTH）：
            // resume 后 buildStepContext 要用它给后续步骤当依赖上下文
            checkpoint.addCompletedStep(stepId,
                    updated.modifiedFiles(),
                    updated.result());
            saveCheckpointStrict();
        } else if (updated.status() == StepStatus.FAILED) {
            // 失败步骤可能已写入文件（副作用不可逆）：保留 modifiedFiles 进 checkpoint，
            // resume 后注入重做上下文，让 Worker 知道上次失败已留下哪些文件。
            // addFailedStep 内部已调 recordFailure，此处不再单独调用以免 failedSteps 重复计数。
            checkpoint.addFailedStep(stepId,
                    updated.modifiedFiles(),
                    updated.result());
            saveCheckpointStrict();
        }
    }

    private synchronized void saveCheckpointStrict() {
        try {
            checkpoint.saveStrict();
        } catch (IOException e) {
            throw new IllegalStateException("Checkpoint 持久化失败: " + e.getMessage(), e);
        }
    }

    private synchronized SubAgent resolveAssignedWorker(String stepId, int preferredIndex) {
        AgentCheckpoint.StepAssignmentRecord existing = checkpoint == null
                ? null
                : checkpoint.getStepAssignments().get(stepId);
        if (existing != null) {
            return workers.stream()
                    .filter(worker -> worker.getName().equals(existing.workerAgentId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "checkpoint 步骤绑定的 Worker 不存在: " + existing.workerAgentId()));
        }
        SubAgent worker = workers.get(Math.floorMod(preferredIndex, workers.size()));
        if (checkpoint != null) {
            checkpoint.assignStep(stepId, worker.getName(), reviewer.getName());
            saveCheckpointStrict();
        }
        return worker;
    }

    private synchronized void recordAgentMessage(String agentId, String stepId,
                                                 String phase, AgentMessage message) {
        if (checkpoint == null) {
            return;
        }
        String type = message == null || message.type() == null ? "UNKNOWN" : message.type().name();
        String content = message == null || message.content() == null ? "" : message.content().trim();
        String summary = phase + " [" + type + "]" + (content.isBlank() ? "" : " " + content);
        if (checkpoint.advanceAgentCursor(agentId, stepId, summary)) {
            saveCheckpointStrict();
        }
    }

    private synchronized void recordAgentEvent(String agentId, String stepId,
                                               String phase, String summary) {
        if (checkpoint != null
                && checkpoint.advanceAgentCursor(agentId, stepId,
                phase + (summary == null || summary.isBlank() ? "" : ": " + summary.trim()))) {
            saveCheckpointStrict();
        }
    }

    /**
     * 并行执行一批相互独立的步骤。
     *
     * 每个步骤获取一个 Worker（池化，避免同一 Worker 被两个步骤并发占用），同时创建独立的 Reviewer 实例，
     * 流式输出写入步骤本地的 ByteArrayOutputStream；所有任务完成后按 step_id 顺序将缓冲区 flush 到 stdout。
     */
    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "devcli-multi-agent");
            t.setDaemon(true);
            return t;
        });
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        Map<String, SubAgent> assignments = new LinkedHashMap<>();
        for (int i = 0; i < batch.size(); i++) {
            ExecutionStep step = batch.get(i);
            assignments.put(step.id(), resolveAssignedWorker(step.id(), i));
        }
        Map<String, ReentrantLock> workerLocks = new HashMap<>();
        workers.forEach(worker -> workerLocks.put(worker.getName(), new ReentrantLock(true)));
        Map<SubAgent, SubAgent.ForkContext> workerContexts = new ConcurrentHashMap<>();
        for (SubAgent worker : workers) {
            workerContexts.put(worker, worker.createForkContext());
        }
        List<Future<?>> futures = new ArrayList<>();
        SubAgent reviewerForkTemplate = new SubAgent(reviewer.getName(), AgentRole.REVIEWER, llmClient, toolRegistry);
        configureSubAgent(reviewerForkTemplate);
        if (checkpoint != null) {
            applyRecoveryContext(reviewerForkTemplate, checkpoint.recoveryState());
        }
        SubAgent.ForkContext reviewerForkContext = reviewerForkTemplate.createForkContext();

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                SubAgent worker = assignments.get(step.id());
                ReentrantLock workerLock = workerLocks.get(worker.getName());
                SubAgent localReviewer = new SubAgent(
                        reviewer.getName(), AgentRole.REVIEWER, llmClient, toolRegistry);
                configureSubAgent(localReviewer);
                if (checkpoint != null) {
                    applyRecoveryContext(localReviewer, checkpoint.recoveryState());
                }
                try {
                    workerLock.lockInterruptibly();
                    SubAgent.ForkContext workerForkContext = workerContexts.get(worker);
                    toolRegistry.runWithResourceLease(step.id(), () -> {
                        runStep(step, steps, retryCount, worker, localReviewer, context, stepOut,
                                workerForkContext, reviewerForkContext);
                        return null;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    worker.clearHistory();
                    if (workerLock.isHeldByCurrentThread()) {
                        workerLock.unlock();
                    }
                    toolRegistry.releaseResourceLeases(step.id());
                    stepOut.flush();
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Batch wait interrupted");
            } catch (ExecutionException e) {
                log.error("Parallel step task failed", e.getCause());
            }
        }
        executor.shutdownNow();

        // 按 step_id 顺序 flush 各步骤的缓冲输出，保证用户看到的执行过程有稳定顺序
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                out.print(buf.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /**
     * 执行单个步骤（Worker 执行 + Reviewer 审查 + 最多 2 次重试）。
     *
     * 此方法被串行和并行两条路径共享，通过 {@code out} 控制流式输出目的地。
     */
    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out) {
        runStep(step, steps, retryCount, worker, reviewer, context, out, null, null);
    }

    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker, SubAgent reviewer, String context,
                         PrintStream out,
                         SubAgent.ForkContext workerForkContext,
                         SubAgent.ForkContext reviewerForkContext) {
        if (requiresIsolatedWorkspace(step)) {
            runStepInIsolatedWorkspace(step, steps, retryCount, worker, reviewer, context, out,
                    workerForkContext, reviewerForkContext);
            return;
        }
        try {
            toolRegistry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
                runStepWithLease(step, steps, retryCount, worker, reviewer, context, out,
                        workerForkContext, reviewerForkContext);
                return null;
            });
        } finally {
            toolRegistry.releaseResourceLeases(step.id());
        }
    }

    private void runStepInIsolatedWorkspace(ExecutionStep step, List<ExecutionStep> steps,
                                            Map<String, Integer> retryCount,
                                            SubAgent worker, SubAgent reviewer, String context,
                                            PrintStream out,
                                            SubAgent.ForkContext workerForkContext,
                                            SubAgent.ForkContext reviewerForkContext) {
        StepUpdateBuffer buffer = new StepUpdateBuffer(step.id());
        try (WorkspaceExecutionSession session = WorkspaceExecutionSession.open(toolRegistry, step.id())) {
            ToolRegistry isolatedRegistry = session.toolRegistry();
            SubAgent isolatedWorker = new SubAgent(
                    worker.getName(), worker.getRole(), llmClient, isolatedRegistry);
            SubAgent isolatedReviewer = new SubAgent(
                    reviewer.getName(), reviewer.getRole(), llmClient, isolatedRegistry);
            configureSubAgent(isolatedWorker);
            configureSubAgent(isolatedReviewer);
            if (checkpoint != null) {
                AgentCheckpoint.RecoveryState recovery = checkpoint.recoveryState();
                applyRecoveryContext(isolatedWorker, recovery);
                applyRecoveryContext(isolatedReviewer, recovery);
            }

            activeStepToolRegistry.set(isolatedRegistry);
            activeStepUpdate.set(buffer);
            try {
                isolatedRegistry.runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT, () -> {
                    runStepWithLease(step, steps, retryCount,
                            isolatedWorker, isolatedReviewer, context, out,
                            workerForkContext, reviewerForkContext);
                    return null;
                });
            } finally {
                activeStepUpdate.remove();
                activeStepToolRegistry.remove();
                isolatedRegistry.releaseResourceLeases(step.id());
                toolRegistry.releaseResourceLeases(step.id());
                isolatedWorker.clearHistory();
                isolatedReviewer.clearHistory();
            }

            ExecutionStep outcome = buffer.updated == null
                    ? step.withFailed("隔离步骤未产生终态")
                    : buffer.updated;
            PatchSet patchSet = session.patchSet();
            if (outcome.status() == StepStatus.COMPLETED) {
                ExecutionStep workerOutcome = outcome;
                workspaceCommitCoordinator.commit(
                        session,
                        patchSet,
                        checkpoint,
                        step.id(),
                        Path.of(toolRegistry.getProjectPath()),
                        workerOutcome.artifact(),
                        applyResult -> {
                            ExecutionStep decision;
                            if (!applyResult.applied()) {
                                String reason = applyResult.failureDescription();
                                decision = step.withFailed(reason);
                                out.println("❌ 步骤 [" + step.id() + "] " + reason + "\n");
                            } else {
                                decision = workerOutcome.withModifiedFiles(applyResult.modifiedResources());
                            }
                            commitStepUpdate(steps, step.id(), decision);
                        });
            } else {
                commitStepUpdate(steps, step.id(), outcome.withModifiedFiles(List.of()));
            }
        } catch (Exception e) {
            toolRegistry.releaseResourceLeases(step.id());
            commitStepUpdate(steps, step.id(),
                    step.withFailed("隔离工作区执行失败: " + e.getMessage()));
            out.println("❌ 步骤 [" + step.id() + "] 隔离工作区执行失败："
                    + e.getMessage() + "\n");
        }
    }

    private boolean requiresIsolatedWorkspace(ExecutionStep step) {
        String configured = System.getProperty("devcli.workspace.isolation.enabled");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_WORKSPACE_ISOLATION_ENABLED");
        }
        if (configured != null && !configured.isBlank() && !Boolean.parseBoolean(configured)) {
            return false;
        }
        return requiresConcreteVerification(step);
    }

    private void runStepWithLease(ExecutionStep step, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount,
                                  SubAgent worker, SubAgent reviewer, String context,
                                  PrintStream out,
                                  SubAgent.ForkContext workerForkContext,
                                  SubAgent.ForkContext reviewerForkContext) {
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = executeWorkerWithTransientRetry(step, worker, taskMsg, context, out,
                workerForkContext, "");
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }
        String acceptedResult = resolveWorkerResultContent(result.content(), worker.getLastExecutionEvidence());
        if (acceptedResult.isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        ReviewDecision reviewDecision = reviewWorkerResult(step, reviewer, acceptedResult, out, reviewerForkContext);
        boolean approved = reviewDecision.approved();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = reviewDecision.issues();
        if (reviewDecision.reviewerError()) {
            if (shouldAcceptAfterRecoverableReviewerFailure(
                    step, issues, reviewDecision.hardCheckExecuted())) {
                String degradedResult = acceptedResult
                        + "\n\nReviewer 可恢复故障；Pre-Review 硬检查已通过，按降级策略接受。\n"
                        + issues;
                updateStep(steps, step.id(), step.withResult(degradedResult));
                out.println("✅ 步骤 [" + step.id()
                        + "] Pre-Review 硬检查已通过，Reviewer 可恢复故障降级接受\n");
                return;
            }
            updateStep(steps, step.id(), step.withFailed(issues));
            return;
        }
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            String feedbackContext = buildRetryContext(context, issues);
            AgentMessage retryResult = executeWorkerWithTransientRetry(step, worker, taskMsg, feedbackContext, out,
                    workerForkContext, "重试 ");
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            acceptedResult = resolveWorkerResultContent(
                    retryResult.content(), worker.getLastExecutionEvidence());
            if (acceptedResult.isBlank()) {
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result without successful tool evidence",
                        step.id(), retries);
                continue;
            }

            ReviewDecision retryReview = reviewWorkerResult(step, reviewer, acceptedResult, out, reviewerForkContext);
            if (retryReview.reviewerError()) {
                issues = retryReview.issues();
                if (shouldAcceptAfterRecoverableReviewerFailure(
                        step, issues, retryReview.hardCheckExecuted())) {
                    acceptedResult = acceptedResult
                            + "\n\nReviewer 可恢复故障；Pre-Review 硬检查已通过，按降级策略接受。\n"
                            + issues;
                    approved = true;
                }
                break;
            }
            approved = retryReview.approved();
            issues = retryReview.issues();
        }

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 重试后审查通过\n");
        } else {
            updateStep(steps, step.id(), step.withFailed(issues));
            out.println("❌ 步骤 [" + step.id() + "] 审查未通过，阻止下游步骤继续执行\n");
        }
    }

    static String resolveWorkerResultContent(String content, SubAgent.ExecutionEvidence evidence) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        if (evidence == null || !evidence.hasSuccessfulToolCall()) {
            return "";
        }

        List<SubAgent.ToolEvidence> successful = evidence.toolResults().stream()
                .filter(result -> result.status() == com.devcli.tool.ToolStatus.SUCCESS)
                .toList();
        List<SubAgent.ToolEvidence> unsuccessful = evidence.toolResults().stream()
                .filter(result -> result.status() != com.devcli.tool.ToolStatus.SUCCESS)
                .toList();
        StringBuilder summary = new StringBuilder()
                .append("Worker 未返回文字总结，但本轮存在结构化工具执行证据。\n")
                .append("成功工具：")
                .append(successful.size())
                .append('/')
                .append(evidence.toolResults().size());
        int previewCount = Math.min(4, successful.size());
        for (int i = 0; i < previewCount; i++) {
            SubAgent.ToolEvidence tool = successful.get(i);
            summary.append("\n- ").append(tool.name()).append(": ");
            if (tool.result().isBlank()) {
                summary.append("执行成功，无文本结果");
            } else {
                summary.append(previewToolEvidence(tool.result(), 600));
            }
        }
        if (successful.size() > previewCount) {
            summary.append("\n- 其余成功工具：").append(successful.size() - previewCount).append(" 个");
        }
        int failurePreviewCount = Math.min(2, unsuccessful.size());
        for (int i = 0; i < failurePreviewCount; i++) {
            SubAgent.ToolEvidence tool = unsuccessful.get(i);
            summary.append("\n- 未成功 ").append(tool.name())
                    .append(" [").append(tool.status()).append("]: ")
                    .append(tool.result().isBlank()
                            ? "无文本结果"
                            : previewToolEvidence(tool.result(), 300));
        }
        if (unsuccessful.size() > failurePreviewCount) {
            summary.append("\n- 其余未成功工具：")
                    .append(unsuccessful.size() - failurePreviewCount).append(" 个");
        }
        return summary.toString();
    }

    private static String previewToolEvidence(String result, int maxLength) {
        String normalized = result.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...";
    }

    private AgentMessage executeWorkerWithTransientRetry(ExecutionStep step, SubAgent worker, AgentMessage taskMsg,
                                                         String context, PrintStream out,
                                                         SubAgent.ForkContext workerForkContext,
                                                         String label) {
        String executionContext = context;
        AgentMessage result = executeWorkerOnce(
                step, worker, taskMsg, executionContext, out, workerForkContext);
        int transientRetries = 0;
        int protocolRepairs = 0;
        while (true) {
            if (result.type() == AgentMessage.Type.ERROR
                    && isTransientLlmError(result.content())
                    && transientRetries < MAX_RETRIES_PER_STEP) {
                transientRetries++;
                out.println("⚠️ 步骤 [" + step.id() + "] " + label
                        + "LLM 瞬时错误，正在重新调用 Worker (" + transientRetries
                        + "/" + MAX_RETRIES_PER_STEP + ")...");
                result = executeWorkerOnce(
                        step, worker, taskMsg, executionContext, out, workerForkContext);
                continue;
            }

            if (TeamWorkerProtocol.needsMandatoryToolRepair(
                    result, worker.getLastExecutionEvidence(), requireWorkerToolEvidence)
                    && protocolRepairs < TeamWorkerProtocol.MAX_MANDATORY_TOOL_REPAIRS) {
                protocolRepairs++;
                out.println("⚠️ 步骤 [" + step.id() + "] " + label
                        + "Worker 未产生成功工具证据，正在强制执行修复 ("
                        + protocolRepairs + "/" + TeamWorkerProtocol.MAX_MANDATORY_TOOL_REPAIRS + ")...");
                worker.clearHistory();
                LlmClient.ToolChoice requiredToolChoice =
                        TeamWorkerProtocol.requiredToolChoice(step.type());
                AgentMessage repairTask = AgentMessage.task("orchestrator",
                        TeamWorkerProtocol.buildMandatoryToolTask(
                                step.description(), protocolRepairs,
                                requiredToolChoice.toolName()));
                result = executeWorkerOnce(
                        step, worker, repairTask, executionContext, out, workerForkContext,
                        requiredToolChoice);
                continue;
            }
            return result;
        }
    }

    private AgentMessage executeWorkerOnce(ExecutionStep step, SubAgent worker, AgentMessage taskMsg,
                                           String context, PrintStream out,
                                           SubAgent.ForkContext workerForkContext) {
        return executeWorkerOnce(step, worker, taskMsg, context, out,
                workerForkContext, LlmClient.ToolChoice.AUTO);
    }

    private AgentMessage executeWorkerOnce(ExecutionStep step, SubAgent worker, AgentMessage taskMsg,
                                           String context, PrintStream out,
                                           SubAgent.ForkContext workerForkContext,
                                           LlmClient.ToolChoice toolChoice) {
        try {
            ToolRegistry registry = activeToolRegistry();
            String completionToolName = TeamWorkerProtocol.completionToolName(
                    step.type(), toolChoice);
            AgentMessage result = registry.runWithResourceLease(step.id(), () -> workerForkContext == null
                    ? worker.executeWithContext(taskMsg, context, out, toolChoice,
                            completionToolName)
                    : worker.executeForkedWithContext(
                            taskMsg, context, workerForkContext, out, toolChoice,
                            completionToolName));
            recordAgentMessage(worker.getName(), step.id(), "Worker 执行完成", result);
            return result;
        } finally {
            activeToolRegistry().releaseResourceLeases(step.id());
        }
    }

    private boolean shouldAcceptAfterRecoverableReviewerFailure(
            ExecutionStep step, String issues, boolean hardCheckExecuted) {
        return canDegradeReviewerFailure(
                isFinalIntegrationStep(step), isRecoverableReviewerFailure(issues), hardCheckExecuted);
    }

    static boolean canDegradeReviewerFailure(
            boolean finalIntegration, boolean recoverableFailure, boolean hardCheckExecuted) {
        return recoverableFailure && (hardCheckExecuted || finalIntegration);
    }

    private boolean isRecoverableReviewerFailure(String content) {
        return isTransientLlmError(content)
                || (content != null && content.contains("达到硬轮数上限"));
    }

    private boolean isTransientLlmError(String content) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("retryable=true")
                || lower.contains("api请求失败: 500")
                || lower.contains("server_error")
                || lower.contains("internal_server_error")
                || lower.contains("oauth2.googleapis.com/token")
                || lower.contains(" eof")
                || lower.contains("timeout")
                || lower.contains("temporarily")
                || lower.contains("rate limit")
                || lower.contains("429")
                || lower.contains("503")
                || lower.contains("502");
    }
    private ReviewDecision reviewWorkerResult(ExecutionStep step, SubAgent reviewer, String workerResult,
                                              PrintStream out, SubAgent.ForkContext reviewerForkContext) {
        PreReviewResult preReview = runPreReviewHook(step);
        if (!preReview.passed()) {
            recordAgentEvent(reviewer.getName(), step.id(),
                    "Pre-Review 未通过", preReview.feedback());
            out.println("⛔ 步骤 [" + step.id() + "] Pre-Review Hook 未通过，跳过 Reviewer LLM");
            out.println("   反馈: " + preReview.feedback() + "\n");
            return new ReviewDecision(false, preReview.feedback(), false,
                    preReview.hardCheckExecuted());
        }

        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        String reviewTask = buildReviewTask(step);
        List<String> reviewToolCalls = Collections.synchronizedList(new ArrayList<>());
        reviewer.setStructuredToolResultConsumer(result -> {
            memoryManager.addToolResult(result.name(), result.argumentsJson(), result.result(),
                    result.sideChannels());
            reviewToolCalls.add(result.name());
        });
        AgentMessage reviewResult = reviewerForkContext == null
                ? reviewer.review(reviewTask, workerResult, out)
                : reviewer.reviewForked(reviewTask, workerResult, reviewerForkContext, out);
        reviewer.clearHistory();
        recordAgentMessage(reviewer.getName(), step.id(), "Reviewer 审查完成", reviewResult);

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("❌ 步骤 [" + step.id()
                    + "] 审查阶段 LLM 调用失败，正在检查 Pre-Review 降级条件\n");
            return new ReviewDecision(false, "审查 LLM 故障：" + reviewResult.content(), true,
                    preReview.hardCheckExecuted());
        }
        if (requiresConcreteVerification(step) && reviewToolCalls.isEmpty()) {
            if (isVerificationStepWithPreReview(step)) {
                log.info("Reviewer did not call tools for verification step {}, accepting Pre-Review hard check as concrete verification", step.id());
            } else {
                return new ReviewDecision(false,
                        "Reviewer 未调用工具验证真实产物；文件/代码/命令类任务不能只根据 Worker 文字说明批准。",
                        false, preReview.hardCheckExecuted());
            }
        }

        return new ReviewDecision(parseReviewApproval(reviewResult.content()),
                parseReviewIssues(reviewResult.content()), false,
                preReview.hardCheckExecuted());
    }

    record ReviewDecision(boolean approved, String issues, boolean reviewerError,
                          boolean hardCheckExecuted) {
    }

    PreReviewResult runPreReviewHook(ExecutionStep step) {
        if (!requiresConcreteVerification(step) || !requiresJavaHardCheck(step)) {
            return PreReviewResult.skipped();
        }
        Path projectRoot = Path.of(activeToolRegistry().getProjectPath()).toAbsolutePath().normalize();
        PreReviewVerifier.Result result = preReviewVerifier.verify(projectRoot, step.id());
        return new PreReviewResult(
                result.passed(), result.hardCheckExecuted(), result.feedback());
    }

    private boolean requiresJavaHardCheck(ExecutionStep step) {
        String text = (step.type() + " " + step.description()).toLowerCase(Locale.ROOT);
        return text.contains("java")
                || text.contains(".java")
                || text.contains("cli")
                || text.contains("api")
                || text.contains("代码")
                || text.contains("编译")
                || text.contains("入口")
                || isFinalIntegrationStep(step);
    }

    private String buildRetryContext(String context, String issues) {
        StringBuilder retry = new StringBuilder(context == null ? "" : context);
        retry.append("\n\n上一次执行被拒绝。只做根因修复，不要重写无关代码。\n");
        retry.append("必须保留原始任务指定的 class / method / signature、已通过行为和已有生产文件结构。\n");
        retry.append("如果反馈来自 Pre-Review 编译失败，先读取报错文件和行号，再最小补丁修复。\n");
        retry.append("拒绝原因摘要：\n").append(summarizeRetryIssues(issues)).append("\n");
        return retry.toString();
    }

    private String summarizeRetryIssues(String issues) {
        if (issues == null || issues.isBlank()) {
            return "未提供具体原因；请重新验证入口、编译和验收点。";
        }
        String[] lines = issues.replace("\r", "").split("\n");
        StringBuilder summary = new StringBuilder();
        int kept = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            boolean important = kept < 8
                    || trimmed.contains("error:")
                    || trimmed.contains("错误")
                    || trimmed.contains("failed")
                    || trimmed.contains("missing")
                    || trimmed.contains("expected=")
                    || trimmed.contains("actual=")
                    || trimmed.contains("Reviewer 未调用工具");
            if (important) {
                summary.append("- ").append(trimmed).append("\n");
                kept++;
            }
            if (kept >= 14) {
                break;
            }
        }
        if (summary.isEmpty()) {
            return abbreviate(issues, 1200);
        }
        if (lines.length > kept) {
            summary.append("- ...<truncated>\n");
        }
        return summary.toString();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "\n...<truncated>";
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");
        if (currentUserTask != null && !currentUserTask.isBlank()) {
            context.append("原始用户任务：\n").append(currentUserTask).append("\n\n");
        }
        appendAcceptanceCriteriaSection(context, "本步骤必须满足以下验收点");
        context.append("当前步骤：").append(currentStep.description()).append("\n\n");
        if (redoTracker.isRedo(currentStep.id())) {
            context.append("⚠️ 本步骤上次执行失败，现在原位重做——请换一种思路实现，不要重复已失败的做法。\n");
            String lastFailure = redoTracker.lastFailureReason(currentStep.id());
            if (!lastFailure.isBlank()) {
                context.append("上次失败原因：").append(abbreviate(lastFailure, 300)).append("\n");
            }
            context.append("\n");
        }
        // resume 跨进程恢复：WorkingMemory 已空、StepRedoTracker 无上次失败原因，失败步骤的副作用
        // （已写文件 + 失败摘要）从 checkpoint 注入，让重做的 Worker 知道上次失败留下了什么。
        ExecutionArtifact failedArtifact = restoredFailedArtifacts.get(currentStep.id());
        if (failedArtifact != null) {
            if (!failedArtifact.modifiedResources().isEmpty()) {
                context.append("本步骤上次运行失败并已写入以下文件（副作用不可逆）：\n");
                for (String file : failedArtifact.modifiedResources()) {
                    context.append("- ").append(file).append('\n');
                }
                context.append("重做前必须先读取这些文件的当前内容，在其真实状态上修改，不要假设它们不存在。\n");
            }
            String failureSummary = failedArtifact.error().isBlank()
                    ? failedArtifact.summary()
                    : failedArtifact.error();
            if (!failureSummary.isBlank()) {
                context.append("上次失败摘要：").append(abbreviate(failureSummary, 300)).append("\n");
            }
            context.append("\n");
        }
        if (requiresJavaHardCheck(currentStep)) {
            context.append("Java 代码交付约束：严格保留原始任务指定的入口签名；优先使用简单命令式实现；完成前运行最小编译检查；重试时只做根因补丁。\n\n");
        }
        if (isFinalIntegrationStep(currentStep)) {
            context.append("所有步骤状态：\n");
            for (ExecutionStep step : steps) {
                if (!step.id().equals(currentStep.id())) {
                    context.append("[").append(step.id()).append("] ")
                            .append(step.status()).append(" - ")
                            .append(step.description()).append("\n");
                    if (step.result() != null && !step.result().isBlank()) {
                        context.append("结果预览：")
                                .append(step.result(), 0, Math.min(step.result().length(), 800))
                                .append("\n");
                    }
                    if (!step.modifiedFiles().isEmpty()) {
                        context.append("修改文件：").append(String.join(", ", step.modifiedFiles())).append("\n");
                    }
                }
            }
            context.append("\n");
        }

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (!step.modifiedFiles().isEmpty()) {
                    context.append("修改文件：\n");
                    for (String file : step.modifiedFiles()) {
                        context.append("- ").append(file).append('\n');
                    }
                    context.append("继续前请优先读取这些文件的当前内容，基于真实落盘状态衔接实现。\n");
                }
                if (step.result() != null && !step.result().isBlank()) {
                    context.append("结果：").append(previewDependencyResult(step.result())).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    /**
     * 依赖步骤结果预览：保留头部 + 尾部，避免精确实体（入口签名、路径、验收结论）
     * 被单一截断点切掉——结论与验证输出通常在结果末尾。
     */
    private static String previewDependencyResult(String result) {
        final int maxChars = 2_000;
        final int headChars = 1_500;
        final int tailChars = 400;
        if (result == null || result.length() <= maxChars) {
            return result == null ? "" : result;
        }
        // 提取验收标准部分，完整保留不截断
        String criteria = extractAcceptanceCriteria(result);
        String nonCriteria = removeFirst(result, criteria);
        if (nonCriteria.length() <= maxChars) {
            return result;
        }
        // 普通内容部分做截断，验收标准放在最后完整保留
        String preview = nonCriteria.substring(0, headChars)
                + "\n...<中间内容已截断>...\n"
                + nonCriteria.substring(nonCriteria.length() - tailChars);
        if (!criteria.isEmpty()) {
            preview += "\n\n验收标准（完整保留）：\n" + criteria;
        }
        return preview;
    }

    private static String removeFirst(String text, String target) {
        if (text == null || text.isEmpty() || target == null || target.isEmpty()) {
            return text == null ? "" : text;
        }
        int start = text.indexOf(target);
        if (start < 0) {
            return text;
        }
        return text.substring(0, start) + text.substring(start + target.length());
    }

    private static String extractAcceptanceCriteria(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = indexOfAcceptanceCriteriaKey(text);
        if (start >= 0) {
            int arrayStart = text.indexOf('[', start);
            if (arrayStart >= 0) {
                int arrayEnd = findBalancedJsonArrayEnd(text, arrayStart);
                if (arrayEnd > arrayStart) {
                    return text.substring(start, arrayEnd).trim();
                }
            }
        }
        start = text.indexOf("验收标准");
        if (start < 0) {
            return "";
        }
        return text.substring(start, findLabeledSectionEnd(text, start)).trim();
    }

    private static int indexOfAcceptanceCriteriaKey(String text) {
        int quoted = text.indexOf("\"acceptance_criteria\"");
        return quoted >= 0 ? quoted : text.indexOf("acceptance_criteria");
    }

    private static int findLabeledSectionEnd(String text, int start) {
        int doubleNewline = text.indexOf("\n\n", start);
        return doubleNewline > start ? doubleNewline : text.length();
    }

    private static int findBalancedJsonArrayEnd(String text, int arrayStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = arrayStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private boolean isVerificationStepWithPreReview(ExecutionStep step) {
        if (step == null || !requiresJavaHardCheck(step)) {
            return false;
        }
        String text = ((step.type() == null ? "" : step.type()) + " "
                + (step.description() == null ? "" : step.description())).toLowerCase(Locale.ROOT);
        return text.contains("verification")
                || text.contains("verify")
                || text.contains("test")
                || text.contains("compile")
                || text.contains("验证")
                || text.contains("编译");
    }
    private String buildReviewTask(ExecutionStep step) {
        StringBuilder task = new StringBuilder();
        if (currentUserTask != null && !currentUserTask.isBlank()) {
            task.append("原始用户任务：\n").append(currentUserTask).append("\n\n");
        }
        appendAcceptanceCriteriaSection(task, "逐条验证以下验收点，每条必须单独检查并输出证据");
        task.append("当前步骤：").append(step.description());
        if (requiresConcreteVerification(step)) {
            task.append("\n\n审查要求：")
                    .append("\n1. 必须调用工具检查真实产物，至少确认相关文件/入口/API 是否存在")
                    .append("\n2. 如果步骤涉及代码，运行可行的最小编译或自检命令")
                    .append("\n3. 仅凭执行者文字说明不得批准")
                    .append("\n4. 每条验收标准必须逐条核对，不能只抽查")
                    .append("\n5. 输出 JSON 时 criteria_results 必须包含所有验收标准，不能遗漏");
        }
        return task.toString();
    }

    private void appendAcceptanceCriteriaSection(StringBuilder sb, String title) {
        if (currentAcceptanceCriteria == null || currentAcceptanceCriteria.isEmpty()) {
            return;
        }
        sb.append("⚠️ [关键上下文，不可压缩或省略] ").append(title).append("：\n");
        for (AcceptanceCriterion criterion : currentAcceptanceCriteria) {
            sb.append(criterion.formatForPrompt()).append("\n");
        }
        sb.append("\n");
    }

    private boolean requiresConcreteVerification(ExecutionStep step) {
        String text = (step.type() + " " + step.description()).toLowerCase(Locale.ROOT);
        return text.contains("file")
                || text.contains("write")
                || text.contains("command")
                || text.contains("code")
                || text.contains("java")
                || text.contains("cli")
                || text.contains("api")
                || text.contains("入口")
                || text.contains("文件")
                || text.contains("代码")
                || text.contains("编译")
                || isFinalIntegrationStep(step);
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无"
                    : String.join(", ", step.dependencies());
            sb.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), deps));
        }
        return sb.toString();
    }

    /**
     * 构建最终汇总。
     *
     * 注意：Worker/Reviewer 的完整输出在执行阶段已经通过流式渲染打印给用户，
     * 此处只返回"步骤状态 + 简短预览"作为总结，避免同一段内容被打印 2-3 次。
     */
    private String buildFinalResult(List<ExecutionStep> steps) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(step ->
                step.status() == StepStatus.COMPLETED);
        boolean hasFailedSteps = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);

        if (allCompleted) {
            result.append("✅ 多 Agent 协作任务完成！\n\n");
        } else if (hasFailedSteps) {
            result.append("⚠️ 多 Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ 多 Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");

        for (ExecutionStep step : steps) {
            result.append("[").append(step.id()).append("] ");
            if (step.status() == StepStatus.COMPLETED) {
                result.append("✅ ");
            } else if (step.status() == StepStatus.FAILED) {
                result.append("❌ ");
            } else {
                result.append("⏳ ");
            }
            result.append(step.description()).append("\n");

            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 120
                        ? step.result().substring(0, 120) + "..."
                        : step.result();
                result.append("   结果：").append(preview).append("\n");
            }
        }

        return result.toString();
    }
}
