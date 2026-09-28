package com.devcli.agent;

import com.devcli.context.ContextProfile;
import com.devcli.config.ConfigResolver;
import com.devcli.hook.HookLifecycle;
import com.devcli.llm.LlmClient;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.CompactionContext;
import com.devcli.memory.TokenBudget;
import com.devcli.prompt.PromptRepository;
import com.devcli.concurrent.CancellationContext;
import com.devcli.concurrent.CancellationToken;
import com.devcli.concurrent.RunContext;
import com.devcli.event.RunEvent;
import com.devcli.event.RunEventSink;
import com.devcli.tool.DelegateTaskTool;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.util.DelegationReportSanitizer;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** 每个主回合的委派装配，复用跨回合状态、子循环和隔离归并链路。 */
final class DelegationSession implements DelegateTaskTool.Handler {
    private static final int MAX_REPORT_CHARS = 12000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolRegistry parent;
    private final Function<String, LlmClient> modelResolver;
    private final Map<String, LlmClient> models = new ConcurrentHashMap<>();
    private final AgentBudget parentBudget;
    private final String systemPrompt;
    private final RunEventSink events;
    private final PromptRepository prompts;
    private final int maxChildIterations;
    private final boolean reportSanitizationEnabled;
    private final DelegationState state;
    private final Map<String, List<LlmClient.Tool>> toolSnapshots = new ConcurrentHashMap<>();
    /** 单会话派出量配额；计数器由 Agent 持有，跨用户轮次累计，不随单轮预算重置。 */
    private final AtomicInteger spawnCounter;
    private final int maxSpawnsPerSession;

    DelegationSession(ToolRegistry parent, Function<String, LlmClient> modelResolver,
                      AgentBudget parentBudget, String systemPrompt, RunEventSink events) {
        this(parent, modelResolver, parentBudget, systemPrompt, events, null);
    }

    DelegationSession(ToolRegistry parent, Function<String, LlmClient> modelResolver,
                      AgentBudget parentBudget, String systemPrompt, RunEventSink events,
                      AtomicInteger spawnCounter) {
        this(parent, modelResolver, parentBudget, systemPrompt, events, spawnCounter,
                new DelegationState(Path.of(parent.getProjectPath())));
    }

    DelegationSession(ToolRegistry parent, Function<String, LlmClient> modelResolver,
                      AgentBudget parentBudget, String systemPrompt, RunEventSink events,
                      AtomicInteger spawnCounter, DelegationState state) {
        this.parent = parent;
        this.modelResolver = modelResolver;
        this.parentBudget = parentBudget;
        this.systemPrompt = systemPrompt;
        this.events = events;
        this.state = state;
        this.prompts = new PromptRepository(Path.of(System.getProperty("user.home"), ".devcli", "prompts"),
                Path.of(parent.getProjectPath()).toAbsolutePath().resolve(".devcli/prompts"));
        this.maxChildIterations = ConfigResolver.intValue("devcli.delegate.max.iterations",
                "DEVCLI_DELEGATE_MAX_ITERATIONS", 32, 1, 100);
        this.reportSanitizationEnabled = ConfigResolver.booleanValue(
                "devcli.delegation.report.sanitization.enabled",
                "DEVCLI_DELEGATION_REPORT_SANITIZATION_ENABLED", true);
        this.spawnCounter = spawnCounter == null ? new AtomicInteger() : spawnCounter;
        this.maxSpawnsPerSession = ConfigResolver.intValue("devcli.delegate.max.per.session",
                "DEVCLI_DELEGATE_MAX_PER_SESSION", 200, 1, 10000);
    }

