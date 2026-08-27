package com.devcli.agent;

import com.devcli.config.ConfigResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmTraceLogger;
import com.devcli.lsp.LspDiagnosticReport;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.MemoryManager;
import com.devcli.memory.TokenBudget;
import com.devcli.context.ContextProfile;
import com.devcli.plan.*;
import com.devcli.prompt.PromptAssembler;
import com.devcli.prompt.PromptContext;
import com.devcli.prompt.PromptMode;
import com.devcli.runtime.CancellationContext;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.util.AnsiStyle;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolRegistry.ToolExecutionResult;
import com.devcli.tool.ToolRegistry.ToolInvocation;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import com.devcli.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final PlanTaskWorkspaceExecutor taskWorkspaceExecutor;
    private final ThreadLocal<ToolRegistry> activeTaskToolRegistry = new ThreadLocal<>();
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final ConversationHistoryCompactor historyCompactor;
    private final PrintStream out;
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> ruleContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private SkillContextBuffer skillContextBuffer;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();
    private final TraceRecorder traceRecorder = new TraceRecorder();

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.taskWorkspaceExecutor = new PlanTaskWorkspaceExecutor(this.toolRegistry);
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.historyCompactor = new ConversationHistoryCompactor(llmClient);
        AgentRuntimeSupport.configureCompactor(
                historyCompactor,
                this.memoryManager,
                this.toolRegistry,
                this::buildPostCompactRestoreSection,
                this::buildCompactBoundaryRuntimeState);
        AgentRuntimeSupport.bindMemory(this.toolRegistry, this.memoryManager);
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    /**
     * 注入 Sticky Memory 渲染源（PR-B）：与 Agent 一致语义，由 Main 启动时接进来。
     */
    public void setRuleContextSupplier(Supplier<String> ruleContextSupplier) {
        this.ruleContextSupplier = ruleContextSupplier == null ? () -> "" : ruleContextSupplier;
        memoryManager.setRuleContextSupplier(this.ruleContextSupplier);
    }

    /** @deprecated 使用 {@link #setRuleContextSupplier(Supplier)}。 */
    @Deprecated
    public void setStickyMemorySupplier(Supplier<String> supplier) { setRuleContextSupplier(supplier); }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setSkillContextBuffer(SkillContextBuffer skillContextBuffer) {
        this.skillContextBuffer = skillContextBuffer;
    }

    private void maybeCompactHistory(List<LlmClient.Message> messages, PrintStream out) {
        if (historyCompactor == null) return;
        ContextProfile profile = memoryManager.getContextProfile();
        int toolDefinitionTokens = TokenBudget.estimateToolDefinitionsTokens(
                toolRegistry.getToolDefinitions());
        int trigger = profile.historyTriggerTokens(toolDefinitionTokens);
        try {
            historyCompactor.setMicrocompactOutputRoot(java.nio.file.Path.of(toolRegistry.getProjectPath()));
            boolean compacted = historyCompactor.compactIfNeeded(messages, trigger);
            if (compacted && out != null) {
                out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private String buildSkillIndex(String activationText) {
        return AgentRuntimeSupport.buildSkillIndex(
                skillRegistry, activationText, toolRegistry, log);
    }

    private String prependSkillBodies(String content) {
        return AgentRuntimeSupport.prependSkillBodies(skillContextBuffer, content);
    }

    private String buildPostCompactRestoreSection() {
        return AgentRuntimeSupport.buildPostCompactRestoreSection(
                memoryManager.buildPostCompactRestoreSection(), toolRegistry, skillContextBuffer);
    }

    private com.devcli.memory.CompactBoundaryRuntimeState buildCompactBoundaryRuntimeState() {
        return AgentRuntimeSupport.buildCompactBoundaryRuntimeState(
                memoryManager, toolRegistry, skillContextBuffer, false);
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        String sessionTaskId = "plan-run-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        memoryManager.beginTask(sessionTaskId);
        memoryManager.setActiveProjectScope(toolRegistry.getProjectPath());
        toolRegistry.prefetchToolDefinitionsForInput(userInput);
        memoryManager.addUserMessage(userInput);
        StreamState streamState = new StreamState();
        String resultForSummary = "";
        try {
            if (CancellationContext.isCancelled()) {
                resultForSummary = "⏹️ 已取消当前计划执行。";
                return resultForSummary;
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                memoryManager.addAssistantMessage("[计划结果] " + outcome.result());
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                resultForSummary = "";
                return "";
            }
            resultForSummary = outcome.result();
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            memoryManager.addAssistantMessage(errorMessage);
            resultForSummary = errorMessage;
            return errorMessage;
        } finally {
            memoryManager.completeTask(sessionTaskId, userInput, resultForSummary,
                    toolRegistry.getProjectPath());
            memoryManager.endTask(sessionTaskId);
            scheduleSessionPreSummaryMaintenance(userInput, resultForSummary, streamState);
        }
    }

    private void scheduleSessionPreSummaryMaintenance(String userInput, String result, StreamState streamState) {
        List<LlmClient.Message> turnHistory = new ArrayList<>();
        turnHistory.add(LlmClient.Message.system("PLAN_TURN"));
        turnHistory.add(LlmClient.Message.user(userInput == null ? "" : userInput));
        if (result != null && !result.isBlank()) {
            turnHistory.add(LlmClient.Message.assistant(result));
        }
        memoryManager.maintainSessionPreSummaryAfterTurnAsync(
                turnHistory,
                streamState.turnToolCalls(),
                streamState.largestToolResultChars());
    }

    /**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState);
    }

    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        // 每一版图都必须经过同一审阅入口；默认无头 handler 仍可自动批准。
        for (int replanAttempt = 0; ; replanAttempt++) {
            while (true) {
                PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
                if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                    break;
                }
                if (decision.action() == PlanReviewAction.CANCEL) {
                    return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
                }
                String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
                if (feedback.isEmpty()) {
                    break;
                }
                out.println("📝 已收到补充要求，正在生成计划修订...\n");
                plan = planner.reviseForFeedback(plan, feedback);
            }

            String result = executePlan(plan, streamState);
            if (replanAttempt >= MAX_REPLAN_ATTEMPTS || !plan.hasFailed() || result.contains("⏹️")) {
                return PlanRunOutcome.executed(result);
            }
            out.println("🔄 计划执行出现失败，尝试重新规划（第 " + (replanAttempt + 1) + "/" + MAX_REPLAN_ATTEMPTS + " 次）...\n");
            plan = planner.replan(plan, "计划执行中有任务失败，请重新规划仅未完成或失败的部分");
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        LinkedHashMap<String, String> ledgerSteps = new LinkedHashMap<>();
        for (Task ledgerTask : plan.getAllTasks()) {
            ledgerSteps.put(ledgerTask.getId(), ledgerTask.getDescription());
        }
        memoryManager.setTaskLedgerPlan(plan.getId(), plan.getGoal(), ledgerSteps);
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<PlanTaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, streamState);
            for (PlanTaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.setModifiedFiles(batchResult.modifiedFiles());
                    task.setResultSummary(batchResult.resultSummary());
                    task.markCompleted(batchResult.result());
                    memoryManager.completeTaskStep(task.getId());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                task.setModifiedFiles(batchResult.modifiedFiles());
                task.setResultSummary(batchResult.resultSummary());
                task.markFailed(error.getMessage());
                memoryManager.failTaskStep(task.getId(), error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            String reason = "计划未能继续推进，存在未满足依赖的任务";
            return "⚠️ " + FailureFeedback.fromReason(reason)
                    .withRetryInstruction("修正任务依赖后重新发起 `/plan`")
                    .render();
        }

        // Bug #7 修复：始终调用 buildFinalResult 生成完整摘要（包含成功和失败）
        // 如果有失败，在摘要前添加失败信息
        String planSummary = buildFinalResult(plan, streamedTaskOutputs);
        if (!finalResult.isEmpty()) {
            planSummary = "⚠️ 部分任务失败:\n" + finalResult + "\n\n" + planSummary;
        }

        if (plan.hasFailed()) {
            plan.markFailed();
            String failureReason = finalResult.isEmpty()
                    ? "计划部分完成，有任务失败"
                    : finalResult.toString();
            String guidance = FailureFeedback.fromReason(failureReason)
                    .withRetryInstruction("修正失败任务后重新发起 `/plan`")
                    .render();
            if (planSummary.isBlank()) {
                return "⚠️ " + guidance;
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary
                    + "\n\n" + guidance;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<PlanTaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                           StreamState streamState) {
        PlanTaskBatchExecutor batchExecutor = new PlanTaskBatchExecutor(
                out,
                task -> {
                    task.markStarted();
                    memoryManager.startTaskStep(task.getId());
                },
                (task, taskOut) -> executeTaskWithArtifact(plan, task, streamState, taskOut),
                task -> consumeTaskModifiedFiles(task.getId()));
        return batchExecutor.execute(executableTasks);
    }

    private PlanTaskExecutionResult executeTaskWithArtifact(ExecutionPlan plan, Task task,
                                                        StreamState streamState, PrintStream out) {
        PlanTaskWorkspaceExecutor.Execution<TaskRunResult> execution =
                taskWorkspaceExecutor.execute(
                        task.getId(),
                        requiresIsolatedWorkspace(task),
                        activeRegistry -> {
                            activeTaskToolRegistry.set(activeRegistry);
                            try {
                                return executeTaskUnchecked(
                                        plan.getGoal(), plan, task, streamState, out);
                            } finally {
                                activeTaskToolRegistry.remove();
                            }
                        });
        if (execution.failed()) {
            return PlanTaskExecutionResult.failure(
                    task, execution.error(), execution.modifiedFiles());
        }
        TaskRunResult taskResult = execution.value();
        return PlanTaskExecutionResult.success(
                task, taskResult.result(), taskResult.streamedOutput(), execution.modifiedFiles());
    }

    private List<String> consumeTaskModifiedFiles(String taskId) {
        return activeTaskToolRegistry().consumeStepModifiedFiles(taskId);
    }

    private ToolRegistry activeTaskToolRegistry() {
        ToolRegistry active = activeTaskToolRegistry.get();
        return active == null ? toolRegistry : active;
    }

    private boolean requiresIsolatedWorkspace(Task task) {
        if (!ConfigResolver.booleanValue(
                "devcli.workspace.isolation.enabled",
                "DEVCLI_WORKSPACE_ISOLATION_ENABLED",
                true)) {
            return false;
        }
        return task.getType() == Task.TaskType.FILE_WRITE
                || task.getType() == Task.TaskType.COMMAND
                || task.getType() == Task.TaskType.VERIFICATION;
    }

    private static final int MAX_TASK_ITERATIONS = 5;

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTaskUnchecked(String goal, ExecutionPlan plan, Task task,
                                               StreamState streamState, PrintStream out) {
        try {
            return executeTask(goal, plan, task, streamState, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out) throws IOException {
        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = prependSkillBodies(buildTaskContext(goal, plan, task));
        // 长期记忆检索结果与 skill 索引、工作记忆一起作为当轮快照前置到任务消息，
        // 不进 system prompt——否则每任务/每迭代都会让前缀失配
        String turnContext = buildTurnContext(memoryContext, taskInput);
        if (!turnContext.isBlank()) {
            taskInput = turnContext + "\n\n" + taskInput;
        }

        List<LlmClient.Message> messages = new ArrayList<>(Arrays.asList(
                LlmClient.Message.system(buildTaskSystemPrompt(task)),
                ImageReferenceParser.userMessage(
                        taskInput,
                        Path.of(activeTaskToolRegistry().getProjectPath()))
        ));

        StringBuilder allResults = new StringBuilder();
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);
        TraceContext traceContext = TraceContext.root("plan-task");
        traceRecorder.record(traceContext, "task.start", Map.of(
                "taskId", task.getId(),
                "taskType", task.getType().name(),
                "description", task.getDescription()
        ));

        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        return new AgentExecutionEngine<TaskRunResult>(
                llmClient, budget, HookLifecycle.load(activeTaskToolRegistry())).run(
                new AgentExecutionEngine.Delegate<>() {
                    @Override
                    public List<LlmClient.Message> history() {
                        return messages;
                    }

                    @Override
                    public List<LlmClient.Tool> toolDefinitions(int iteration) {
                        return activeTaskToolRegistry().getToolDefinitions();
                    }

                    @Override
                    public com.devcli.tool.ToolPresentation toolPresentation(String toolName) {
                        return activeTaskToolRegistry().toolPresentation(toolName);
                    }

                    @Override
                    public LlmClient.StreamListener streamListener() {
                        return streamRenderer;
                    }

                    @Override
                    public int maxIterations() {
                        return MAX_TASK_ITERATIONS;
                    }

                    @Override
                    public void beforeIteration(int iteration, AgentBudget currentBudget) {
                        // 不在迭代内重建 system prompt：它已只含任务身份与会话级稳定内容，
                        // 逐字节稳定才能让其后的任务历史命中前缀缓存。
                        injectPendingLspDiagnostics(messages, out);
                        maybeCompactHistory(messages, out);
                    }

                    @Override
                    public void afterResponse(LlmClient.ChatResponse response, int iteration,
                                              AgentBudget currentBudget) {
                        traceRecorder.record(traceContext, "llm.response", Map.of(
                                "taskId", task.getId(),
                                "iteration", iteration,
                                "toolCalls", response.toolCalls() == null ? 0 : response.toolCalls().size(),
                                "inputTokens", response.inputTokens(),
                                "outputTokens", response.outputTokens()
                        ));
                        LlmTraceLogger.logReasoning(log,
                                "plan-task task=" + task.getId() + " iteration=" + iteration,
                                llmClient,
                                response.reasoningContent());
                        log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                                task.getId(),
                                iteration,
                                response.toolCalls() == null ? 0 : response.toolCalls().size(),
                                response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                                response.content() == null ? 0 : response.content().length());
                    }

                    @Override
                    public void beforeToolExecution(LlmClient.ChatResponse response, int iteration,
                                                    AgentBudget currentBudget) {
                        printToolCalls(out, response.toolCalls());
                        streamRenderer.resetBetweenIterations();
                    }

                    @Override
                    public List<ToolExecutionResult> executeTools(List<LlmClient.ToolCall> toolCalls,
                                                                  int iteration) {
                        return executeToolCalls(task.getId(), toolCalls);
                    }

                    @Override
                    public void afterToolResults(LlmClient.ChatResponse response,
                                                 List<ToolExecutionResult> toolResults,
                                                 int iteration,
                                                 AgentBudget currentBudget) {
                        streamState.recordToolResults(toolResults);
                        for (ToolExecutionResult toolResult : toolResults) {
                            traceRecorder.record(traceContext, "tool.result", Map.of(
                                    "taskId", task.getId(),
                                    "iteration", iteration,
                                    "tool", toolResult.name(),
                                    "elapsedMillis", toolResult.elapsedMillis(),
                                    "timedOut", toolResult.timedOut(),
                                    "resultPreview", preview(toolResult.result(), 300)
                            ));
                            memoryManager.addToolResult(
                                    toolResult.name(), toolResult.argumentsJson(), toolResult.result(),
                                    toolResult.sideChannels());
                            allResults.append(toolResult.result()).append("\n");
                        }
                        appendImageToolMessages(messages, toolResults);
                    }

                    @Override
                    public String instructionAfterToolResults(
                            LlmClient.ChatResponse response,
                            List<ToolExecutionResult> toolResults,
                            int iteration,
                            AgentBudget currentBudget) {
                        return memoryManager.drainCurrentStateConflictInstruction();
                    }

                    @Override
                    public java.util.Map<String, String> refreshStaleContext() {
                        ToolRegistry registry = activeTaskToolRegistry();
                        return registry.refreshStaleContext(task.getId());
                    }

                    @Override
                    public String contextScope() {
                        return task.getId();
                    }

                    @Override
                    public TaskRunResult completed(LlmClient.ChatResponse response,
                                                   AgentBudget currentBudget) {
                        memoryManager.recordTokenUsage(
                                currentBudget.totalInputTokens(),
                                currentBudget.totalOutputTokens(),
                                currentBudget.totalCachedInputTokens());
                        if (!allResults.isEmpty()
                                && (response.content() == null || response.content().isBlank())) {
                            String toolOnlyResult = allResults.toString().trim();
                            if (!toolOnlyResult.isBlank()) {
                                memoryManager.addAssistantMessage(
                                        "[计划任务 " + task.getId() + "] " + toolOnlyResult);
                            }
                            streamRenderer.finish();
                            return TaskRunResult.of(
                                    toolOnlyResult, streamRenderer.hasStreamedOutput());
                        }
                        if (response.content() != null && !response.content().isBlank()) {
                            memoryManager.addAssistantMessage(
                                    "[计划任务 " + task.getId() + "] " + response.content());
                        }
                        streamRenderer.finish();
                        return TaskRunResult.of(
                                response.content(), streamRenderer.hasStreamedOutput());
                    }

                    @Override
                    public TaskRunResult cancelled(AgentBudget currentBudget) {
                        streamRenderer.finish();
                        return TaskRunResult.of(
                                "⏹️ 已取消任务 [" + task.getId() + "]。",
                                streamRenderer.hasStreamedOutput());
                    }

                    @Override
                    public TaskRunResult budgetExceeded(AgentBudget.ExitReason reason,
                                                        AgentBudget currentBudget) {
                        streamRenderer.finish();
                        return TaskRunResult.of(
                                FailureFeedback.forBudget(reason, currentBudget).render(),
                                streamRenderer.hasStreamedOutput());
                    }

                    @Override
                    public TaskRunResult iterationLimitReached(AgentBudget currentBudget) {
                        String fallbackResult = allResults.toString().trim();
                        if (!fallbackResult.isBlank()) {
                            memoryManager.addAssistantMessage(
                                    "[计划任务 " + task.getId() + "] " + fallbackResult);
                        }
                        streamRenderer.finish();
                        String guidance = FailureFeedback.forBudget(
                                AgentBudget.ExitReason.HARD_ITERATION_LIMIT, currentBudget).render();
                        return TaskRunResult.of(
                                fallbackResult.isBlank()
                                        ? guidance
                                        : fallbackResult + "\n\n" + guidance,
                                streamRenderer.hasStreamedOutput());
                    }

                    @Override
                    public TaskRunResult failed(IOException error, AgentBudget currentBudget) {
                        throw new UncheckedIOException(error);
                    }
                });
    }

    /**
     * 任务级 system prompt。只含任务身份（type / description）与会话级稳定内容；
     * 工作记忆与 skill 索引改由 {@link #buildTurnContext(String, String)} 注入任务消息，
     * 使 messages[0] 在同一任务的全部迭代中逐字节稳定，保住前缀缓存。
     */
    private String buildTaskSystemPrompt(Task task) {
        return promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .ruleContext(buildRuleContext())
                .build());
    }

    /** 组装当轮上下文快照（长期记忆检索结果 / skill 索引 / 工作记忆），前置到任务消息。 */
    private String buildTurnContext(String memoryContext, String activationText) {
        return promptAssembler.assembleTurnContext(PromptContext.builder()
                .memoryContext(memoryContext)
                .sessionMemory(memoryManager.buildSessionMemorySection())
                .skillIndex(buildSkillIndex(activationText))
                .build());
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
            return "";
        }
    }

    private String buildRuleContext() {
        try {
            String rules = ruleContextSupplier.get();
            return rules == null ? "" : rules.trim();
        } catch (Exception e) {
            log.warn("Failed to render rule context for plan task", e);
            return "";
        }
    }

    private void injectPendingLspDiagnostics(List<LlmClient.Message> messages, PrintStream out) {
        LspDiagnosticReport report = activeTaskToolRegistry().flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        messages.add(LlmClient.Message.internalUser(report.promptText()));
        out.println(report.displayText());
        log.info("Injected LSP diagnostics into plan task conversation");
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<ToolExecutionResult> executeToolCalls(String taskId, List<LlmClient.ToolCall> toolCalls) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = activeTaskToolRegistry().executeTools(invocations);
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private void appendImageToolMessages(List<LlmClient.Message> messages, List<ToolExecutionResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return;
        }
        for (ToolExecutionResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<LlmClient.ContentPart> parts = new ArrayList<>();
            parts.add(LlmClient.ContentPart.text("工具 " + result.name() + " 返回了图片内容，请结合上面的工具文本结果分析。"));
            parts.addAll(result.imageParts());
            messages.add(LlmClient.Message.user(parts, LlmClient.MessageSource.TOOL));
        }
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "grep_code" -> "🔎 精确搜索代码 " + count + " 次";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            case "list_memory" -> "🧠 查看长期记忆 " + count + " 次";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "grep_code" -> "pattern";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                case "list_memory" -> "limit";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;
        private int turnToolCalls;
        private int largestToolResultChars;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void recordToolResults(List<ToolExecutionResult> toolResults) {
            if (toolResults == null || toolResults.isEmpty()) {
                return;
            }
            turnToolCalls += toolResults.size();
            for (ToolExecutionResult toolResult : toolResults) {
                largestToolResultChars = Math.max(largestToolResultChars,
                        toolResult.result() == null ? 0 : toolResult.result().length());
            }
        }

        private int turnToolCalls() {
            return turnToolCalls;
        }

        private int largestToolResultChars() {
            return largestToolResultChars;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final AgentStreamPresenter delegate;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.delegate = AgentStreamPresenter.task(taskId, out, streamState::markStreamed);
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            delegate.onReasoningDelta(delta);
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            delegate.onContentDelta(delta);
        }

        private synchronized void finish() {
            delegate.finish();
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            delegate.resetBetweenIterations();
        }

        private synchronized boolean hasStreamedOutput() {
            return delegate.hasStreamedOutput();
        }
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResultSummary() != null && !dep.getResultSummary().isBlank()) {
                    context.append("  结论: ").append(dep.getResultSummary()).append("\n");
                }
                if (!dep.getModifiedFiles().isEmpty()) {
                    context.append("  修改文件: ").append(String.join(", ", dep.getModifiedFiles())).append("\n");
                }
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
