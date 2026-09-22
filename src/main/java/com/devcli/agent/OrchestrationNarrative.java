package com.devcli.agent;

import com.devcli.plan.ExecutionArtifact;

import java.util.List;
import java.util.Map;

/** 组装 Worker 上下文和编排终态报告，集中控制可信摘要与恢复信息的暴露边界。 */
final class OrchestrationNarrative {
    private static final int MAX_REVIEW_RETRIES = 2;
    private static final int MAX_REDO_PER_STEP = 1;

    private final OrchestrationRunState runState;
    private final ReviewCoordinator reviewCoordinator;

    OrchestrationNarrative(OrchestrationRunState runState,
                           ReviewCoordinator reviewCoordinator) {
        this.runState = runState;
        this.reviewCoordinator = reviewCoordinator;
    }

    String buildStepContext(List<AgentOrchestrator.ExecutionStep> steps,
                            AgentOrchestrator.ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder("总任务上下文：\n");
        if (!runState.userTask().isBlank()) {
            context.append("原始用户任务：\n").append(runState.userTask()).append("\n\n");
        }
        reviewCoordinator.appendAcceptanceCriteriaSection(
                context, "本步骤必须满足以下验收点",
                reviewCoordinator.acceptanceCriteriaForStep(currentStep));
        context.append("当前步骤：").append(currentStep.description()).append("\n\n");
        appendRedoContext(context, currentStep);
        appendRestoredFailureContext(context, currentStep);
        appendAttemptDigests(context, currentStep);
        if (reviewCoordinator.requiresJavaHardCheck(currentStep)) {
            context.append("Java 代码交付约束：严格保留原始任务指定的入口签名；优先使用简单命令式实现；完成前运行最小编译检查；重试时只做根因补丁。\n\n");
        }
        if (AgentOrchestrator.isFinalIntegrationStep(currentStep)) {
            appendAllStepStates(context, steps, currentStep);
        }
        appendCompletedDependencies(context, steps, currentStep);
        return context.toString();
    }

    private void appendRedoContext(StringBuilder context,
                                   AgentOrchestrator.ExecutionStep step) {
        if (!runState.redoTracker().isRedo(step.id())) {
            return;
        }
        context.append("⚠️ 本步骤上次执行失败，现在原位重做——请换一种思路实现，不要重复已失败的做法。\n");
        String lastFailure = runState.redoTracker().lastFailureReason(step.id());
        if (!lastFailure.isBlank()) {
            context.append("上次失败原因：").append(abbreviate(lastFailure, 300)).append('\n');
        }
        context.append('\n');
    }

    private void appendRestoredFailureContext(StringBuilder context,
                                              AgentOrchestrator.ExecutionStep step) {
        ExecutionArtifact failedArtifact = runState.restoredFailedArtifacts().get(step.id());
        if (failedArtifact == null) {
            return;
        }
        if (!failedArtifact.modifiedResources().isEmpty()) {
            context.append("本步骤上次运行失败并已写入以下文件（副作用不可逆）：\n");
            failedArtifact.modifiedResources().forEach(
                    file -> context.append("- ").append(file).append('\n'));
            context.append("重做前必须先读取这些文件的当前内容，在其真实状态上修改，不要假设它们不存在。\n");
        }
        String failureSummary = failedArtifact.error().isBlank()
                ? failedArtifact.summary()
                : failedArtifact.error();
        if (!failureSummary.isBlank()) {
            context.append("上次失败摘要：")
                    .append(abbreviate(failureSummary, 300)).append('\n');
        }
        context.append('\n');
    }

    private void appendAttemptDigests(StringBuilder context,
                                      AgentOrchestrator.ExecutionStep step) {
        List<AgentCheckpoint.AttemptDigestRecord> attempts =
                runState.restoredAttemptDigests().stream()
                        .filter(attempt -> step.id().equals(attempt.stepId()))
                        .toList();
        if (attempts.isEmpty()) {
            return;
        }
        context.append("checkpoint 恢复的已失败尝试（不得无依据重复同一方案）：\n");
        attempts.forEach(attempt -> context.append("- ")
                .append(abbreviate(attempt.digest(), 500)).append('\n'));
        context.append('\n');
    }

