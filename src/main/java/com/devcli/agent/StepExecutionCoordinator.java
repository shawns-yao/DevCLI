package com.devcli.agent;

import com.devcli.event.RunEventSink;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.plan.ExecutionArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicInteger;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.concurrent.CancellationContext;
import com.devcli.tool.ToolRegistry;
import com.devcli.trace.TraceContext;
import com.devcli.trace.TraceRecorder;
import com.devcli.util.AnsiStyle;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 计划依赖调度与 checkpoint 投影；子任务统一经过 delegate_task 的执行原语。 */
final class StepExecutionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(StepExecutionCoordinator.class);

    @FunctionalInterface
    interface ContextBuilder {
        String build(List<AgentOrchestrator.ExecutionStep> steps,
                     AgentOrchestrator.ExecutionStep currentStep);
    }

    @FunctionalInterface
    interface FinalResultBuilder {
        String build(List<AgentOrchestrator.ExecutionStep> steps,
                     Map<String, Integer> retryCount);
    }

    record Hooks(ContextBuilder buildContext,
                 FinalResultBuilder buildFinalResult) {
        Hooks {
            Objects.requireNonNull(buildContext, "buildContext");
            Objects.requireNonNull(buildFinalResult, "buildFinalResult");
        }
    }

    private final PrintStream out;
    private final Supplier<List<String>> workersSupplier;
    private final SubAgent reviewer;
    private final LlmClient llmClient;
    private final LlmClient reviewerLlmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final TraceRecorder traceRecorder;
    private final OrchestrationRunState runState;
    private final PlanCoordinator planCoordinator;
    private final ReviewCoordinator reviewCoordinator;
    private final CheckpointCoordinator checkpointCoordinator;
    private final Hooks hooks;
    private final ThreadLocal<ToolRegistry> activeStepToolRegistry = new ThreadLocal<>();
    private boolean requireWorkerToolEvidence;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AtomicInteger spawnCounter = new AtomicInteger();
    private final Supplier<RunEventSink> events;
    private final Supplier<String> systemPrompt;
    private DelegationState delegationState;
    private AgentBudget delegationBudget;
    private String delegationSystemPrompt;

    StepExecutionCoordinator(PrintStream out,
                             Supplier<List<String>> workersSupplier,
                             SubAgent reviewer,
                             LlmClient llmClient,
                             LlmClient reviewerLlmClient,
                             ToolRegistry toolRegistry,
                             MemoryManager memoryManager,
                             TraceRecorder traceRecorder,
                             OrchestrationRunState runState,
                             PlanCoordinator planCoordinator,
                             ReviewCoordinator reviewCoordinator,
                             CheckpointCoordinator checkpointCoordinator,
                             Hooks hooks, Supplier<RunEventSink> events, Supplier<String> systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.events = events;
        this.out = Objects.requireNonNull(out, "out");
        this.workersSupplier = Objects.requireNonNull(workersSupplier, "workersSupplier");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.reviewerLlmClient = Objects.requireNonNull(reviewerLlmClient, "reviewerLlmClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder");
        this.runState = Objects.requireNonNull(runState, "runState");
        this.planCoordinator = Objects.requireNonNull(planCoordinator, "planCoordinator");
        this.reviewCoordinator = Objects.requireNonNull(reviewCoordinator, "reviewCoordinator");
        this.checkpointCoordinator = Objects.requireNonNull(
                checkpointCoordinator, "checkpointCoordinator");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    void setRequireWorkerToolEvidence(boolean required) {
        requireWorkerToolEvidence = required;
    }

    ToolRegistry activeToolRegistry() {
        ToolRegistry active = activeStepToolRegistry.get();
        return active == null ? toolRegistry : active;
    }

    String execute(List<AgentOrchestrator.ExecutionStep> steps, TraceContext traceContext) {
        delegationBudget = AgentBudget.fromLlmClient(llmClient);
        delegationSystemPrompt = systemPrompt.get();
        try (DelegationState state = new DelegationState(Path.of(toolRegistry.getProjectPath()))) {
            delegationState = state;
            syncWorkPlan(steps);
            return executePlan(steps, traceContext);
        }
    }

    private String executePlan(List<AgentOrchestrator.ExecutionStep> steps, TraceContext traceContext) {
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        Map<String, String> restoredRedoFailures = new HashMap<>();
        OrchestrationRunState.RedoRecovery redoRecovery = runState.consumeRedoRecovery();
        for (AgentCheckpoint.RedoAttemptRecord attempt : redoRecovery.attempts()) {
            restoredRedoFailures.put(attempt.stepId(), attempt.failureReason());
        }
        runState.redoTracker().restore(redoRecovery.counts(), restoredRedoFailures);
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前 Plan 任务。";
            }
            List<AgentOrchestrator.ExecutionStep> executable =
                    planCoordinator.getExecutableSteps(steps);
            boolean onlyFinalLeft = !executable.isEmpty()
                    && executable.stream().allMatch(AgentOrchestrator::isFinalIntegrationStep);
            if ((executable.isEmpty() || onlyFinalLeft) && resetFailedStepsForRedo(steps)) {
                continue;
            }
            if (executable.isEmpty()) {
                break;
            }
            if (executable.size() == 1
                    && AgentOrchestrator.isFinalIntegrationStep(executable.get(0))
                    && planCoordinator.shouldFuseFinalIntegration(steps)) {
                AgentOrchestrator.ExecutionStep finalStep = executable.get(0);
                String reason = "Final integration 熔断：失败步骤比例过高，停止让最终集成阶段强行修补。";
                commitStepUpdate(steps, finalStep.id(), finalStep.withFailed(reason));
                out.println("⛔ 步骤 [" + finalStep.id() + "] " + reason + "\n");
                continue;
            }
            batchIndex++;
            if (executable.size() == 1) {
                AgentOrchestrator.ExecutionStep step = executable.get(0);
                resolveAssignedWorker(step.id(), singleStepCursor++);
                runStep(step, steps, hooks.buildContext().build(steps, step), out);
            } else {
                runBatchParallel(batchIndex, executable, steps, traceContext);
            }
        }

        for (AgentOrchestrator.ExecutionStep step : steps) {
            if (step.status() == AgentOrchestrator.StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置步骤失败被跳过: "
                        + step.description());
            }
        }
        String finalResult = hooks.buildFinalResult().build(steps, retryCount);
        memoryManager.addAssistantMessage("[Plan结果] " + finalResult);
        AgentCheckpoint checkpoint = runState.checkpoint();
        checkpointCoordinator.finish(steps);
        if (checkpoint != null && runState.checkpoint() != null) {
            log.info("orchestration checkpoint retained for resume/post-mortem: {}",
                    checkpoint.getOrchestrationId());
        }
        return finalResult;
    }

    private boolean resetFailedStepsForRedo(List<AgentOrchestrator.ExecutionStep> steps) {
        boolean anyReset = false;
        for (int index = 0; index < steps.size(); index++) {
            AgentOrchestrator.ExecutionStep step = steps.get(index);
            if (AgentOrchestrator.isFinalIntegrationStep(step)
                    || step.status() != AgentOrchestrator.StepStatus.FAILED
                    || checkpointCoordinator.hasDeferredPatch(step.id())
                    || !runState.redoTracker().canRedo(step.id())) {
                continue;
            }
            int attempt = runState.redoTracker().markRedo(step.id(), step.result());
            checkpointCoordinator.recordRedo(
                    step.id(), attempt, step.result(), step.modifiedFiles());
            out.println(AnsiStyle.heading("🔁 步骤 [" + step.id()
                    + "] 失败，在原位换思路重做（第 " + attempt + "/"
                    + runState.redoTracker().maxRedoPerStep() + " 次）"));
            steps.set(index, step.withRedoPending());
            anyReset = true;
        }
        return anyReset;
    }

    synchronized String resolveAssignedWorker(String stepId, int preferredIndex) {
        List<String> workers = workersSupplier.get();
        String assignedWorkerId = checkpointCoordinator.assignedWorkerId(stepId);
        if (!assignedWorkerId.isBlank()) {
            return workers.stream()
                    .filter(worker -> worker.equals(assignedWorkerId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "checkpoint 步骤绑定的 Worker 不存在: " + assignedWorkerId));
        }
        String worker = workers.get(Math.floorMod(preferredIndex, workers.size()));
        checkpointCoordinator.assignStep(stepId, worker, reviewer.getName());
        return worker;
    }

    private void runBatchParallel(int batchIndex,
                                  List<AgentOrchestrator.ExecutionStep> executable,
                                  List<AgentOrchestrator.ExecutionStep> steps,
                                  TraceContext traceContext) {
        MultiAgentBatchExecutor batchExecutor = new MultiAgentBatchExecutor(
                out, workersSupplier.get().size(), toolRegistry, traceRecorder,
                new MultiAgentBatchExecutor.Hooks(
                        (id, index) -> resolveAssignedWorker(id, index),
                        step -> hooks.buildContext().build(steps, step),
                        (step, context, stepOut) -> runStep(step, steps, context, stepOut),
                        (step, reason) -> commitStepUpdate(
                                steps, step.id(), step.withFailed(reason))));
        batchExecutor.execute(batchIndex, executable, traceContext);
    }

    private synchronized void commitStepUpdate(List<AgentOrchestrator.ExecutionStep> steps,
                                               String stepId,
                                               AgentOrchestrator.ExecutionStep updated) {
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).id().equals(stepId)) {
                continue;
            }
            steps.set(index, updated);
            syncWorkPlan(steps);
            if (!updated.modifiedFiles().isEmpty()) {
                String source = updated.status() == AgentOrchestrator.StepStatus.COMPLETED
                        ? "Multi-Agent 步骤完成"
                        : "Multi-Agent 步骤失败";
                memoryManager.addVolatileFact(source + " [" + stepId + "] 修改文件: "
                        + String.join(", ", updated.modifiedFiles()));
            }
            checkpointCoordinator.recordStep(stepId, updated);
            return;
        }
    }

    private void runStep(AgentOrchestrator.ExecutionStep step,
                         List<AgentOrchestrator.ExecutionStep> steps,
                         String context, PrintStream stepOut) {
        memoryManager.runWithEvidenceScope(step.id(), () -> {
            try {
                boolean readOnly = switch (step.type().toUpperCase(Locale.ROOT)) {
                    case "FILE_READ", "ANALYSIS", "PLANNING" -> true;
                    default -> false;
                };
                commitStepUpdate(steps, step.id(), step.started());
                var checkpoint = new DelegationCheckpoint(checkpointCoordinator, step.id(),
                        Path.of(toolRegistry.getProjectPath()), reviewCoordinator.verifier(),
                        memoryManager::addToolResult, (workspace, report) -> {
                    activeStepToolRegistry.set(workspace.toolRegistry());
                    try {
                        if (AgentOrchestrator.isFinalIntegrationStep(step)) {
                            var review = reviewCoordinator.review(step, reviewer, report.toString(), stepOut, null);
                            report.put("final_review", review.issues());
                            if (review.hardCheckExecuted()) report.put("final_hard_check", review.hardCheckFailureKind().name());
                            if (review.hardCheckFailureKind() != PreReviewVerifier.FailureKind.NONE) {
                                report.put("hard_check_failure_kind", review.hardCheckFailureKind().name());
                            }
                            if (!review.approved() && !ReviewCoordinator.isReviewerAdvisory(review)) {
                                throw new IllegalStateException(review.issues());
                            }
                        }
                        return artifact(step, report);
                    } finally {
                        activeStepToolRegistry.remove();
                    }
                }, artifact -> commitStepUpdate(steps, step.id(),
                        new AgentOrchestrator.ExecutionStep(step.id(), step.description(), step.type(),
                                step.dependencies(), artifact)));
                DelegationSession delegation = new DelegationSession(toolRegistry,
                        role -> role.equals("reviewer") ? reviewerLlmClient : llmClient,
                        delegationBudget, delegationSystemPrompt, events.get(), spawnCounter, delegationState, checkpoint);
                Map<String, String> args = new HashMap<>();
                args.put("role", readOnly ? "explorer" : "worker");
                args.put("task", step.description());
                args.put("context", context + "\n\n" + memoryManager.workPlanForPrompt());
                args.put("task_spec", "{\"execution_kind\":\"agent_loop\",\"inputs\":\"approved plan\",\"scope\":\"current step\",\"done_condition\":\"finish step and provide tool evidence\"}");
                args.put("deliverable", "当前步骤的执行结果、工具证据和未解决项");
                if (!readOnly) args.put("allowed_write_paths", "[\"**\"]");
                ToolOutput output = delegation.execute(args, ToolExecutionContext.current(step.id()));
                JsonNode report = JSON.readTree(output.text());
                if (output.isSuccess()) {
                    if (readOnly) commitStepUpdate(steps, step.id(), new AgentOrchestrator.ExecutionStep(
                            step.id(), step.description(), step.type(), step.dependencies(), artifact(step, report)));
                    stepOut.println("✅ 步骤 [" + step.id() + "] 委派完成");
                } else {
                    commitStepUpdate(steps, step.id(), step.withFailed(output.text()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] " + output.text());
                }
            } catch (Exception e) {
                commitStepUpdate(steps, step.id(), step.withFailed(e.getMessage()));
                stepOut.println("❌ 步骤 [" + step.id() + "] " + e.getMessage());
            }
            return null;
        });
    }

    private ExecutionArtifact artifact(AgentOrchestrator.ExecutionStep step, JsonNode report) {
        boolean successfulEvidence = false;
        boolean mutationEvidence = false;
        for (JsonNode evidence : report.path("evidence")) {
            if (!"SUCCESS".equals(evidence.path("status").asText())) continue;
            successfulEvidence = true;
            var effect = toolRegistry.toolEffect(evidence.path("tool").asText());
            mutationEvidence |= effect == com.devcli.tool.ToolContract.ToolEffect.PROJECT_MUTATION
                    || effect == com.devcli.tool.ToolContract.ToolEffect.HOST_PROCESS;
        }
        if (reviewCoordinator.requiresConcreteVerification(step) && !mutationEvidence
                && !(AgentOrchestrator.isFinalIntegrationStep(step)
                && (successfulEvidence || report.has("final_hard_check")))) {
            throw new IllegalStateException("写入或命令步骤缺少成功执行证据，未提交候选补丁");
        }
        if (requireWorkerToolEvidence && report.path("evidence").isEmpty()) {
            throw new IllegalStateException("步骤没有实际工具执行证据");
        }
        StringBuilder summary = new StringBuilder("summary_source=ORCHESTRATOR; report_id=")
                .append(report.path("report_id").asText()).append("; hard_check=")
                .append(report.path("hard_check").asText("NOT_REQUIRED"));
        for (JsonNode evidence : report.path("evidence")) {
            if ("SUCCESS".equals(evidence.path("status").asText())) {
                summary.append("; tool=").append(evidence.path("tool").asText());
            }
        }
        return step.artifact().complete(report.toString(), summary.toString(), List.of(), System.currentTimeMillis());
    }

    private void syncWorkPlan(List<AgentOrchestrator.ExecutionStep> steps) {
        var planSteps = JSON.createArrayNode();
        boolean focusAssigned = false;
        for (var step : steps) {
            String label = "[" + step.id() + "] " + step.description();
            String status = "pending";
            if (step.status() == AgentOrchestrator.StepStatus.COMPLETED) status = "completed";
            else if (step.status() == AgentOrchestrator.StepStatus.RUNNING && !focusAssigned) {
                status = "in_progress";
                focusAssigned = true;
            }
            planSteps.addObject().put("step", label.substring(0, Math.min(300, label.length()))).put("status", status);
        }
        memoryManager.updateWorkPlan(Map.of("goal", runState.userTask().substring(0,
                Math.min(1000, runState.userTask().length())), "steps", planSteps.toString()));
    }
}
