package com.devcli.agent;

import com.devcli.tool.ToolContract;
import com.devcli.context.ContextProfile;
import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
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
import com.devcli.tool.DelegateRoleCatalog;
import com.devcli.util.DelegationReportSanitizer;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final int maxChildIterations;
    private final boolean reportSanitizationEnabled;
    private final DelegationState state;
    private final Map<String, List<LlmClient.Tool>> toolSnapshots = new ConcurrentHashMap<>();
    /** 单会话派出量配额；计数器由 Agent 持有，跨用户轮次累计，不随单轮预算重置。 */
    private final AtomicInteger spawnCounter;
    private final int maxSpawnsPerSession;
    private final DelegationCheckpoint checkpoint;

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
        this(parent, modelResolver, parentBudget, systemPrompt, events, spawnCounter, state, null);
    }

    DelegationSession(ToolRegistry parent, Function<String, LlmClient> modelResolver,
                      AgentBudget parentBudget, String systemPrompt, RunEventSink events,
                      AtomicInteger spawnCounter, DelegationState state, DelegationCheckpoint checkpoint) {
        this.parent = parent;
        this.modelResolver = modelResolver;
        this.parentBudget = parentBudget;
        this.systemPrompt = systemPrompt;
        this.events = events;
        this.state = state;
        this.checkpoint = checkpoint;
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
        DelegateRoleCatalog.Role definition;
        try {
            definition = parent.delegateRoles().require(role);
        } catch (IllegalArgumentException e) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS, e.getMessage(), false), "failed");
        }
        if (arguments.getOrDefault("task", "").isBlank()) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "需要有效角色和非空子任务", false), "failed");
        }
        if (definition.writes() && Boolean.parseBoolean(arguments.getOrDefault("run_in_background", "false"))) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "写角色仅支持同步执行；请等待子任务完成后继续写入", false), "blocked");
        }
        Set<String> requestedTools = parseStringSet(arguments.get("allowed_tools"));
        if (requestedTools.stream().anyMatch(tool -> !definition.allows(tool))) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                    "请求工具超出角色定义的 allowed_tools", false), "blocked");
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
        if (parent.currentToolAccessScope() != ToolContract.ToolAccessScope.FULL) {
            return reportFailure(id, ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                    "受限子任务不能继续委派"), "blocked");
        }
        AgentBudget budget = parentBudget.fork();
        if (budget.check() != AgentBudget.ExitReason.WITHIN_BUDGET) {
            return reportFailure(id, ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    budget.describeExit(budget.check()), false), "blocked");
        }
        ToolContract.ToolAccessScope childScope = definition.capability();
        for (String tool : parseStringSet(arguments.get("allowed_tools"))) {
            if (!parent.permitsDelegatedTool(tool, childScope)) {
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
        if (!definition.writes()) {
            return executeReserved(id, role, arguments, context, budget, upstreamSnapshot, resumed);
        }
        var writer = parent.tryEnterSynchronousWorker();
        if (writer == null) {
            return ToolOutput.rejected(ToolErrorCode.RESOURCE_CONFLICT,
                    "已有写入任务正在执行；同步 worker 不能重叠运行，请等待后重试", true);
        }
        try (writer) {
            return executeReserved(id, role, arguments, context, budget, upstreamSnapshot, resumed);
        }
    }

    private ToolOutput executeReserved(String id, String role, Map<String, String> arguments,
                                       ToolExecutionContext context, AgentBudget budget,
                                       String upstreamSnapshot, DelegationState.Snapshot resumed) {
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
            DelegateRoleCatalog.Role definition = parent.delegateRoles().require(role);
            String rolePrompt = definition.prompt();
            LlmClient client = models.computeIfAbsent(role, this::resolveModel);
            if (client == null) throw new IllegalArgumentException("子 Agent 模型不可用: " + role);
            if (definition.writes()) {
                try (WorkspaceExecutionSession workspace = WorkspaceExecutionSession.open(
                        parent, id, parseStringList(arguments.get("allowed_write_paths")))) {
                    try {
                        if (checkpoint != null) checkpoint.restore(workspace, id);
                        if (resumed != null && !resumed.changes().isEmpty()) restorePatch(workspace, resumed, arguments);
                        candidateReport = JSON.createObjectNode().put("report_id", id).put("child_id", id)
                                .put("status", "failed").put("patch_status", "NOT_APPLIED");
                        result = executeChild(workspace.toolRegistry(), client, budget, id, role, rolePrompt,
                                arguments, context, ToolContract.ToolAccessScope.ISOLATED_PROJECT, upstreamSnapshot, resumed);
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
                            WorkspaceExecutionSession.CommitPreparation validation = effective -> {
                                        context.throwIfCancelled();
                                        if ("PASSED".equals(report.path("hard_check").asText())
                                                && !samePatch(patch, effective)) {
                                            throw new IOException("提交候选已经变化，必须重新执行代码硬检查");
                                        }
                                        report.put("patch_status", "COMMIT_UNKNOWN");
                                    };
                            PatchSet.ApplyResult applied = checkpoint == null
                                    ? workspace.commit(patch, validation, ignored -> { })
                                    : checkpoint.commit(workspace, patch, report, validation);
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
                            if (checkpoint != null) checkpoint.retainFailure(workspace, candidateReport);
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
                            arguments, context, ToolContract.ToolAccessScope.READ_ONLY, upstreamSnapshot, resumed);
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
        workspace.restorePatch(resumed.patch(), arguments.get("resume_report_id"));
    }

    private void verifyCodePatch(WorkspaceExecutionSession workspace, PatchSet patch, ObjectNode report,
                                 String id, ToolExecutionContext context) throws IOException {
        if (patch.changes().stream().noneMatch(c -> c.relativePath().endsWith(".java")
                || c.relativePath().equals("pom.xml"))) {
            report.put("hard_check", "NOT_REQUIRED");
            return;
        }
        context.throwIfCancelled();
        PreReviewVerifier verifier = checkpoint != null ? checkpoint.verifier() : new PreReviewVerifier(60, request -> {
            ToolExecutionContext checkContext = new ToolExecutionContext(context.invocationId() + "/verify",
                    context.cancellationToken(), System.nanoTime(), Math.min(context.deadlineNanos(),
                    System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(request.timeoutSeconds())));
            ToolOutput output = ToolRegistry.runWithResultEvents(event -> events.emit(new RunEvent.AgentEvent(id, event)),
                    () -> workspace.toolRegistry().runWithToolAccess(ToolContract.ToolAccessScope.ISOLATED_PROJECT,
                            () -> workspace.toolRegistry().executeCommandOutput(request.command(), checkContext)));
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

    private LlmClient resolveModel(String role) {
        var definition = parent.delegateRoles().require(role);
        LlmClient primary = modelResolver.apply(role);
        if (definition.provider() == null && definition.model() == null) return primary;
        return com.devcli.llm.LlmClientFactory.createDelegatedAgent(primary, definition.provider(), definition.model());
    }

    private ToolOutput runIndependentReview(String workerReport,
                                            Map<String, String> arguments,
                                            ToolExecutionContext context,
                                            ToolRegistry reviewBase) {
        try {
            LlmClient reviewer = models.computeIfAbsent("reviewer", this::resolveModel);
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
                        parent.delegateRoles().require("reviewer").prompt(), reviewArguments,
                        context, ToolContract.ToolAccessScope.READ_ONLY, "");
            }
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "独立 Reviewer 执行失败: " + e.getMessage(), false);
        }
    }

    private ToolOutput executeChild(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                    String id, String role, String rolePrompt, Map<String, String> arguments,
                                    ToolExecutionContext context, ToolContract.ToolAccessScope scope,
                                    String upstreamReport) {
        return executeChild(registry, client, budget, id, role, rolePrompt, arguments, context, scope, upstreamReport, null);
    }

    private ToolOutput executeChild(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                    String id, String role, String rolePrompt, Map<String, String> arguments,
                                    ToolExecutionContext context, ToolContract.ToolAccessScope scope,
                                    String upstreamReport, DelegationState.Snapshot resumed) {
        var definition = parent.delegateRoles().require(role);
        var narrowed = new java.util.HashMap<>(arguments);
        if (parseStringSet(arguments.get("allowed_tools")).isEmpty() && !definition.allowedTools().contains("*")) {
            narrowed.put("allowed_tools", JSON.valueToTree(definition.allowedTools()).toString());
        }
        Map<String, String> childArguments = Map.copyOf(narrowed);
        return registry.runWithAllowedTools(parseStringSet(childArguments.get("allowed_tools")),
                () -> registry.runWithAllowedWritePaths(parseStringList(childArguments.get("allowed_write_paths")),
                        () -> executeChildInternal(registry, client, budget, id, role, rolePrompt,
                                childArguments, context, scope, upstreamReport, resumed)));
    }

    private ToolOutput executeChildInternal(ToolRegistry registry, LlmClient client, AgentBudget budget,
                                             String id, String role, String rolePrompt, Map<String, String> arguments,
                                             ToolExecutionContext context, ToolContract.ToolAccessScope scope,
                                             String upstreamReport, DelegationState.Snapshot resumed) {
        var skillBuffer = parent.activeSkillContextBuffer();
        registry.restrictForDelegation();
        if (skillBuffer != null) registry.setSkillContextBuffer(skillBuffer.copy());
        registry.setContextProfile(ContextProfile.from(client));
        List<LlmClient.Tool> tools = registry.runWithToolAccess(scope,
                () -> toolSnapshots.computeIfAbsent(role + "|" + String.join(",", parseStringSet(arguments.get("allowed_tools"))), ignored -> {
                    return List.copyOf(registry.getToolDefinitions());
                }));
        try (RunContext run = CancellationContext.startRunContext(Path.of(registry.getProjectPath()));
             CancellationToken.Registration registration = context.cancellationToken().onCancel(
                     cancelled -> run.cancellationToken().cancel(cancelled.reason(), cancelled.message()))) {
            return registry.runWithToolAccess(scope, () -> registry.runWithResourceLease(id, () -> {
                try {
                    return new DelegationChildLoop(registry, client, budget, id, rolePrompt, arguments, context, tools,
                            childMaxIterations(arguments), upstreamReport, resumed, systemPrompt, events,
                            state, checkpoint, this::serializeReport).run();
                } catch (IOException error) {
                    return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                            "无法恢复委派报告: " + error.getMessage(), false);
                } finally {
                    registry.releaseResourceLeases(id);
                }
            }));
        }
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

    static String bounded(String text) {
        if (text == null) return "";
        return text.length() <= MAX_REPORT_CHARS ? text : text.substring(0, MAX_REPORT_CHARS) + "\n[结果已截断]";
    }



}