    @Override
    public ToolOutput execute(Map<String, String> arguments, ToolExecutionContext context) {
        arguments = Map.copyOf(arguments);
        String resumeId = arguments.getOrDefault("resume_report_id", "").trim();
        String id = resumeId.isEmpty() ? "delegate-" + UUID.randomUUID().toString().substring(0, 12) : resumeId;
        DelegationState.Snapshot resumed;
        try {
            resumed = resumeId.isEmpty() ? null : state.find(resumeId);
            if (!resumeId.isEmpty() && (resumed == null || resumed.history().isEmpty())) {
                return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS, "子任务不存在或没有可恢复的上下文");
            }
            if (resumed != null && !resumed.report().isBlank()
                    && !JSON.readTree(resumed.report()).path("resume_available").asBoolean(true)) {
                return ToolOutput.rejected(ToolErrorCode.RESOURCE_CONFLICT, "该子任务恢复状态不完整，请先人工核对产物");
            }
            if (resumed != null && !resumeScopeAllowed(resumed.arguments(), arguments)) {
                return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED, "续接不能更换角色或扩大工具与写入范围");
            }
        } catch (IOException | IllegalArgumentException e) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, "无法读取子任务恢复记录: " + e.getMessage(), false);
        }
        String role = arguments.getOrDefault("role", "").toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("explorer", "planner", "worker", "reviewer").contains(role)
                || arguments.getOrDefault("task", "").isBlank()) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "需要有效角色和非空子任务", false), "failed");
        }
        DelegationPolicy.Decision policy = DelegationPolicy.evaluate(arguments);
        DelegationPolicy.Yield yield = policy.yield();
        // 收益诊断只进事件流，不回灌模型：把分数告诉模型只会诱导它修改声明措辞来「过门槛」，
        // 而任务实质不变。放行结论与诊断分开记录，便于事后统计委派决策质量。详见 ADR 0009。
        events.emit(new RunEvent.CustomMessage(
                "delegation.policy", policy.summary(),
                Map.of("child_id", id,
                        "role", role,
                        "allowed", String.valueOf(policy.allowed()),
                        "yield_score", String.valueOf(yield.score()),
                        "yield_benefit", String.valueOf(yield.benefit()),
                        "yield_coordination_cost", String.valueOf(yield.coordinationCost()),
                        "yield_low", String.valueOf(yield.lowYield()),
                        "yield_advice", yield.advice())));
        if (!policy.allowed()) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "委派请求被拒绝：" + policy.reason(), false), "blocked");
        }
        if (parent.currentToolAccessScope() != ToolRegistry.ToolAccessScope.FULL) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                    "受限子任务不能继续委派"), "blocked");
        }
        AgentBudget budget = parentBudget.fork();
        if (budget.check() != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    budget.describeExit(budget.check()), false), "blocked");
        }
        ToolRegistry.ToolAccessScope childScope = "worker".equals(role)
                ? ToolRegistry.ToolAccessScope.ISOLATED_PROJECT : ToolRegistry.ToolAccessScope.READ_ONLY;
        for (String tool : parseStringSet(arguments.get("allowed_tools"))) {
            if (!parent.hasTool(tool) || !childScope.permits(parent.toolEffect(tool))
                    || List.of("delegate_task", "delegate_control", "list_memory").contains(tool)) {
                return reportFailure(id, ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                        "子任务请求的工具不可用或不符合角色权限: " + tool, false), "blocked");
            }
        }
        String upstream = arguments.getOrDefault("upstream_report_id", "").trim();
        String upstreamSnapshot = upstream.isEmpty() ? "" : acquireReport(upstream);
        if (!upstream.isEmpty() && upstreamSnapshot == null) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "上游报告不存在或已过期，请补充输入后再委派", false), "blocked");
        }
        if (!state.reserve(id)) {
            return ToolOutput.rejected(ToolErrorCode.RESOURCE_CONFLICT, "子任务正在执行或后台委派容量已满");
        }
        // 单会话派出量配额。放在全部前置检查之后，只统计真正开始执行的委派。
        // 真实总量由父子共享的 Token 与轮数预算控制，该配额的作用是让「委派是有限资源」
        // 对模型成为确定信号，而不是承担实际限流。详见 ADR 0010。
        int spawnIndex = spawnCounter.incrementAndGet();
        if (spawnIndex > maxSpawnsPerSession) {
            spawnCounter.decrementAndGet();
            state.release(id);
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "本次会话委派额度已用尽（上限 " + maxSpawnsPerSession
                            + " 次），请由主 Agent 用现有工具直接完成剩余工作。", false), "blocked");
        }
        events.emit(new RunEvent.CustomMessage("delegation.started", "子任务开始",
                Map.of("child_id", id, "report_id", id, "role", role,
                        "spawn_index", String.valueOf(spawnIndex),
                        "spawn_limit", String.valueOf(maxSpawnsPerSession))));
        if (Boolean.parseBoolean(arguments.getOrDefault("run_in_background", "false"))) {
            ToolRegistry base = null;
            try {
                base = parent.forkForProject(Path.of(parent.getProjectPath()));
                if (base instanceof com.devcli.hitl.HitlToolRegistry approvals) approvals.freezeTrustedIntentContext();
                DelegationSession background = new DelegationSession(base, modelResolver, parentBudget,
                        systemPrompt, events, spawnCounter, state);
                ToolRegistry frozenBase = base;
                Map<String, String> frozenArguments = arguments;
                ObjectNode running = JSON.createObjectNode().put("child_id", id).put("report_id", id)
                        .put("status", "running").put("role", role).put("summary", "子任务已在后台启动")
                        .put("patch_status", "NOT_APPLIED").put("knowledge_outcome", "NONE");
                appendRequestMetadata(running, arguments);
                state.saveReport(id, serializeReport(running));
                CancellationToken backgroundToken = state.token(id);
                state.launch(id, context.cancellationToken(), token -> {
                    RunContext previous = CancellationContext.bind(null);
                    try (frozenBase; RunContext run = CancellationContext.startRunContext(Path.of(frozenBase.getProjectPath()));
                         CancellationToken.Registration link = token.onCancel(c ->
                                 run.cancellationToken().cancel(c.reason(), c.message()))) {
                        ToolExecutionContext childContext = new ToolExecutionContext(id, token,
                                context.startedAtNanos(), context.deadlineNanos());
                        return background.executeAccepted(id, role, frozenArguments, childContext,
                                budget, upstreamSnapshot, resumed);
                    } catch (Exception e) {
                        return background.reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                                "后台子任务失败: " + e.getMessage(), false), "failed");
                    } finally {
                        CancellationContext.restore(previous);
                    }
                });
                java.util.concurrent.CompletableFuture.delayedExecutor(context.remainingNanos(),
                        java.util.concurrent.TimeUnit.NANOSECONDS).execute(() -> backgroundToken.cancel(
                                CancellationToken.Reason.TIMEOUT, "后台子任务超过执行期限"));
                // 启动结果使用独立快照，快速完成的子任务不会被 running 状态覆盖。
                return ToolOutput.success(serializeReport(running));
            } catch (Exception e) {
                if (base != null) base.close();
                state.release(id);
                return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                        "后台委派启动失败: " + e.getMessage(), false), "failed");
            }
        }
        CancellationToken token = state.token(id);
        try (CancellationToken.Registration link = context.cancellationToken().onCancel(c ->
                token.cancel(c.reason(), c.message()))) {
            return executeAccepted(id, role, arguments, new ToolExecutionContext(context.invocationId(), token,
                    context.startedAtNanos(), context.deadlineNanos()), budget, upstreamSnapshot, resumed);
        } finally {
            state.release(id);
        }
    }

    private ToolOutput executeAccepted(String id, String role, Map<String, String> arguments,
                                       ToolExecutionContext context, AgentBudget budget,
                                       String upstreamSnapshot, DelegationState.Snapshot resumed) {
        ToolOutput result;
        ObjectNode candidateReport = null;
        try {
            context.throwIfCancelled();
            String rolePrompt = prompts.loadRequired("modes/delegate-" + role + ".md");
            LlmClient client = models.computeIfAbsent(role, modelResolver);
            if (client == null) throw new IllegalArgumentException("子 Agent 模型不可用: " + role);
            if (role.equals("worker")) {
                try (WorkspaceExecutionSession workspace = WorkspaceExecutionSession.open(
                        parent, id, parseStringList(arguments.get("allowed_write_paths")))) {
                    try {
                        if (resumed != null && !resumed.changes().isEmpty()) restorePatch(workspace, resumed, arguments);
                        candidateReport = JSON.createObjectNode().put("report_id", id).put("child_id", id)
                                .put("status", "failed").put("patch_status", "NOT_APPLIED");
                        result = executeChild(workspace.toolRegistry(), client, budget, id, role, rolePrompt,
                                arguments, context, ToolRegistry.ToolAccessScope.ISOLATED_PROJECT, upstreamSnapshot, resumed);
                        candidateReport = (ObjectNode) JSON.readTree(result.text());
                        if (result.isSuccess()) {
                            context.throwIfCancelled();
                            PatchSet patch = workspace.patchSet();
                            ObjectNode report = candidateReport;
                            List<String> patchResources = patch.changes().stream()
                                    .map(PatchSet.FileChange::relativePath).distinct().toList();
                            appendPatchEvidence(report, patch);
                            verifyCodePatch(workspace, patch, report, id, context);
                            boolean reviewRequired = DelegationReviewGate.requiresIndependentReview(
                                    new DelegationReviewGate.Signals(
                                            patchResources, childEverHadMutationFailure(result)));
                            report.put("independent_review_required", reviewRequired);
                            if (reviewRequired) {
                                ToolOutput review = runIndependentReview(
                                        serializeReport(report), arguments, context, workspace.toolRegistry());
                                DelegationReviewProtocol.Decision decision =
                                        DelegationReviewProtocol.evaluate(review.text());
                                if (!review.isSuccess() || !decision.protocolValid() || !decision.approved()) {
                                    report.put("independent_review", "REJECTED");
                                    throw new IOException("独立 Reviewer 未通过，未应用工作区修改: "
                                            + decision.summary() + " "
                                            + String.join("; ", decision.blockingIssues()));
                                }
                                report.put("independent_review", "APPROVED");
                                if (decision.advisories() > 0) {
                                    var advisories = report.putArray("advisories");
                                    decision.advisoryIssues().forEach(advisories::add);
                                }
                            } else {
                                report.put("independent_review", "NOT_REQUIRED");
                            }
                            PatchSet.ApplyResult applied = workspace.commit(patch,
                                    effective -> {
                                        context.throwIfCancelled();
                                        if ("PASSED".equals(report.path("hard_check").asText())
                                                && !samePatch(patch, effective)) {
                                            throw new IOException("提交候选已经变化，必须重新执行代码硬检查");
                                        }
                                        report.put("patch_status", "COMMIT_UNKNOWN");
                                    }, ignored -> { });
                            if (applied.applied()) {
                                var files = report.putArray("modified_resources");
                                applied.modifiedResources().forEach(files::add);
                                report.put("report_id", id).put("status", "done");
                                report.put("patch_status", applied.modifiedResources().isEmpty()
                                        ? "NO_CHANGES" : "APPLIED");
                                String normalized = serializeReport(report);
                                storeReport(id, normalized);
                                result = ToolOutput.success(normalized)
                                        .withModifiedResources(applied.modifiedResources());
                            } else {
                                report.put("patch_status", applied.rollbackComplete()
                                        ? "NOT_APPLIED" : "ROLLBACK_INCOMPLETE");
                                var rollbackFailures = report.putArray("rollback_failures");
                                applied.rollbackFailures().forEach(rollbackFailures::add);
                                result = ToolOutput.error(ToolErrorCode.RESOURCE_CONFLICT,
                                        applied.failureDescription(), false);
                            }
                        }
                    } finally {
                        if (candidateReport != null) {
                            String patchStatus = candidateReport.path("patch_status").asText();
                            if (!List.of("COMMIT_UNKNOWN", "ROLLBACK_INCOMPLETE").contains(patchStatus)) {
                                try {
                                    PatchSet retained = "APPLIED".equals(patchStatus)
                                            ? new PatchSet(List.of()) : workspace.patchSet();
                                    state.savePatch(id, arguments, retained);
                                    candidateReport.put("resume_available", !state.find(id).history().isEmpty())
                                            .put("pending_patch_files", retained.changes().size());
                                } catch (IOException e) {
                                    candidateReport.put("resume_available", false)
                                            .put("recovery_error", bounded(e.getMessage()));
                                }
                            } else {
                                candidateReport.put("resume_available", false);
                            }
                        }
                    }
                }
            } else {
                try (ToolRegistry child = parent.forkForProject(Path.of(parent.getProjectPath()))) {
                    result = executeChild(child, client, budget, id, role, rolePrompt,
                            arguments, context, ToolRegistry.ToolAccessScope.READ_ONLY, upstreamSnapshot, resumed);
                    candidateReport = (ObjectNode) JSON.readTree(result.text());
                    if (result.isSuccess()) {
                        result = registerChildReport(id, result);
                    }
                }
            }
        } catch (CancellationException e) {
            result = context.cancellation().map(c -> c.reason() == CancellationToken.Reason.TIMEOUT).orElse(false)
                    ? new ToolOutput(com.devcli.tool.ToolStatus.TIMEOUT, ToolErrorCode.TIMEOUT, false,
                            "子任务超过执行期限，未应用未提交的修改", List.of(), List.of())
                    : ToolOutput.cancelled("子任务已取消，未应用未提交的修改");
        } catch (Exception e) {
            result = ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "子任务失败: " + e.getMessage(), false);
        } finally {
            parent.forgetStaleWriteScope(id);
        }
        if (!result.isSuccess() && candidateReport != null
                && !result.text().contains("\"report_id\"")) {
            candidateReport.put("status", result.status() == com.devcli.tool.ToolStatus.CANCELLED
                    ? "cancelled" : result.status() == com.devcli.tool.ToolStatus.TIMEOUT ? "timed_out" : "failed");
            candidateReport.put("error", bounded(result.text()))
                    .put("error_code", result.errorCode().name())
                    .put("retryable", result.retryable());
            candidateReport.withArray("open_questions").add(bounded(result.text()));
            List<String> committedResources = new ArrayList<>();
            candidateReport.path("modified_resources").forEach(path -> committedResources.add(path.asText()));
            result = new ToolOutput(result.status(), result.errorCode(), result.retryable(),
                    serializeReport(candidateReport), List.of(), committedResources, List.of());
        }
        if (candidateReport != null) {
            result = new ToolOutput(result.status(), result.errorCode(), result.retryable(),
                    serializeReport(candidateReport), result.imageParts(), result.modifiedResources(), result.sideChannels());
        }
        result = ensureReport(id, result, arguments);
        events.emit(new RunEvent.CustomMessage("delegation.completed", "子任务结束",
                Map.of("child_id", id, "report_id", id, "role", role,
                        "status", result.status().name())));
        return result;
    }

    @Override public ToolOutput control(Map<String, String> arguments, ToolExecutionContext context) {
        String id = arguments.getOrDefault("report_id", "");
        try {
            context.throwIfCancelled();
            if ("cancel".equals(arguments.get("action"))) {
                boolean requested = state.cancel(id);
                String report = state.report(id);
                if (report == null) return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, "委派报告不存在或已过期", false);
                ObjectNode response = (ObjectNode) JSON.readTree(report);
                response.put("cancel_requested", requested);
                return ToolOutput.success(serializeReport(response));
            }
            if ("wait".equals(arguments.get("action"))) {
                state.await(id, Math.max(0, Math.min(30,
                        Integer.parseInt(arguments.getOrDefault("wait_seconds", "30")))), context.cancellationToken());
            }
            context.throwIfCancelled();
            String report = state.report(id);
            if (report != null && !state.isActive(id)) {
                ObjectNode terminal = (ObjectNode) JSON.readTree(report);
                if ("running".equals(terminal.path("status").asText())) {
                    terminal.put("status", "interrupted").put("error_code", ToolErrorCode.EXECUTION_FAILED.name())
                            .put("summary", "子任务已经停止，未取得终态报告，请核对后续接或重新委派");
                    report = serializeReport(terminal);
                }
            }
            return report == null ? ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, "委派报告不存在或已过期", false)
                    : ToolOutput.success(report);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutput.cancelled("等待子任务时被中断");
        } catch (CancellationException e) {
            return ToolOutput.cancelled("委派控制已取消");
        } catch (IOException | IllegalArgumentException e) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, "委派控制参数或报告无效", false);
        }
    }

    private boolean resumeScopeAllowed(Map<String, String> previous, Map<String, String> current) {
        if (!previous.getOrDefault("role", "").equals(current.getOrDefault("role", ""))) return false;
        Set<String> originalTools = parseStringSet(previous.get("allowed_tools"));
        Set<String> tools = parseStringSet(current.get("allowed_tools"));
        if (!originalTools.isEmpty() && (tools.isEmpty() || !originalTools.containsAll(tools))) return false;
        return parseStringSet(previous.get("allowed_write_paths"))
                .containsAll(parseStringSet(current.get("allowed_write_paths")));
    }

    private void restorePatch(WorkspaceExecutionSession workspace, DelegationState.Snapshot resumed,
                              Map<String, String> arguments) throws IOException {
        boolean allowed = workspace.toolRegistry().runWithAllowedWritePaths(
                parseStringList(arguments.get("allowed_write_paths")), () -> resumed.changes().stream()
                        .allMatch(c -> workspace.toolRegistry().isWritePathAllowed(c.relativePath())));
        if (!allowed) throw new IOException("待恢复补丁超出本次写入范围");
        Map<String, String> baselines = new java.util.LinkedHashMap<>();
        for (PatchSet.FileChange change : resumed.changes()) {
            Path target = workspace.toolRegistry().resolveSafePath(change.relativePath());
            baselines.put(change.relativePath(), java.nio.file.Files.isRegularFile(target)
                    ? java.nio.file.Files.readString(target) : null);
        }
        PatchSet.ApplyResult restored = resumed.patch().apply(workspace.workspacePath());
        if (!restored.applied()) throw new IOException("恢复补丁与当前文件基线冲突: " + restored.failureDescription());
        for (PatchSet.FileChange change : resumed.changes()) {
            Path target = workspace.toolRegistry().resolveSafePath(change.relativePath());
            workspace.toolRegistry().contextVersionLedger().recordLocalWrite(
                    arguments.get("resume_report_id"), change.relativePath(), target, baselines.get(change.relativePath()),
                    change.type() == PatchSet.ChangeType.DELETE ? null
                            : new String(change.content(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void verifyCodePatch(WorkspaceExecutionSession workspace, PatchSet patch, ObjectNode report,
                                 String id, ToolExecutionContext context) throws IOException {
        if (patch.changes().stream().noneMatch(c -> c.relativePath().endsWith(".java")
                || c.relativePath().equals("pom.xml"))) {
            report.put("hard_check", "NOT_REQUIRED");
            return;
        }
        context.throwIfCancelled();
        PreReviewVerifier verifier = new PreReviewVerifier(60, request -> {
            ToolExecutionContext checkContext = new ToolExecutionContext(context.invocationId() + "/verify",
                    context.cancellationToken(), System.nanoTime(), Math.min(context.deadlineNanos(),
                    System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(request.timeoutSeconds())));
            ToolOutput output = workspace.toolRegistry().runWithToolAccess(ToolRegistry.ToolAccessScope.ISOLATED_PROJECT,
                    () -> workspace.toolRegistry().executeCommandOutput(request.command(), checkContext));
            if (output.status() == com.devcli.tool.ToolStatus.REJECTED) throw new IllegalStateException(output.text());
            return new com.devcli.tool.command.CommandExecutionService.Result(output.isSuccess() ? 0 : -1,
                    output.text(), output.status() == com.devcli.tool.ToolStatus.TIMEOUT,
                    output.status() == com.devcli.tool.ToolStatus.CANCELLED);
        });
        PreReviewVerifier.Result check = verifier.verify(workspace.workspacePath(), id);
        report.put("hard_check", check.hardCheckExecuted() ? (check.passed() ? "PASSED" : "FAILED") : "NOT_SUPPORTED")
                .put("hard_check_failure_kind", check.failureKind().name())
                .put("hard_check_feedback", bounded(check.feedback()));
        context.throwIfCancelled();
        if (!check.hardCheckExecuted()) throw new IOException("当前 Java 项目布局没有可执行的硬检查入口，未归并");
        if (!check.passed()) throw new IOException("代码任务硬检查未通过，未归并: " + check.feedback());
        if (!samePatch(patch, workspace.patchSet())) throw new IOException("硬检查期间候选文件发生变化，未归并");
    }

    private boolean samePatch(PatchSet expected, PatchSet actual) {
        if (expected.changes().size() != actual.changes().size()) return false;
        for (int i = 0; i < expected.changes().size(); i++) {
            PatchSet.FileChange left = expected.changes().get(i);
            PatchSet.FileChange right = actual.changes().get(i);
            if (!left.relativePath().equals(right.relativePath()) || left.type() != right.type()
                    || !left.beforeHash().equals(right.beforeHash()) || !left.afterHash().equals(right.afterHash())
                    || !java.util.Objects.equals(left.beforeMode(), right.beforeMode())
                    || !java.util.Objects.equals(left.afterMode(), right.afterMode())) return false;
        }
        return true;
    }

    private ToolOutput ensureReport(String id, ToolOutput result, Map<String, String> arguments) {
        if (result == null) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "子任务返回空结果", false), "failed");
        }
        try {
            JsonNode root = JSON.readTree(result.text());
            if (root != null && root.isObject() && root.has("report_id") && root.has("status")) {
                ObjectNode report = (ObjectNode) root;
                appendRequestMetadata(report, arguments);
                if (!result.isSuccess()) {
                    report.put("error_code", result.errorCode().name()).put("retryable", result.retryable());
                }
                String normalized = serializeReport(report);
                storeReport(id, normalized);
                return new ToolOutput(result.status(), result.errorCode(), result.retryable(), normalized,
                        result.imageParts(), result.modifiedResources(), result.sideChannels());
            }
        } catch (IOException ignored) {
        }
        return result.isSuccess()
                ? registerChildReport(id, result)
                : reportFailure(id, result, result.status() == com.devcli.tool.ToolStatus.CANCELLED
                        ? "cancelled" : result.status() == com.devcli.tool.ToolStatus.TIMEOUT ? "timed_out" : "failed");
    }

    private void appendRequestMetadata(ObjectNode report, Map<String, String> arguments) {
        if (arguments == null || arguments.isEmpty()) return;
        ObjectNode request = report.with("request");
        copyRequestValue(request, "task", arguments.get("task"));
        copyRequestValue(request, "task_spec", arguments.get("task_spec"));
        copyRequestValue(request, "context", arguments.get("context"));
        copyRequestValue(request, "deliverable", arguments.get("deliverable"));
        copyRequestValue(request, "constraints", arguments.get("constraints"));
        copyRequestValue(request, "entry_points", arguments.get("entry_points"));
        copyRequestValue(request, "allowed_tools", arguments.get("allowed_tools"));
        copyRequestValue(request, "allowed_write_paths", arguments.get("allowed_write_paths"));
        copyRequestValue(request, "budget", arguments.get("budget"));
        copyRequestValue(request, "upstream_report_id", arguments.get("upstream_report_id"));
        copyRequestValue(request, "resume_report_id", arguments.get("resume_report_id"));
        copyRequestValue(request, "run_in_background", arguments.get("run_in_background"));
    }

    private void copyRequestValue(ObjectNode request, String name, String raw) {
        if (raw == null || raw.isBlank()) return;
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (parsed != null && (parsed.isArray() || parsed.isObject())) {
                request.set(name, parsed);
                return;
            }
        } catch (IOException ignored) {
        }
        request.put(name, raw);
    }

    private ToolOutput reportFailure(String id, ToolOutput output, String status) {
        ObjectNode report = JSON.createObjectNode();
        report.put("report_id", id).put("status", status == null ? "failed" : status)
                .put("summary", bounded(output == null ? "" : output.text()))
                .put("transcript_ref", "delegation:" + id);
        report.put("knowledge_outcome", "NONE").put("patch_status", "NOT_APPLIED");
        if (output != null) {
            report.put("error_code", output.errorCode().name()).put("retryable", output.retryable());
            output.modifiedResources().forEach(report.withArray("modified_resources")::add);
        }
        report.putArray("evidence");
        report.putArray("dead_ends");
        report.putArray("open_questions");
        report.putArray("facts_discovered");
        String normalized = serializeReport(report);
        storeReport(id, normalized);
        if (output == null) return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, normalized, false);
        return new ToolOutput(output.status(), output.errorCode(), output.retryable(), normalized,
                output.imageParts(), output.modifiedResources(), output.sideChannels());
    }

    private boolean childEverHadMutationFailure(ToolOutput result) {
        try {
            return JSON.readTree(result.text()).path("ever_had_mutation_failure").asBoolean(false);
        } catch (IOException ignored) {
            return false;
        }
    }

    private void storeReport(String id, String report) {
        state.saveReport(id, report);
    }

    private String acquireReport(String id) {
        return state.report(id);
    }

    private String serializeReport(ObjectNode report) {
        return DelegationReportSanitizer.frame(report, reportSanitizationEnabled).toString();
    }

    private ToolOutput registerChildReport(String id, ToolOutput result) {
        try {
            JsonNode root = JSON.readTree(result.text());
            if (root != null && root.isObject()) {
                ObjectNode report = (ObjectNode) root;
                report.put("report_id", id);
                if (!report.has("status")) report.put("status", "done");
                if (!report.has("summary")) report.put("summary", bounded(result.text()));
                if (!report.has("transcript_ref")) report.put("transcript_ref", "delegation:" + id);
                if (!report.has("modified_resources")) report.putArray("modified_resources");
                if (!report.has("evidence")) report.set("evidence", report.path("tool_evidence").deepCopy());
                if (!report.has("dead_ends")) report.putArray("dead_ends");
                if (!report.has("open_questions")) report.putArray("open_questions");
                if (!report.has("facts_discovered")) report.putArray("facts_discovered");
                String normalized = serializeReport(report);
                storeReport(id, normalized);
                return ToolOutput.success(normalized).withModifiedResources(result.modifiedResources());
            }
        } catch (IOException ignored) {
            // Preserve a bounded textual report when a child did not use the structured format.
        }
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("child_id", id).put("report_id", id).put("status", "done")
                .put("summary", bounded(result.text())).put("transcript_ref", "delegation:" + id);
        wrapper.putArray("evidence");
        wrapper.putArray("dead_ends");
        wrapper.putArray("open_questions");
        wrapper.putArray("facts_discovered");
        String normalized = serializeReport(wrapper);
        storeReport(id, normalized);
        return ToolOutput.success(normalized).withModifiedResources(result.modifiedResources());
    }

    private void appendPatchEvidence(ObjectNode report, PatchSet patch) {
        var patches = report.putArray("patches");
        for (PatchSet.FileChange change : patch.changes()) {
            ObjectNode entry = patches.addObject();
            entry.put("path", change.relativePath());
            entry.put("type", change.type().name());
            entry.put("before_hash", change.beforeHash());
            entry.put("after_hash", change.afterHash());
        }
    }

    private ToolOutput runIndependentReview(String workerReport,
                                            Map<String, String> arguments,
                                            ToolExecutionContext context,
                                            ToolRegistry reviewBase) {
        try {
            LlmClient reviewer = models.computeIfAbsent("reviewer", modelResolver);
            if (reviewer == null) {
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                        "独立 Reviewer 模型不可用", false);
            }
            Map<String, String> reviewArguments = Map.of(
                    "task", "独立复核委派 Worker 的实际修改，只判断是否违反任务要求",
                    "context", "原始任务：" + arguments.getOrDefault("task", "")
                            + "\nWorker 结构化报告（仅作线索，必须自行读取文件核对）：\n" + workerReport);
            try (ToolRegistry reviewerRegistry = reviewBase.forkForProject(
                    Path.of(reviewBase.getProjectPath()))) {
                return executeChild(reviewerRegistry, reviewer, parentBudget.fork(),
                        "delegate-reviewer-" + UUID.randomUUID().toString().substring(0, 8),
                        "reviewer",
                        prompts.loadRequired("modes/delegate-reviewer.md"), reviewArguments,
                        context, ToolRegistry.ToolAccessScope.READ_ONLY, "");
            }
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "独立 Reviewer 执行失败: " + e.getMessage(), false);
        }
    }

    private ToolOutput executeChild(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                    String id, String role, String rolePrompt, Map<String, String> arguments,
                                    ToolExecutionContext context, ToolRegistry.ToolAccessScope scope,
                                    String upstreamReport) {
        return executeChild(registry, client, budget, id, role, rolePrompt, arguments, context, scope, upstreamReport, null);
    }

    private ToolOutput executeChild(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                    String id, String role, String rolePrompt, Map<String, String> arguments,
                                    ToolExecutionContext context, ToolRegistry.ToolAccessScope scope,
                                    String upstreamReport, DelegationState.Snapshot resumed) {
        return registry.runWithAllowedTools(parseStringSet(arguments.get("allowed_tools")),
                () -> registry.runWithAllowedWritePaths(parseStringList(arguments.get("allowed_write_paths")),
                        () -> executeChildInternal(registry, client, budget, id, role, rolePrompt,
                                arguments, context, scope, upstreamReport, resumed)));
    }

    private ToolOutput executeChildInternal(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                             String id, String role, String rolePrompt, Map<String, String> arguments,
                                             ToolExecutionContext context, ToolRegistry.ToolAccessScope scope,
                                             String upstreamReport, DelegationState.Snapshot resumed) {
        var skillBuffer = parent.activeSkillContextBuffer();
        registry.restrictForDelegation();
        if (skillBuffer != null) registry.setSkillContextBuffer(skillBuffer.copy());
        registry.setContextProfile(ContextProfile.from(client));
        List<LlmClient.Tool> tools = registry.runWithToolAccess(scope,
                () -> toolSnapshots.computeIfAbsent(role + "|" + String.join(",", parseStringSet(arguments.get("allowed_tools"))), ignored -> {
                    registry.prefetchToolDefinitionsForInput(arguments.get("task"));
                    return List.copyOf(registry.getToolDefinitions());
                }));
        try (RunContext run = CancellationContext.startRunContext(Path.of(registry.getProjectPath()));
             CancellationToken.Registration registration = context.cancellationToken().onCancel(
                     cancelled -> run.cancellationToken().cancel(cancelled.reason(), cancelled.message()))) {
            return registry.runWithToolAccess(scope, () -> registry.runWithResourceLease(id, () -> {
                try {
                    return new ChildLoop(registry, client, budget, id, rolePrompt, arguments, context, tools,
                            childMaxIterations(arguments), upstreamReport, resumed).run();
                } finally {
                    registry.releaseResourceLeases(id);
                }
            }));
        }
    }

    private final class ChildLoop implements AgentExecutionEngine.Delegate<ToolOutput> {
        private final ToolRegistry registry;
        private final LlmClient client;
        private final AgentBudget budget;
        private final String id;
        private final ToolExecutionContext context;
        private final List<LlmClient.Tool> tools;
        private final List<LlmClient.Message> history = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private final Deque<ObjectNode> observations = new ArrayDeque<>();
        private final Deque<ObjectNode> deadEnds = new ArrayDeque<>();
        private boolean hasSuccessfulEvidence;
        private final int childMaxIterations;
        private final java.util.Set<String> unresolvedMutations = new java.util.HashSet<>();
        private boolean everHadMutationFailure;
        private final ConversationHistoryCompactor compactor;
        private final Map<String, String> arguments;

        ChildLoop(ToolRegistry registry, LlmClient client, AgentBudget budget, String id,
                  String rolePrompt, Map<String, String> arguments, ToolExecutionContext context,
                  List<LlmClient.Tool> tools, int childMaxIterations, String upstreamReport,
                  DelegationState.Snapshot resumed) {
            this.registry = registry;
            this.client = client;
            this.budget = budget;
            this.id = id;
            this.context = context;
            this.tools = tools;
            this.childMaxIterations = childMaxIterations;
            this.arguments = arguments;
            history.add(LlmClient.Message.system(systemPrompt + "\n\n你是受主 Agent 委派的子 Agent。"
                    + rolePrompt + "\n只完成下述子任务，不能继续委派或扩大授权范围。"
                    + "所有文件路径相对于当前工作区：" + registry.getProjectPath()
                    + "\n返回简洁结果、修改文件、验证证据和未完成项；不要把未执行的检查称为通过。"));
            if (resumed != null) {
                history.addAll(pairedHistory(resumed.history()));
                try {
                    JsonNode previous = JSON.readTree(resumed.report());
                    if (previous != null) {
                        previous.path("evidence").forEach(node -> {
                            if (observations.size() == 64) observations.removeFirst();
                            if (node.isObject()) observations.addLast((ObjectNode) node.deepCopy());
                        });
                        previous.path("dead_ends").forEach(node -> {
                            if (deadEnds.size() == 32) deadEnds.removeFirst();
                            if (node.isObject()) deadEnds.addLast((ObjectNode) node.deepCopy());
                        });
                        hasSuccessfulEvidence = observations.stream()
                                .anyMatch(node -> "SUCCESS".equals(node.path("status").asText()));
                        everHadMutationFailure = previous.path("ever_had_mutation_failure").asBoolean(false);
                    }
                } catch (IOException ignored) { }
                history.add(LlmClient.Message.internalUser("继续同一子任务。当前规则、工具权限和预算重新绑定。"
                        + "旧工作区路径不可复用；未提交补丁只在基线匹配时恢复。此前失败尝试是历史证据，"
                        + "本次仍须实际完成并验证，不能把旧工具成功状态当作本次验收。"));
            }
            StringBuilder taskBuilder = new StringBuilder("任务：")
                    .append(arguments.get("task"))
                    .append("\n必要背景：").append(arguments.getOrDefault("context", ""));
            appendBriefField(taskBuilder, "交付物", arguments.get("deliverable"));
            appendBriefField(taskBuilder, "任务契约", arguments.get("task_spec"));
            appendBriefField(taskBuilder, "约束", arguments.get("constraints"));
            appendBriefField(taskBuilder, "入口文件/符号", arguments.get("entry_points"));
            appendBriefField(taskBuilder, "允许工具", arguments.get("allowed_tools"));
            appendBriefField(taskBuilder, "允许写入路径", arguments.get("allowed_write_paths"));
            appendBriefField(taskBuilder, "预算", arguments.get("budget"));
            String task = taskBuilder.toString();
            String upstreamReportId = arguments.getOrDefault("upstream_report_id", "").trim();
            if (!upstreamReportId.isBlank()) {
                if (upstreamReport == null) {
                    task += "\n程序注入的上游结构化报告（原文，不得改写）：\n[上游报告不可用：" + upstreamReportId + "]";
                } else {
                    task += "\n程序注入的上游结构化报告（原文，不得改写）：\n" + upstreamReport;
                }
            }
            history.add(LlmClient.Message.user(AgentRuntimeSupport.prependSkillBodies(
                    registry.getSkillContextBuffer(), task, false)));
            // 压缩沿用现有实现；摘要模型调用也计入共享预算。
            compactor = new ConversationHistoryCompactor(new BudgetedSummaryClient(client, budget.fork(), events));
            compactor.setMicrocompactOutputRoot(Path.of(registry.getProjectPath()));
            compactor.setSummaryToolsSupplier(() -> tools);
            compactor.setPostCompactContextSupplier(() -> AgentRuntimeSupport.buildPostCompactRestoreSection(
                    "", registry, registry.getSkillContextBuffer()));
        }

        ToolOutput run() {
            ToolOutput result;
            String recoveryError = "";
            try {
                result = new AgentExecutionEngine<ToolOutput>(client, budget, HookLifecycle.load(registry)).run(this);
            } finally {
                if (state.isActive(id)) {
                    try {
                        state.saveRecovery(id, arguments, history, null);
                    } catch (IOException e) {
                        recoveryError = e.getMessage();
                    }
                }
            }
            if (state.isActive(id)) {
                try {
                    ObjectNode report = (ObjectNode) JSON.readTree(result.text());
                    report.put("resume_available", recoveryError.isEmpty());
                    if (!recoveryError.isEmpty()) report.put("recovery_error", bounded(recoveryError));
                    result = new ToolOutput(result.status(), result.errorCode(), result.retryable(),
                            serializeReport(report), result.imageParts(), result.modifiedResources(), result.sideChannels());
                } catch (IOException ignored) { }
            }
            return result;
        }
        @Override public List<LlmClient.Message> history() { return history; }
        @Override public List<LlmClient.Tool> toolDefinitions(int iteration) { return tools; }
        @Override public ToolRegistry.ToolSnapshot toolSnapshot(int iteration) {
            return registry.snapshotForCurrentAccess().withDefinitions(tools);
        }
        @Override public LlmClient.StreamListener streamListener() { return LlmClient.StreamListener.NO_OP; }
        @Override public int maxIterations() { return childMaxIterations; }
        @Override public boolean isCancelled() { return context.isCancelled() || CancellationContext.isCancelled(); }
        @Override public RunEventSink eventSink() {
            // 子循环终态不能变成父运行终态，文本和历史也不混入父模型上下文。
            return event -> {
                if (event instanceof RunEvent.ModelUsage) events.emit(event);
                else if (event instanceof RunEvent.ToolResults results) {
                    events.emit(new RunEvent.CustomMessage("delegation.tools", "子任务工具结果",
                            Map.of("child_id", id, "results", results.results().stream()
                                    .map(r -> r.name() + ":" + r.status()).toList().toString())));
                }
            };
        }
        @Override public void beforeIteration(int iteration, AgentBudget currentBudget) {
            compactor.compactIfNeeded(history, AgentRuntimeSupport.buildCompactionContext(
                    ContextProfile.from(client).historyTriggerTokens(
                            TokenBudget.estimateToolDefinitionsTokens(tools)),
                    registry, id));
            if (registry.getSkillContextBuffer() != null) {
                String pending = registry.getSkillContextBuffer().drain();
                if (!pending.isBlank()) history.add(LlmClient.Message.internalUser(pending));
            }
        }
        @Override public List<ToolRegistry.ToolExecutionResult> executeTools(List<LlmClient.ToolCall> calls, int iteration) {
            return executeTools(calls, iteration, null);
        }
        @Override public List<ToolRegistry.ToolExecutionResult> executeTools(
                List<LlmClient.ToolCall> calls, int iteration, ToolRegistry.ToolSnapshot snapshot) {
            return registry.executeTools(calls.stream().map(call -> new ToolRegistry.ToolInvocation(
                    call.id(), call.function().name(), call.function().arguments())).toList(), snapshot);
        }
        @Override public void afterToolResults(LlmClient.ChatResponse response,
                List<ToolRegistry.ToolExecutionResult> results, int iteration, AgentBudget currentBudget) {
            for (var result : results) {
                if (evidence.size() == 64) evidence.remove(0);
                evidence.add(result.name() + ":" + result.status() + ":" + result.errorCode());
                ObjectNode observed = JSON.createObjectNode();
                observed.put("invocation_id", result.id()).put("tool", result.name())
                        .put("status", result.status().name()).put("error_code", result.errorCode().name())
                        .put("arguments", excerpt(result.argumentsJson()))
                        .put("output_excerpt", excerpt(result.result()));
                result.sideChannels().stream()
                        .filter(com.devcli.tool.ToolResultArtifact.class::isInstance)
                        .map(com.devcli.tool.ToolResultArtifact.class::cast)
                        .filter(artifact -> !artifact.artifactRef().isBlank()).findFirst()
                        .ifPresent(artifact -> observed.put("result_ref", artifact.artifactRef()));
                if (observations.size() == 64) observations.removeFirst();
                observations.addLast(observed);
                if (result.status() == com.devcli.tool.ToolStatus.SUCCESS) {
                    hasSuccessfulEvidence = true;
                } else {
                    if (deadEnds.size() == 32) deadEnds.removeFirst();
                    deadEnds.addLast(observed);
                }
                var effect = registry.toolEffect(result.name());
                if (effect != ToolRegistry.ToolEffect.READ_ONLY && effect != ToolRegistry.ToolEffect.LOCAL_CONTEXT) {
                    String key = mutationKey(result);
                    if (result.status() == com.devcli.tool.ToolStatus.SUCCESS) unresolvedMutations.remove(key);
                    else {
                        unresolvedMutations.add(key);
                        everHadMutationFailure = true;
                    }
                }
                if (result.hasImageParts()) history.add(LlmClient.Message.user(result.imageParts(), LlmClient.MessageSource.TOOL));
            }
        }
        @Override public Map<String, String> refreshStaleContext() { return registry.refreshStaleContext(id); }
        @Override public String contextScope() { return id; }
        @Override public ToolOutput completed(LlmClient.ChatResponse response, AgentBudget currentBudget) {
            ObjectNode report = JSON.createObjectNode();
            report.put("child_id", id).put("report_id", id).put("status", "done")
                    .put("model", client.getModelName())
                    .put("summary", bounded(response.content())).put("iterations", currentBudget.iteration());
            appendEvidence(report);
            report.putArray("facts_discovered");
            report.putArray("modified_resources");
            report.put("transcript_ref", "delegation:" + id);
            report.put("verification", "工具状态仅表示执行结果；主 Agent 仍需核对任务验收条件");
            if (registry.currentToolAccessScope() == ToolRegistry.ToolAccessScope.ISOLATED_PROJECT
                    && !unresolvedMutations.isEmpty()) {
                report.put("status", "failed").put("error", "仍有未解决的副作用工具失败，未应用工作区修改");
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, serializeReport(report), false);
            }
            return ToolOutput.success(serializeReport(report));
        }
        @Override public ToolOutput cancelled(AgentBudget currentBudget) {
            if (context.cancellation().map(c -> c.reason() == CancellationToken.Reason.TIMEOUT).orElse(false)) {
                return terminalReport("timed_out", "子任务超过执行期限", ToolErrorCode.TIMEOUT, false);
            }
            return terminalReport("cancelled", "子任务已取消", ToolErrorCode.CANCELLED, false);
        }
        @Override public ToolOutput budgetExceeded(AgentBudget.ExitReason reason, AgentBudget currentBudget) {
            return terminalReport("partial", currentBudget.describeExit(reason), ToolErrorCode.EXECUTION_FAILED, false);
        }
        @Override public ToolOutput iterationLimitReached(AgentBudget currentBudget) {
            return terminalReport("partial", "子任务达到 " + childMaxIterations + " 轮上限",
                    ToolErrorCode.EXECUTION_FAILED, false);
        }
        @Override public ToolOutput failed(IOException error, AgentBudget currentBudget) {
            if (isCancelled()) return cancelled(currentBudget);
            return terminalReport("failed", "子任务模型调用失败: " + error.getMessage(),
                    ToolErrorCode.EXECUTION_FAILED, false);
        }

        private ToolOutput terminalReport(String status, String summary,
                                           ToolErrorCode errorCode, boolean retryable) {
            ObjectNode report = JSON.createObjectNode();
            report.put("child_id", id).put("report_id", id).put("status", status)
                    .put("summary", bounded(summary)).put("transcript_ref", "delegation:" + id);
            report.putArray("modified_resources");
            appendEvidence(report);
            report.withArray("open_questions").add(bounded(summary));
            report.putArray("facts_discovered");
            com.devcli.tool.ToolStatus toolStatus = "cancelled".equals(status)
                    ? com.devcli.tool.ToolStatus.CANCELLED : "timed_out".equals(status)
                    ? com.devcli.tool.ToolStatus.TIMEOUT : com.devcli.tool.ToolStatus.ERROR;
            return new ToolOutput(toolStatus, errorCode, retryable,
                    serializeReport(report), List.of(), List.of(), List.of());
        }

        private void appendEvidence(ObjectNode report) {
            var checks = report.putArray("tool_evidence");
            evidence.forEach(checks::add);
            var observed = report.putArray("evidence");
            observations.forEach(observed::add);
            var rejected = report.putArray("dead_ends");
            deadEnds.forEach(rejected::add);
            var unresolved = report.putArray("unresolved_mutations");
            unresolvedMutations.stream().sorted().map(DelegationSession::excerpt).forEach(unresolved::add);
            var questions = report.putArray("open_questions");
            unresolvedMutations.stream().sorted().map(DelegationSession::excerpt).forEach(questions::add);
            report.put("knowledge_outcome", hasSuccessfulEvidence ? "PARTIAL" : "NONE");
            report.put("patch_status", registry.currentToolAccessScope()
                    == ToolRegistry.ToolAccessScope.ISOLATED_PROJECT ? "NOT_APPLIED" : "NOT_APPLICABLE");
            report.put("ever_had_mutation_failure", everHadMutationFailure);
            report.put("evidence_notice", "工具摘录是观察，不是验收结论；失败尝试也不等于永久不可行");
        }

        private void appendBriefField(StringBuilder task, String label, String value) {
            if (value != null && !value.isBlank()) {
                task.append("\n").append(label).append("：").append(value);
            }
        }
    }

    /** 中断留下的工具调用只补充未知结果，不重放工具或推断成功。 */
    private List<LlmClient.Message> pairedHistory(List<LlmClient.Message> messages) {
        List<LlmClient.Message> paired = new ArrayList<>();
        Set<String> pending = new java.util.LinkedHashSet<>();
        for (LlmClient.Message message : messages) {
            if ("tool".equals(message.role())) {
                if (pending.remove(message.toolCallId())) paired.add(message);
                continue;
            }
            pending.forEach(callId -> paired.add(new LlmClient.Message("tool",
                    "上次执行中断，结果未知；需要重新读取文件核验", null, null, callId)));
            pending.clear();
            paired.add(message);
            if (message.toolCalls() != null) message.toolCalls().forEach(call -> pending.add(call.id()));
        }
        pending.forEach(callId -> paired.add(new LlmClient.Message("tool",
                "上次执行中断，结果未知；需要重新读取文件核验", null, null, callId)));
        return paired;
    }

    private int childMaxIterations(Map<String, String> arguments) {
        String raw = arguments.get("budget");
        if (raw == null || raw.isBlank()) return maxChildIterations;
        try {
            int requested = JSON.readTree(raw).path("max_iterations").asInt(maxChildIterations);
            return Math.max(1, Math.min(maxChildIterations, requested));
        } catch (IOException | RuntimeException ignored) {
            return maxChildIterations;
        }
    }

    private Set<String> parseStringSet(String raw) {
        return new java.util.LinkedHashSet<>(parseStringList(raw));
    }

    private List<String> parseStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode node = JSON.readTree(raw);
            if (!node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText());
            });
            return List.copyOf(values);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static String bounded(String text) {
        if (text == null) return "";
        return text.length() <= MAX_REPORT_CHARS ? text : text.substring(0, MAX_REPORT_CHARS) + "\n[结果已截断]";
    }

    private static String excerpt(String text) {
        String safe = com.devcli.policy.SensitiveDataRedactor.redact(text == null ? "" : text);
        return safe.length() <= 512 ? safe : safe.substring(0, 512) + "\n[excerpt truncated]";
    }

    private static String mutationKey(ToolRegistry.ToolExecutionResult result) {
        try {
            var arguments = JSON.readTree(result.argumentsJson());
            String target = "execute_command".equals(result.name())
                    ? arguments.path("command").asText("") : arguments.path("path").asText("");
            return result.name() + ":" + target;
        } catch (IOException e) {
            return result.name();
        }
    }

    private record BudgetedSummaryClient(LlmClient delegate, AgentBudget budget, RunEventSink events) implements LlmClient {
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            if (CancellationContext.isCancelled() || budget.tryBeginIteration() == 0) {
                throw new IOException("子任务预算耗尽或已取消，停止摘要调用");
            }
            ChatResponse response = delegate.chat(messages, tools, listener);
            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            events.emit(new RunEvent.ModelUsage(response.inputTokens(), response.outputTokens(),
                    response.cachedInputTokens(), com.devcli.context.TokenUsageFormatter.estimatedCostCnyValue(
                            delegate, response.inputTokens(), response.outputTokens(), response.cachedInputTokens())));
            return response;
        }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
        @Override public int maxOutputTokens() { return delegate.maxOutputTokens(); }
    }
}
