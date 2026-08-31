package com.devcli.agent;

import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager;
import com.devcli.runtime.CancellationContext;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** DAG 调度、Worker 重试、隔离工作区与 PatchSet 应用的执行策略。 */
final class StepExecutionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(StepExecutionCoordinator.class);
    private static final int MAX_RETRIES_PER_STEP = 2;

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

    record Hooks(Consumer<SubAgent> configureAgent,
                 BiConsumer<SubAgent, AgentCheckpoint.RecoveryState> applyRecovery,
                 ContextBuilder buildContext,
                 FinalResultBuilder buildFinalResult) {
        Hooks {
            Objects.requireNonNull(configureAgent, "configureAgent");
            Objects.requireNonNull(applyRecovery, "applyRecovery");
            Objects.requireNonNull(buildContext, "buildContext");
            Objects.requireNonNull(buildFinalResult, "buildFinalResult");
        }
    }

    private static final class StepUpdateBuffer {
        private final String stepId;
        private AgentOrchestrator.ExecutionStep updated;
        private boolean verificationInfrastructureFailure;

        private StepUpdateBuffer(String stepId) {
            this.stepId = stepId;
        }
    }

    private final PrintStream out;
    private final Supplier<List<SubAgent>> workersSupplier;
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
    private final ThreadLocal<StepUpdateBuffer> activeStepUpdate = new ThreadLocal<>();
    private boolean requireWorkerToolEvidence;

    StepExecutionCoordinator(PrintStream out,
                             Supplier<List<SubAgent>> workersSupplier,
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
                             Hooks hooks) {
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
                updateStep(steps, finalStep.id(), finalStep.withFailed(reason));
                out.println("⛔ 步骤 [" + finalStep.id() + "] " + reason + "\n");
                continue;
            }
            batchIndex++;
            if (executable.size() == 1) {
                AgentOrchestrator.ExecutionStep step = executable.get(0);
                SubAgent worker = resolveAssignedWorker(step.id(), singleStepCursor++);
                runStep(step, steps, retryCount, worker, reviewer,
                        hooks.buildContext().build(steps, step), out, null, null);
                worker.clearHistory();
            } else {
                runBatchParallel(batchIndex, executable, steps, retryCount, traceContext);
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

    synchronized SubAgent resolveAssignedWorker(String stepId, int preferredIndex) {
        List<SubAgent> workers = workersSupplier.get();
        String assignedWorkerId = checkpointCoordinator.assignedWorkerId(stepId);
        if (!assignedWorkerId.isBlank()) {
            return workers.stream()
                    .filter(worker -> worker.getName().equals(assignedWorkerId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "checkpoint 步骤绑定的 Worker 不存在: " + assignedWorkerId));
        }
        SubAgent worker = workers.get(Math.floorMod(preferredIndex, workers.size()));
        checkpointCoordinator.assignStep(stepId, worker.getName(), reviewer.getName());
        return worker;
    }

    private void runBatchParallel(int batchIndex,
                                  List<AgentOrchestrator.ExecutionStep> executable,
                                  List<AgentOrchestrator.ExecutionStep> steps,
                                  Map<String, Integer> retryCount,
                                  TraceContext traceContext) {
        MultiAgentBatchExecutor batchExecutor = new MultiAgentBatchExecutor(
                out, workersSupplier.get(), reviewer, llmClient, toolRegistry, traceRecorder,
                new MultiAgentBatchExecutor.Hooks(
                        this::resolveAssignedWorker,
                        hooks.configureAgent(),
                        agent -> {
                            AgentCheckpoint checkpoint = runState.checkpoint();
                            if (checkpoint != null) {
                                hooks.applyRecovery().accept(agent, checkpoint.recoveryState());
                            }
                        },
                        step -> hooks.buildContext().build(steps, step),
                        (step, worker, localReviewer, context, stepOut,
                         workerForkContext, reviewerForkContext) ->
                                runStep(step, steps, retryCount, worker, localReviewer, context,
                                        stepOut, workerForkContext, reviewerForkContext),
                        (step, reason) -> updateStep(
                                steps, step.id(), step.withFailed(reason))));
        batchExecutor.execute(batchIndex, executable, traceContext);
    }

    private void runStep(AgentOrchestrator.ExecutionStep step,
                         List<AgentOrchestrator.ExecutionStep> steps,
                         Map<String, Integer> retryCount,
                         SubAgent worker,
                         SubAgent localReviewer,
                         String context,
                         PrintStream stepOut,
                         SubAgent.ForkContext workerForkContext,
                         SubAgent.ForkContext reviewerForkContext) {
        memoryManager.runWithEvidenceScope(step.id(), () -> {
            try {
                if (requiresIsolatedWorkspace(step)) {
                    runStepInIsolatedWorkspace(step, steps, retryCount, worker, localReviewer,
                            context, stepOut, workerForkContext, reviewerForkContext);
                    return null;
                }
                try {
                    toolRegistry.runWithToolAccess(ToolRegistry.ToolAccessScope.READ_ONLY, () -> {
                        runStepWithLease(step, steps, retryCount, worker, localReviewer, context,
                                stepOut, workerForkContext, reviewerForkContext);
                        return null;
                    });
                } finally {
                    toolRegistry.releaseResourceLeases(step.id());
                }
                return null;
            } finally {
                toolRegistry.forgetStaleWriteScope(step.id());
            }
        });
    }

    private void runStepInIsolatedWorkspace(
            AgentOrchestrator.ExecutionStep step,
            List<AgentOrchestrator.ExecutionStep> steps,
            Map<String, Integer> retryCount,
            SubAgent worker,
            SubAgent localReviewer,
            String context,
            PrintStream stepOut,
            SubAgent.ForkContext workerForkContext,
            SubAgent.ForkContext reviewerForkContext) {
        StepUpdateBuffer buffer = new StepUpdateBuffer(step.id());
        try (WorkspaceExecutionSession session =
                     WorkspaceExecutionSession.open(toolRegistry, step.id())) {
            CheckpointCoordinator.DeferredPatchRestore deferredPatch =
                    checkpointCoordinator.restoreDeferredPatch(step.id(), session.workspacePath());
            if (deferredPatch.present() && !deferredPatch.restored()) {
                String reason = "待验证 PatchSet 无法恢复到隔离工作区: " + deferredPatch.failure();
                commitStepUpdate(steps, step.id(), step.withFailed(reason));
                stepOut.println("❌ 步骤 [" + step.id() + "] " + reason + "\n");
                return;
            }
            String effectiveContext = deferredPatch.present()
                    ? context + "\n\n[恢复上下文] 上次因硬验证环境故障保留的 PatchSet"
                    + " 已恢复到当前隔离工作区。请检查现有修改并继续验证，不要假设文件仍是原始状态。"
                    : context;
            ToolRegistry isolatedRegistry = session.toolRegistry();
            SubAgent isolatedWorker = new SubAgent(
                    worker.getName(), worker.getRole(), llmClient, isolatedRegistry);
            SubAgent isolatedReviewer = new SubAgent(
                    localReviewer.getName(), localReviewer.getRole(),
                    reviewerLlmClient, isolatedRegistry);
            hooks.configureAgent().accept(isolatedWorker);
            hooks.configureAgent().accept(isolatedReviewer);
            AgentCheckpoint checkpoint = runState.checkpoint();
            if (checkpoint != null) {
                AgentCheckpoint.RecoveryState recovery = checkpoint.recoveryState();
                hooks.applyRecovery().accept(isolatedWorker, recovery);
                hooks.applyRecovery().accept(isolatedReviewer, recovery);
            }

            activeStepToolRegistry.set(isolatedRegistry);
            activeStepUpdate.set(buffer);
            try {
                isolatedRegistry.runWithToolAccess(
                        ToolRegistry.ToolAccessScope.ISOLATED_PROJECT, () -> {
                            runStepWithLease(step, steps, retryCount,
                                    isolatedWorker, isolatedReviewer, effectiveContext, stepOut,
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

            AgentOrchestrator.ExecutionStep outcome = buffer.updated == null
                    ? step.withFailed("隔离步骤未产生终态")
                    : buffer.updated;
            PatchSet patchSet = session.patchSet();
            if (outcome.status() == AgentOrchestrator.StepStatus.COMPLETED) {
                AgentOrchestrator.ExecutionStep workerOutcome = outcome;
                checkpointCoordinator.commitWorkspace(
                        session, patchSet, step.id(), Path.of(toolRegistry.getProjectPath()),
                        workerOutcome.artifact(), applyResult -> {
                            AgentOrchestrator.ExecutionStep decision;
                            if (!applyResult.applied()) {
                                String reason = applyResult.failureDescription();
                                decision = step.withFailed(reason);
                                stepOut.println("❌ 步骤 [" + step.id() + "] " + reason + "\n");
                                commitStepUpdate(steps, step.id(), decision);
                            } else {
                                decision = workerOutcome.withModifiedFiles(
                                        applyResult.modifiedResources());
                                AgentOrchestrator.ExecutionStep terminalDecision = decision;
                                checkpointCoordinator.persistTerminalClearingDeferredPatch(
                                        step.id(), () -> commitStepUpdate(
                                                steps, step.id(), terminalDecision));
                            }
                        });
            } else if (buffer.verificationInfrastructureFailure && !patchSet.isEmpty()) {
                checkpointCoordinator.preserveDeferredPatch(
                        step.id(), patchSet, outcome.result());
                String reason = outcome.result()
                        + "\n未验证 PatchSet 已保存到 checkpoint；修复环境后可通过 resume 重新验证。";
                commitStepUpdate(steps, step.id(), outcome.withFailed(reason)
                        .withModifiedFiles(List.of()));
                stepOut.println("💾 步骤 [" + step.id()
                        + "] 未验证 PatchSet 已保存，未写入主项目\n");
            } else {
                commitStepUpdate(steps, step.id(), outcome.withModifiedFiles(List.of()));
            }
        } catch (Exception e) {
            toolRegistry.releaseResourceLeases(step.id());
            commitStepUpdate(steps, step.id(),
                    step.withFailed("隔离工作区执行失败: " + e.getMessage()));
            stepOut.println("❌ 步骤 [" + step.id() + "] 隔离工作区执行失败："
                    + e.getMessage() + "\n");
        }
    }

    private boolean requiresIsolatedWorkspace(AgentOrchestrator.ExecutionStep step) {
        return ConfigResolver.booleanValue(
                "devcli.workspace.isolation.enabled",
                "DEVCLI_WORKSPACE_ISOLATION_ENABLED", true)
                && reviewCoordinator.requiresConcreteVerification(step);
    }

    private void runStepWithLease(
            AgentOrchestrator.ExecutionStep step,
            List<AgentOrchestrator.ExecutionStep> steps,
            Map<String, Integer> retryCount,
            SubAgent worker,
            SubAgent localReviewer,
            String context,
            PrintStream stepOut,
            SubAgent.ForkContext workerForkContext,
            SubAgent.ForkContext reviewerForkContext) {
        stepOut.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: "
                + step.description());
        if (CancellationContext.isCancelled()) {
            failCancelled(steps, step, stepOut);
            return;
        }
        AgentMessage taskMessage = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = executeWorkerWithTransientRetry(
                step, worker, taskMessage, context, stepOut, workerForkContext, "");
        if (CancellationContext.isCancelled()) {
            failCancelled(steps, step, stepOut);
            return;
        }
        if (result.type() == AgentMessage.Type.ERROR) {
            updateStep(steps, step.id(), step.withFailed(result.content()));
            stepOut.println("❌ 步骤 [" + step.id() + "] 执行失败：" + result.content() + "\n");
            return;
        }

        SubAgent.ExecutionEvidence acceptedEvidence = worker.getLastExecutionEvidence();
        String acceptedResult = resolveWorkerResultContent(result.content(), acceptedEvidence);
        if (acceptedResult.isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            stepOut.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }
        if (!requiresIsolatedWorkspace(step) && workerForkContext != null) {
            long currentEpoch = toolRegistry.contextVersionLedger().currentGeneration();
            if (workerForkContext.contextEpoch() != currentEpoch) {
                String reason = "STALE_CONTEXT：步骤基于 context_epoch="
                        + workerForkContext.contextEpoch() + "，当前为 " + currentEpoch;
                updateStep(steps, step.id(), step.withFailed(reason));
                stepOut.println("❌ 步骤 [" + step.id() + "] " + reason + "\n");
                return;
            }
        }

        ReviewCoordinator.ReviewDecision acceptedReview = reviewCoordinator.review(
                step, localReviewer, acceptedResult, stepOut, reviewerForkContext);
        boolean approved = acceptedReview.approved();
        if (approved) {
            acceptStep(steps, step, acceptedResult, acceptedEvidence, acceptedReview, stepOut,
                    "审查通过");
            return;
        }

        if (failOnVerificationInfrastructure(
                steps, step, acceptedReview, stepOut)) {
            return;
        }
        if (acceptReviewerAdvisory(
                steps, step, acceptedResult, acceptedEvidence, acceptedReview, stepOut)) {
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = acceptedReview.issues();
        if (acceptedReview.reviewerError()) {
            if (acceptRecoverableReviewerFailure(
                    steps, step, acceptedResult, acceptedEvidence, acceptedReview, stepOut)) {
                return;
            }
            updateStep(steps, step.id(), step.withFailed(issues));
            return;
        }
        log.info("Step {} rejected (retry {}/{}): {}",
                step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retryCount.put(step.id(), ++retries);
            stepOut.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            stepOut.println("   反馈: " + issues + "\n");
            AgentMessage retryResult = executeWorkerWithTransientRetry(
                    step, worker, taskMessage, buildRetryContext(context, issues), stepOut,
                    workerForkContext, "重试 ");
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                continue;
            }
            acceptedEvidence = worker.getLastExecutionEvidence();
            acceptedResult = resolveWorkerResultContent(retryResult.content(), acceptedEvidence);
            if (acceptedResult.isBlank()) {
                issues = "执行结果为空";
                continue;
            }
            ReviewCoordinator.ReviewDecision retryReview = reviewCoordinator.review(
                    step, localReviewer, acceptedResult, stepOut, reviewerForkContext);
            acceptedReview = retryReview;
            issues = retryReview.issues();
            if (failOnVerificationInfrastructure(steps, step, retryReview, stepOut)) {
                return;
            }
            if (acceptReviewerAdvisory(
                    steps, step, acceptedResult, acceptedEvidence, retryReview, stepOut)) {
                return;
            }
            if (retryReview.reviewerError()) {
                if (reviewCoordinator.shouldAcceptAfterRecoverableFailure(
                        step, issues, retryReview.hardCheckExecuted())) {
                    acceptedResult += "\n\nReviewer 可恢复故障；Pre-Review 硬检查已通过，按降级策略接受。\n"
                            + issues;
                    approved = true;
                    acceptedReview = new ReviewCoordinator.ReviewDecision(
                            true, issues, true, retryReview.hardCheckExecuted());
                }
                break;
            }
            approved = retryReview.approved();
        }

        if (approved) {
            acceptStep(steps, step, acceptedResult, acceptedEvidence, acceptedReview, stepOut,
                    "重试后审查通过");
        } else {
            updateStep(steps, step.id(), step.withFailed(issues));
            stepOut.println("❌ 步骤 [" + step.id() + "] 审查未通过，阻止下游步骤继续执行\n");
        }
    }

    private void failCancelled(List<AgentOrchestrator.ExecutionStep> steps,
                               AgentOrchestrator.ExecutionStep step,
                               PrintStream stepOut) {
        updateStep(steps, step.id(), step.withFailed("用户取消"));
        stepOut.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
    }

    private boolean failOnVerificationInfrastructure(
            List<AgentOrchestrator.ExecutionStep> steps,
            AgentOrchestrator.ExecutionStep step,
            ReviewCoordinator.ReviewDecision review,
            PrintStream stepOut) {
        if (review.hardCheckFailureKind() != PreReviewVerifier.FailureKind.INFRASTRUCTURE) {
            return false;
        }
        StepUpdateBuffer buffer = activeStepUpdate.get();
        if (buffer != null && buffer.stepId.equals(step.id())) {
            buffer.verificationInfrastructureFailure = true;
        }
        updateStep(steps, step.id(), step.withFailed(review.issues()));
        stepOut.println("⏸️ 步骤 [" + step.id()
                + "] 硬验证环境不可用，停止重复执行 Worker\n");
        return true;
    }

    private void acceptStep(List<AgentOrchestrator.ExecutionStep> steps,
                            AgentOrchestrator.ExecutionStep step,
                            String result,
                            SubAgent.ExecutionEvidence evidence,
                            ReviewCoordinator.ReviewDecision review,
                            PrintStream stepOut,
                            String message) {
        updateStep(steps, step.id(), step.withResult(result,
                ReviewCoordinator.buildTrustedStepSummary(step, evidence, review)));
        stepOut.println("✅ 步骤 [" + step.id() + "] " + message + "\n");
    }

    private boolean acceptRecoverableReviewerFailure(
            List<AgentOrchestrator.ExecutionStep> steps,
            AgentOrchestrator.ExecutionStep step,
            String result,
            SubAgent.ExecutionEvidence evidence,
            ReviewCoordinator.ReviewDecision review,
            PrintStream stepOut) {
        if (!reviewCoordinator.shouldAcceptAfterRecoverableFailure(
                step, review.issues(), review.hardCheckExecuted())) {
            return false;
        }
        String degradedResult = result
                + "\n\nReviewer 可恢复故障；Pre-Review 硬检查已通过，按降级策略接受。\n"
                + review.issues();
        ReviewCoordinator.ReviewDecision degradedReview = new ReviewCoordinator.ReviewDecision(
                true, review.issues(), true, review.hardCheckExecuted());
        acceptStep(steps, step, degradedResult, evidence, degradedReview, stepOut,
                "Pre-Review 硬检查已通过，Reviewer 可恢复故障降级接受");
        return true;
    }

    private boolean acceptReviewerAdvisory(
            List<AgentOrchestrator.ExecutionStep> steps,
            AgentOrchestrator.ExecutionStep step,
            String result,
            SubAgent.ExecutionEvidence evidence,
            ReviewCoordinator.ReviewDecision review,
            PrintStream stepOut) {
        if (!ReviewCoordinator.isReviewerAdvisory(review)) {
            return false;
        }
        String advisoryResult = result + "\n\nReviewer 建议（不阻断）：\n" + review.issues();
        acceptStep(steps, step, advisoryResult, evidence, review, stepOut,
                "确定性检查通过，Reviewer 建议已记录（不阻断）");
        return true;
    }

    private AgentMessage executeWorkerWithTransientRetry(
            AgentOrchestrator.ExecutionStep step,
            SubAgent worker,
            AgentMessage taskMessage,
            String context,
            PrintStream stepOut,
            SubAgent.ForkContext workerForkContext,
            String label) {
        AgentMessage result = executeWorkerOnce(
                step, worker, taskMessage, context, stepOut, workerForkContext,
                LlmClient.ToolChoice.AUTO);
        int transientRetries = 0;
        int protocolRepairs = 0;
        while (true) {
            if (result.type() == AgentMessage.Type.ERROR
                    && isTransientLlmError(result.content())
                    && transientRetries < MAX_RETRIES_PER_STEP) {
                transientRetries++;
                stepOut.println("⚠️ 步骤 [" + step.id() + "] " + label
                        + "LLM 瞬时错误，正在重新调用 Worker (" + transientRetries
                        + "/" + MAX_RETRIES_PER_STEP + ")...");
                result = executeWorkerOnce(step, worker, taskMessage, context, stepOut,
                        workerForkContext, LlmClient.ToolChoice.AUTO);
                continue;
            }
            if (TeamWorkerProtocol.needsMandatoryToolRepair(
                    result, worker.getLastExecutionEvidence(), requireWorkerToolEvidence)
                    && protocolRepairs < TeamWorkerProtocol.MAX_MANDATORY_TOOL_REPAIRS) {
                protocolRepairs++;
                stepOut.println("⚠️ 步骤 [" + step.id() + "] " + label
                        + "Worker 未产生成功工具证据，正在强制执行修复 ("
                        + protocolRepairs + "/" + TeamWorkerProtocol.MAX_MANDATORY_TOOL_REPAIRS
                        + ")...");
                worker.clearHistory();
                LlmClient.ToolChoice requiredToolChoice =
                        TeamWorkerProtocol.requiredToolChoice(step.type());
                AgentMessage repairTask = AgentMessage.task("orchestrator",
                        TeamWorkerProtocol.buildMandatoryToolTask(
                                step.description(), protocolRepairs,
                                requiredToolChoice.toolName()));
                result = executeWorkerOnce(step, worker, repairTask, context, stepOut,
                        workerForkContext, requiredToolChoice);
                continue;
            }
            return result;
        }
    }

    private AgentMessage executeWorkerOnce(
            AgentOrchestrator.ExecutionStep step,
            SubAgent worker,
            AgentMessage taskMessage,
            String context,
            PrintStream stepOut,
            SubAgent.ForkContext workerForkContext,
            LlmClient.ToolChoice toolChoice) {
        try {
            ToolRegistry registry = activeToolRegistry();
            String completionToolName = TeamWorkerProtocol.completionToolName(
                    step.type(), toolChoice);
            long contextEpoch = workerForkContext == null
                    ? registry.contextVersionLedger().currentGeneration()
                    : workerForkContext.contextEpoch();
            AgentMessage result = memoryManager.runWithEvidenceOrigin(
                    worker.getName(), step.id(), contextEpoch,
                    () -> registry.runWithResourceLease(step.id(), () -> workerForkContext == null
                            ? worker.executeWithContext(taskMessage, context, stepOut, toolChoice,
                                    completionToolName)
                            : worker.executeForkedWithContext(taskMessage, context,
                                    workerForkContext, stepOut, toolChoice, completionToolName)));
            checkpointCoordinator.recordAgentMessage(
                    worker.getName(), step.id(), "Worker 执行完成", result);
            return result;
        } finally {
            activeToolRegistry().releaseResourceLeases(step.id());
        }
    }

    private synchronized void updateStep(List<AgentOrchestrator.ExecutionStep> steps,
                                         String stepId,
                                         AgentOrchestrator.ExecutionStep updated) {
        StepUpdateBuffer buffer = activeStepUpdate.get();
        AgentOrchestrator.ExecutionStep effective = attachModifiedFiles(stepId, updated);
        if (buffer != null && buffer.stepId.equals(stepId)) {
            buffer.updated = effective;
            return;
        }
        commitStepUpdate(steps, stepId, effective);
    }

    private synchronized void commitStepUpdate(List<AgentOrchestrator.ExecutionStep> steps,
                                               String stepId,
                                               AgentOrchestrator.ExecutionStep updated) {
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).id().equals(stepId)) {
                continue;
            }
            steps.set(index, updated);
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

    private AgentOrchestrator.ExecutionStep attachModifiedFiles(
            String stepId,
            AgentOrchestrator.ExecutionStep updated) {
        if (updated.status() != AgentOrchestrator.StepStatus.COMPLETED
                && updated.status() != AgentOrchestrator.StepStatus.FAILED) {
            return updated;
        }
        List<String> consumed = activeToolRegistry().consumeStepModifiedFiles(stepId);
        return updated.withModifiedFiles(
                consumed.isEmpty() ? updated.modifiedFiles() : consumed);
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
                .append("成功工具：").append(successful.size()).append('/')
                .append(evidence.toolResults().size());
        appendToolEvidence(summary, successful, 4, 600, "其余成功工具：");
        appendToolEvidence(summary, unsuccessful, 2, 300, "其余未成功工具：");
        return summary.toString();
    }

    private static void appendToolEvidence(StringBuilder summary,
                                           List<SubAgent.ToolEvidence> evidence,
                                           int previewLimit,
                                           int textLimit,
                                           String remainderLabel) {
        int previewCount = Math.min(previewLimit, evidence.size());
        for (int index = 0; index < previewCount; index++) {
            SubAgent.ToolEvidence tool = evidence.get(index);
            summary.append("\n- ");
            if (tool.status() != com.devcli.tool.ToolStatus.SUCCESS) {
                summary.append("未成功 ");
            }
            summary.append(tool.name());
            if (tool.status() != com.devcli.tool.ToolStatus.SUCCESS) {
                summary.append(" [").append(tool.status()).append(']');
            }
            summary.append(": ").append(tool.result().isBlank()
                    ? (tool.status() == com.devcli.tool.ToolStatus.SUCCESS
                            ? "执行成功，无文本结果"
                            : "无文本结果")
                    : previewToolEvidence(tool.result(), textLimit));
        }
        if (evidence.size() > previewCount) {
            summary.append("\n- ").append(remainderLabel)
                    .append(evidence.size() - previewCount).append(" 个");
        }
    }

    private static String previewToolEvidence(String result, int maxLength) {
        String normalized = result.replace("\r\n", "\n").replace('\r', '\n').trim();
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength) + "...";
    }

    private static boolean isTransientLlmError(String content) {
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

    private String buildRetryContext(String context, String issues) {
        return new StringBuilder(context == null ? "" : context)
                .append("\n\n上一次执行被拒绝。只做根因修复，不要重写无关代码。\n")
                .append("必须保留原始任务指定的 class / method / signature、已通过行为和已有生产文件结构。\n")
                .append("如果反馈来自 Pre-Review 编译失败，先读取报错文件和行号，再最小补丁修复。\n")
                .append("拒绝原因摘要：\n").append(summarizeRetryIssues(issues)).append('\n')
                .toString();
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
            boolean important = kept < 8 || trimmed.contains("error:")
                    || trimmed.contains("错误") || trimmed.contains("failed")
                    || trimmed.contains("missing") || trimmed.contains("expected=")
                    || trimmed.contains("actual=") || trimmed.contains("Reviewer 未调用工具");
            if (important) {
                summary.append("- ").append(trimmed).append('\n');
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

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "\n...<truncated>";
    }
}