    private void appendAllStepStates(StringBuilder context,
                                     List<AgentOrchestrator.ExecutionStep> steps,
                                     AgentOrchestrator.ExecutionStep currentStep) {
        context.append("所有步骤状态：\n");
        for (AgentOrchestrator.ExecutionStep step : steps) {
            if (step.id().equals(currentStep.id())) {
                continue;
            }
            context.append('[').append(step.id()).append("] ")
                    .append(step.status()).append(" - ").append(step.description()).append('\n');
            String trustedSummary = step.artifact().summary();
            if (!trustedSummary.isBlank()) {
                context.append("可信摘要：")
                        .append(trustedSummary, 0, Math.min(trustedSummary.length(), 800))
                        .append('\n');
            }
            if (!step.modifiedFiles().isEmpty()) {
                context.append("修改文件：")
                        .append(String.join(", ", step.modifiedFiles())).append('\n');
            }
        }
        context.append('\n');
    }

    private void appendCompletedDependencies(StringBuilder context,
                                             List<AgentOrchestrator.ExecutionStep> steps,
                                             AgentOrchestrator.ExecutionStep currentStep) {
        for (AgentOrchestrator.ExecutionStep step : steps) {
            if (step.status() != AgentOrchestrator.StepStatus.COMPLETED
                    || !currentStep.dependencies().contains(step.id())) {
                continue;
            }
            context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                    .append(step.description()).append('\n');
            if (!step.modifiedFiles().isEmpty()) {
                context.append("修改文件：\n");
                step.modifiedFiles().forEach(file -> context.append("- ").append(file).append('\n'));
                context.append("继续前请优先读取这些文件的当前内容，基于真实落盘状态衔接实现。\n");
            }
            String trustedSummary = step.artifact().summary();
            if (!trustedSummary.isBlank()) {
                context.append("可信摘要：")
                        .append(previewDependencyResult(trustedSummary)).append('\n');
            }
            context.append('\n');
        }
    }

    String buildFinalResult(List<AgentOrchestrator.ExecutionStep> steps,
                            Map<String, Integer> retryCount) {
        StringBuilder result = new StringBuilder();
        boolean allCompleted = steps.stream().allMatch(
                step -> step.status() == AgentOrchestrator.StepStatus.COMPLETED);
        boolean hasFailed = steps.stream().anyMatch(
                step -> step.status() == AgentOrchestrator.StepStatus.FAILED);
        result.append(allCompleted
                ? "✅ Plan 任务完成！\n\n"
                : hasFailed
                        ? "⚠️ Plan 任务未完全完成，存在失败步骤。\n\n"
                        : "⚠️ Plan 任务部分完成，仍有未执行步骤。\n\n");
        result.append("📋 执行总结：\n");
        for (AgentOrchestrator.ExecutionStep step : steps) {
            result.append('[').append(step.id()).append("] ")
                    .append(statusIcon(step.status())).append(' ')
                    .append(step.description()).append('\n');
            if (step.result() != null && !step.result().isBlank()) {
                result.append("   结果：")
                        .append(step.result().length() > 120
                                ? step.result().substring(0, 120) + "..."
                                : step.result())
                        .append('\n');
            }
        }
        String pendingHuman = pendingHumanAcceptanceSummary();
        if (!pendingHuman.isBlank()) {
            result.append('\n').append(pendingHuman).append('\n');
        }
        String escalation = formatFailureEscalation(
                steps, retryCount, runState.redoTracker().snapshotCounts(),
                runState.checkpoint() == null
                        ? ""
                        : runState.checkpoint().getOrchestrationId());
        if (!escalation.isBlank()) {
            result.append('\n').append(escalation).append('\n');
        }
        return result.toString();
    }

    private static String statusIcon(AgentOrchestrator.StepStatus status) {
        return switch (status) {
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            default -> "⏳";
        };
    }

