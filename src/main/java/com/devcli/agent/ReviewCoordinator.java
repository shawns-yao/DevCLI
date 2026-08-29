package com.devcli.agent;

import com.devcli.memory.MemoryManager;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 执行期评审协调器：统一承载 Pre-Review、Reviewer 调用、工具证据门禁、协议裁决和可信摘要。
 */
final class ReviewCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ReviewCoordinator.class);

    interface Journal {
        void recordMessage(String agentId, String stepId, String phase, AgentMessage message);

        void recordEvent(String agentId, String stepId, String phase, String summary);
    }

    record PreReviewResult(boolean passed, boolean hardCheckExecuted,
                           PreReviewVerifier.FailureKind failureKind, String feedback) {
        static PreReviewResult skipped() {
            return new PreReviewResult(true, false, PreReviewVerifier.FailureKind.NONE, "");
        }
    }

    record ReviewDecision(boolean approved, String issues, boolean reviewerError,
                          boolean hardCheckExecuted,
                          PreReviewVerifier.FailureKind hardCheckFailureKind) {
        ReviewDecision(boolean approved, String issues, boolean reviewerError,
                       boolean hardCheckExecuted) {
            this(approved, issues, reviewerError, hardCheckExecuted,
                    PreReviewVerifier.FailureKind.NONE);
        }
    }

    private final MemoryManager memoryManager;
    private final Supplier<ToolRegistry> activeToolRegistry;
    private final Supplier<String> currentUserTask;
    private final Supplier<List<AcceptanceCriterion>> currentAcceptanceCriteria;
    private final Predicate<AgentOrchestrator.ExecutionStep> finalIntegrationStep;
    private final Journal journal;
    private PreReviewVerifier preReviewVerifier = new PreReviewVerifier();

    ReviewCoordinator(MemoryManager memoryManager,
                      Supplier<ToolRegistry> activeToolRegistry,
                      Supplier<String> currentUserTask,
                      Supplier<List<AcceptanceCriterion>> currentAcceptanceCriteria,
                      Predicate<AgentOrchestrator.ExecutionStep> finalIntegrationStep,
                      Journal journal) {
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.activeToolRegistry = Objects.requireNonNull(activeToolRegistry, "activeToolRegistry");
        this.currentUserTask = Objects.requireNonNull(currentUserTask, "currentUserTask");
        this.currentAcceptanceCriteria = Objects.requireNonNull(
                currentAcceptanceCriteria, "currentAcceptanceCriteria");
        this.finalIntegrationStep = Objects.requireNonNull(finalIntegrationStep, "finalIntegrationStep");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    void setPreReviewVerifier(PreReviewVerifier preReviewVerifier) {
        this.preReviewVerifier = preReviewVerifier == null ? new PreReviewVerifier() : preReviewVerifier;
    }

    boolean parseApproval(String reviewContent) {
        return TeamReviewerProtocol.evaluate(reviewContent, reviewerCriteria(null)).approved();
    }

    String parseIssues(String reviewContent) {
        return TeamReviewerProtocol.evaluate(reviewContent, reviewerCriteria(null)).issues();
    }

    ReviewDecision review(AgentOrchestrator.ExecutionStep step,
                          SubAgent reviewer,
                          String workerResult,
                          PrintStream out,
                          SubAgent.ForkContext reviewerForkContext) {
        PreReviewResult preReview = runPreReviewHook(step);
        if (!preReview.passed()) {
            journal.recordEvent(reviewer.getName(), step.id(),
                    "Pre-Review 未通过", preReview.feedback());
            out.println("⛔ 步骤 [" + step.id() + "] Pre-Review Hook 未通过，跳过 Reviewer LLM");
            out.println("   反馈: " + preReview.feedback() + "\n");
            return new ReviewDecision(false, preReview.feedback(), false,
                    preReview.hardCheckExecuted(), preReview.failureKind());
        }
        if (!preReview.feedback().isBlank()) {
            out.println(preReview.feedback() + "\n");
        }

        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        String reviewTask = buildReviewTask(step);
        List<String> reviewToolCalls = Collections.synchronizedList(new ArrayList<>());
        reviewer.setStructuredToolResultConsumer(result -> {
            memoryManager.addToolResult(result.name(), result.argumentsJson(), result.result(),
                    result.sideChannels());
            if (result.status() == ToolStatus.SUCCESS) {
                reviewToolCalls.add(result.name());
            }
        });
        long contextEpoch = reviewerForkContext == null
                ? activeToolRegistry.get().contextVersionLedger().currentGeneration()
                : reviewerForkContext.contextEpoch();
        AgentMessage reviewResult = memoryManager.runWithEvidenceOrigin(
                reviewer.getName(), step.id(), contextEpoch,
                () -> reviewerForkContext == null
                        ? reviewer.review(reviewTask, workerResult, out)
                        : reviewer.reviewForked(reviewTask, workerResult, reviewerForkContext, out));
        reviewer.clearHistory();
        journal.recordMessage(reviewer.getName(), step.id(), "Reviewer 审查完成", reviewResult);

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
        List<String> effectiveVerificationTools = new ArrayList<>(reviewToolCalls);
        if (preReview.hardCheckExecuted()) {
            effectiveVerificationTools.add("execute_command");
        }
        TeamReviewerProtocol.Evaluation evaluation = TeamReviewerProtocol.evaluate(
                reviewResult.content(), reviewerCriteria(step), effectiveVerificationTools);
        return new ReviewDecision(evaluation.approved(), evaluation.issues(), false,
                preReview.hardCheckExecuted());
    }

    boolean shouldAcceptAfterRecoverableFailure(AgentOrchestrator.ExecutionStep step,
                                                String issues,
                                                boolean hardCheckExecuted) {
        return canDegradeReviewerFailure(isRecoverableReviewerFailure(issues), hardCheckExecuted);
    }

    static boolean canDegradeReviewerFailure(boolean recoverableFailure,
                                             boolean hardCheckExecuted) {
        return recoverableFailure && hardCheckExecuted;
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

    static String buildTrustedStepSummary(AgentOrchestrator.ExecutionStep step,
                                          SubAgent.ExecutionEvidence evidence,
                                          ReviewDecision review) {
        StringBuilder summary = new StringBuilder("summary_source=ORCHESTRATOR")
                .append("; step=").append(step == null ? "" : step.id())
                .append("; review=")
                .append(review != null && review.approved()
                        ? (review.reviewerError() ? "DEGRADED" : "APPROVED")
                        : "REJECTED")
                .append("; hard_check=")
                .append(review != null && review.hardCheckExecuted() ? "PASSED" : "NOT_RUN");
        List<SubAgent.ToolEvidence> successful = evidence == null ? List.of() : evidence.toolResults().stream()
                .filter(item -> item.status() == ToolStatus.SUCCESS)
                .toList();
        if (successful.isEmpty()) {
            summary.append("; tools=none");
        } else {
            summary.append("; tools=");
            for (int i = 0; i < successful.size(); i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                SubAgent.ToolEvidence item = successful.get(i);
                String evidencePreview = item.result().replace('\n', ' ').trim();
                if (evidencePreview.length() > 160) {
                    evidencePreview = evidencePreview.substring(0, 160) + "...";
                }
                summary.append(item.name());
                if (!evidencePreview.isBlank()) {
                    summary.append('(').append(evidencePreview).append(')');
                }
            }
        }
        return summary.toString();
    }

    List<String> missingDeclaredVerifierTools(List<String> reviewToolCalls) {
        Set<String> used = reviewToolCalls == null ? Set.of() : new HashSet<>(reviewToolCalls);
        return criteria().stream()
                .filter(criterion -> criterion.verificationMethod()
                        == AcceptanceCriterion.VerificationMethod.TOOL)
                .map(AcceptanceCriterion::verifier)
                .filter(verifier -> !used.contains(verifier))
                .distinct()
                .toList();
    }

    PreReviewResult runPreReviewHook(AgentOrchestrator.ExecutionStep step) {
        if (!requiresConcreteVerification(step) || !requiresJavaHardCheck(step)) {
            return PreReviewResult.skipped();
        }
        Path projectRoot = Path.of(activeToolRegistry.get().getProjectPath()).toAbsolutePath().normalize();
        PreReviewVerifier.Result result = preReviewVerifier.verify(projectRoot, step.id());
        return new PreReviewResult(result.passed(), result.hardCheckExecuted(),
                result.failureKind(), result.feedback());
    }

    void appendAcceptanceCriteriaSection(StringBuilder target,
                                         String title,
                                         List<AcceptanceCriterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return;
        }
        target.append("⚠️ [关键上下文，不可压缩或省略] ").append(title).append("：\n");
        for (AcceptanceCriterion criterion : criteria) {
            target.append(criterion.formatForPrompt()).append("\n");
        }
        target.append("\n");
    }

    List<AcceptanceCriterion> acceptanceCriteriaForStep(AgentOrchestrator.ExecutionStep step) {
        if (step == null) {
            return List.of();
        }
        if (finalIntegrationStep.test(step)) {
            return List.copyOf(criteria());
        }
        return criteria().stream()
                .filter(criterion -> criterion.appliesTo().contains(step.id()))
                .toList();
    }

    boolean requiresConcreteVerification(AgentOrchestrator.ExecutionStep step) {
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
                || finalIntegrationStep.test(step);
    }

    private List<TeamReviewerProtocol.Criterion> reviewerCriteria(AgentOrchestrator.ExecutionStep step) {
        List<AcceptanceCriterion> selected = step == null ? criteria() : acceptanceCriteriaForStep(step);
        return selected.stream()
                .map(criterion -> new TeamReviewerProtocol.Criterion(
                        criterion.id(), criterion.severity(),
                        criterion.verificationMethod() == null
                                ? ""
                                : criterion.verificationMethod().name(),
                        criterion.verifier()))
                .toList();
    }

    private String buildReviewTask(AgentOrchestrator.ExecutionStep step) {
        StringBuilder task = new StringBuilder();
        String userTask = currentUserTask.get();
        if (userTask != null && !userTask.isBlank()) {
            task.append("原始用户任务：\n").append(userTask).append("\n\n");
        }
        appendAcceptanceCriteriaSection(task,
                "逐条验证以下验收点，每条必须单独检查并输出证据",
                acceptanceCriteriaForStep(step));
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

    boolean requiresJavaHardCheck(AgentOrchestrator.ExecutionStep step) {
        String text = (step.type() + " " + step.description()).toLowerCase(Locale.ROOT);
        return text.contains("java")
                || text.contains(".java")
                || text.contains("cli")
                || text.contains("api")
                || text.contains("代码")
                || text.contains("编译")
                || text.contains("入口")
                || finalIntegrationStep.test(step);
    }

    private boolean isVerificationStepWithPreReview(AgentOrchestrator.ExecutionStep step) {
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

    private List<AcceptanceCriterion> criteria() {
        List<AcceptanceCriterion> criteria = currentAcceptanceCriteria.get();
        return criteria == null ? List.of() : criteria;
    }
}