    static String formatFailureEscalation(
            List<AgentOrchestrator.ExecutionStep> steps,
            Map<String, Integer> reviewerRetries,
            Map<String, Integer> redoCounts,
            String orchestrationId) {
        List<AgentOrchestrator.ExecutionStep> failedSteps = steps == null
                ? List.of()
                : steps.stream()
                        .filter(step -> step.status() == AgentOrchestrator.StepStatus.FAILED)
                        .toList();
        if (failedSteps.isEmpty()) {
            return "";
        }
        Map<String, Integer> retries = reviewerRetries == null ? Map.of() : reviewerRetries;
        Map<String, Integer> redos = redoCounts == null ? Map.of() : redoCounts;
        StringBuilder summary = new StringBuilder("🚨 自动恢复链路已结束，需要人工介入：\n");
        for (AgentOrchestrator.ExecutionStep step : failedSteps) {
            summary.append("- ").append(step.id()).append("：Reviewer 重试 ")
                    .append(retries.getOrDefault(step.id(), 0)).append('/')
                    .append(MAX_REVIEW_RETRIES).append("，原位重做 ")
                    .append(redos.getOrDefault(step.id(), 0)).append('/')
                    .append(MAX_REDO_PER_STEP);
            if (step.result() != null && !step.result().isBlank()) {
                summary.append("；最后失败原因：")
                        .append(step.result().length() > 200
                                ? step.result().substring(0, 200) + "..."
                                : step.result());
            }
            summary.append('\n');
        }
        if (orchestrationId != null && !orchestrationId.isBlank()) {
            summary.append("- checkpoint：").append(orchestrationId).append('\n');
        }
        String failureReason = failedSteps.stream()
                .map(AgentOrchestrator.ExecutionStep::result)
                .filter(reason -> reason != null && !reason.isBlank())
                .findFirst().orElse("自动恢复额度已用尽");
        FailureFeedback feedback = FailureFeedback.fromReason(failureReason);
        feedback = orchestrationId != null && !orchestrationId.isBlank()
                ? feedback.withRetryInstruction(
                        "运行 `/plan resume " + orchestrationId + "` 从 checkpoint 继续")
                : feedback.withRetryInstruction("补充或修正验收约束后重新发起 `/plan`");
        return summary.append(feedback.render()).toString();
    }

    String pendingHumanAcceptanceSummary() {
        List<AcceptanceCriterion> pending = runState.acceptanceCriteria().stream()
                .filter(criterion -> criterion.verificationMethod()
                        == AcceptanceCriterion.VerificationMethod.HUMAN)
                .toList();
        if (pending.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder("⚠️ 待人工验收：\n");
        pending.forEach(criterion -> summary.append("- ").append(criterion.id())
                .append(": ").append(criterion.description())
                .append("；检查方式：").append(criterion.verifier())
                .append("；通过证据：").append(criterion.testSignal()).append('\n'));
        return summary.toString().trim();
    }

    static String previewDependencyResult(String result) {
        if (result == null || result.length() <= 2_000) {
            return result == null ? "" : result;
        }
        String criteria = extractAcceptanceCriteria(result);
        String ordinary = removeFirst(result, criteria);
        if (ordinary.length() <= 2_000) {
            return result;
        }
        String preview = ordinary.substring(0, 1_500)
                + "\n...<中间内容已截断>...\n"
                + ordinary.substring(ordinary.length() - 400);
        return criteria.isEmpty()
                ? preview
                : preview + "\n\n验收标准（完整保留）：\n" + criteria;
    }

    private static String removeFirst(String text, String target) {
        if (text == null || text.isEmpty() || target == null || target.isEmpty()) {
            return text == null ? "" : text;
        }
        int start = text.indexOf(target);
        return start < 0 ? text : text.substring(0, start) + text.substring(start + target.length());
    }

    private static String extractAcceptanceCriteria(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = indexOfAcceptanceCriteriaKey(text);
        if (start >= 0) {
            int arrayStart = text.indexOf('[', start);
            int arrayEnd = arrayStart < 0 ? -1 : findBalancedJsonArrayEnd(text, arrayStart);
            if (arrayEnd > arrayStart) {
                return text.substring(start, arrayEnd).trim();
            }
        }
        start = text.indexOf("验收标准");
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("\n\n", start);
        return text.substring(start, end > start ? end : text.length()).trim();
    }

    private static int indexOfAcceptanceCriteriaKey(String text) {
        int quoted = text.indexOf("\"acceptance_criteria\"");
        return quoted >= 0 ? quoted : text.indexOf("acceptance_criteria");
    }

    private static int findBalancedJsonArrayEnd(String text, int arrayStart) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = arrayStart; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
            } else if (character == '"') {
                inString = true;
            } else if (character == '[') {
                depth++;
            } else if (character == ']' && --depth == 0) {
                return index + 1;
            }
        }
        return -1;
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "\n...<truncated>";
    }
}
