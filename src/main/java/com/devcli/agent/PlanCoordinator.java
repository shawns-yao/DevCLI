package com.devcli.agent;

import com.devcli.plan.ExecutionGraph;
import com.devcli.runtime.CancellationContext;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Planner 调用、计划修复、协议解析、语义评审与 DAG 预处理。 */
final class PlanCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PlanCoordinator.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_PLANNER_STEPS = 5;
    private static final double FINAL_INTEGRATION_FAILURE_RATIO_LIMIT = 0.5;

    record GenerationResult(AgentMessage message,
                            List<AgentOrchestrator.ExecutionStep> steps,
                            TeamPlanReviewProtocol.Evaluation semanticReview) {
        GenerationResult {
            steps = steps == null ? List.of() : List.copyOf(steps);
            semanticReview = semanticReview == null
                    ? TeamPlanReviewProtocol.Evaluation.skipped()
                    : semanticReview;
        }
    }

    private final SubAgent planner;
    private final SubAgent reviewer;
    private final ToolRegistry toolRegistry;
    private final OrchestrationRunState runState;
    private final PrintStream out;
    private boolean semanticReviewEnabled;

    PlanCoordinator(SubAgent planner,
                    SubAgent reviewer,
                    ToolRegistry toolRegistry,
                    OrchestrationRunState runState,
                    PrintStream out,
                    boolean semanticReviewEnabled) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.runState = Objects.requireNonNull(runState, "runState");
        this.out = Objects.requireNonNull(out, "out");
        this.semanticReviewEnabled = semanticReviewEnabled;
    }

    void setSemanticReviewEnabled(boolean enabled) {
        semanticReviewEnabled = enabled;
    }

    GenerationResult requestValidatedPlan(String userInput) {
        AgentMessage result = executePlanner(AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + Objects.toString(userInput, "")));
        if (result.type() == AgentMessage.Type.ERROR) {
            return failed(result, TeamPlanReviewProtocol.Evaluation.skipped());
        }
        int maxRepairAttempts = TeamPlannerProtocol.resolveRepairAttempts();
        for (int repairAttempt = 0; ; repairAttempt++) {
            List<AgentOrchestrator.ExecutionStep> steps = parsePlan(result.content());
            String planIssue = validateGeneratedPlan(steps, userInput);
            TeamPlanReviewProtocol.Evaluation semanticReview =
                    TeamPlanReviewProtocol.Evaluation.skipped();
            if (planIssue == null) {
                semanticReview = reviewGeneratedPlan(userInput, steps);
                if (!semanticReview.protocolValid()) {
                    return failed(AgentMessage.error("plan-reviewer", AgentRole.REVIEWER,
                            "计划语义评审失败：" + semanticReview.issues()), semanticReview);
                }
                if (semanticReview.approved()) {
                    return new GenerationResult(result, steps, semanticReview);
                }
                planIssue = "计划语义评审未通过：" + semanticReview.issues();
            }
            if (repairAttempt >= maxRepairAttempts) {
                log.warn("Planner output remained invalid after repair attempts: {}", planIssue);
                return failed(result, semanticReview);
            }
            if (CancellationContext.isCancelled()) {
                return failed(result, TeamPlanReviewProtocol.Evaluation.skipped());
            }
            int attempt = repairAttempt + 1;
            out.println("⚠️ 规划候选未通过校验或语义评审，正在请求修复 (" + attempt
                    + "/" + maxRepairAttempts + ")...\n");
            result = executePlanner(AgentMessage.task("orchestrator",
                    TeamPlannerProtocol.buildRepairPrompt(
                            userInput, result.content(), planIssue, attempt)));
            if (result.type() == AgentMessage.Type.ERROR) {
                return failed(result, semanticReview);
            }
        }
    }

    private GenerationResult failed(AgentMessage message,
                                    TeamPlanReviewProtocol.Evaluation review) {
        return new GenerationResult(message, List.of(), review);
    }

    TeamPlanReviewProtocol.Evaluation reviewGeneratedPlan(
            String userInput,
            List<AgentOrchestrator.ExecutionStep> steps) {
        if (!semanticReviewEnabled) {
            return TeamPlanReviewProtocol.Evaluation.skipped();
        }
        StringBuilder prompt = new StringBuilder("计划语义评审\n原始用户目标：\n")
                .append(Objects.toString(userInput, ""))
                .append("\n\n候选执行计划：\n")
                .append(summarizeSteps(steps))
                .append("\n\n候选验收标准：\n");
        for (AcceptanceCriterion criterion : runState.acceptanceCriteria()) {
            prompt.append("- ").append(criterion.id()).append(": ")
                    .append(criterion.description())
                    .append(" | method=").append(criterion.verificationMethod())
                    .append(" | verifier=").append(criterion.verifier())
                    .append(" | signal=").append(criterion.testSignal())
                    .append(" | severity=").append(criterion.severity())
                    .append(" | applies_to=").append(criterion.appliesTo()).append('\n');
        }
        AgentMessage review = reviewer.executePlanReview(
                AgentMessage.task("orchestrator", prompt.toString()), out);
        if (review.type() == AgentMessage.Type.ERROR) {
            return new TeamPlanReviewProtocol.Evaluation(false, false, "", review.content());
        }
        return TeamPlanReviewProtocol.evaluate(
                review.content(),
                runState.acceptanceCriteria().stream().map(AcceptanceCriterion::id).toList(),
                runState.planStepIds(),
                runState.acceptanceCriteria().stream()
                        .filter(criterion -> "critical".equalsIgnoreCase(criterion.severity())
                                || "high".equalsIgnoreCase(criterion.severity()))
                        .map(AcceptanceCriterion::id)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private String validateGeneratedPlan(List<AgentOrchestrator.ExecutionStep> steps,
                                         String userInput) {
        String graphIssue = TeamPlannerProtocol.validate(
                steps, userInput,
                AgentOrchestrator.ExecutionStep::id,
                AgentOrchestrator.ExecutionStep::description,
                AgentOrchestrator.ExecutionStep::dependencies);
        if (graphIssue != null) {
            return graphIssue;
        }
        AcceptanceCriteriaPreflight.Report criteriaReport = acceptanceCriteriaPreflight();
        return criteriaReport.executable()
                ? null
                : "验收标准不可执行：" + criteriaReport.describeIssues();
    }

    private AgentMessage executePlanner(AgentMessage message) {
        try {
            return planner.execute(message, out);
        } finally {
            planner.clearHistory();
        }
    }

    List<AgentOrchestrator.ExecutionStep> parsePlan(String planJson) {
        try {
            log.debug("Parsing plan JSON, input length={}", planJson == null ? 0 : planJson.length());
            String cleaned = Objects.toString(planJson, "")
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = TeamPlannerProtocol.parsePlanRoot(mapper, cleaned);
            if (root == null) {
                return rejectPlan("Planner output does not contain a complete plan JSON object");
            }
            JsonNode criteriaNode = firstPresent(root,
                    "acceptance_criteria", "acceptanceCriteria", "acceptancecriteria");
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                stepsNode = root.path("tasks");
            }
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                return rejectPlan("Plan JSON has no 'steps' or 'tasks' array");
            }

            List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>();
            Map<String, String> idMapping = new HashMap<>();
            int stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                String originalId = stepNode.path("id").asText();
                String newId = "step_" + stepIndex++;
                idMapping.put(originalId, newId);
                steps.add(AgentOrchestrator.ExecutionStep.pending(
                        newId,
                        stepNode.path("description").asText(),
                        stepNode.path("type").asText("COMMAND"),
                        List.of()));
            }
            stepIndex = 1;
            for (JsonNode stepNode : stepsNode) {
                List<String> dependencies = new ArrayList<>();
                JsonNode dependencyNode = stepNode.path("dependencies");
                if (dependencyNode.isArray()) {
                    for (JsonNode dependency : dependencyNode) {
                        dependencies.add(idMapping.getOrDefault(
                                dependency.asText(), dependency.asText()));
                    }
                }
                int index = stepIndex++ - 1;
                AgentOrchestrator.ExecutionStep original = steps.get(index);
                steps.set(index, AgentOrchestrator.ExecutionStep.pending(
                        original.id(), original.description(), original.type(), dependencies));
            }

            runState.setAcceptanceCriteria(parseAcceptanceCriteria(criteriaNode, idMapping));
            List<AgentOrchestrator.ExecutionStep> normalized = coarsenPlanIfNeeded(steps);
            runState.setPlanStepIds(normalized.stream()
                    .map(AgentOrchestrator.ExecutionStep::id)
                    .collect(Collectors.toUnmodifiableSet()));
            ExecutionGraph.ValidationResult validation = ExecutionGraph.validate(normalized);
            if (!validation.valid()) {
                return rejectPlan("Plan graph validation failed: " + validation.errors());
            }
            return normalized;
        } catch (Exception e) {
            log.error("Failed to parse plan JSON", e);
            return rejectPlan("Failed to parse plan JSON");
        }
    }

    private List<AgentOrchestrator.ExecutionStep> rejectPlan(String reason) {
        log.warn(reason);
        runState.setAcceptanceCriteria(List.of());
        runState.setPlanStepIds(Set.of());
        return List.of();
    }

    List<AcceptanceCriterion> parseAcceptanceCriteria(JsonNode criteriaNode) {
        return parseAcceptanceCriteria(criteriaNode, Map.of());
    }

    private List<AcceptanceCriterion> parseAcceptanceCriteria(
            JsonNode criteriaNode,
            Map<String, String> idMapping) {
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
            String testSignal = firstPresent(
                    node, "test_signal", "testSignal", "testsignal").asText("");
            String severity = node.path("severity").asText("high");
            String verificationMethod = firstPresent(node,
                    "verification_method", "verificationMethod", "verificationmethod").asText("");
            String verifier = firstPresent(
                    node, "verifier", "verification_tool", "verificationTool").asText("");
            List<String> appliesTo = new ArrayList<>();
            JsonNode appliesToNode = firstPresent(node, "applies_to", "appliesTo", "appliesto");
            if (appliesToNode.isArray()) {
                for (JsonNode targetNode : appliesToNode) {
                    String target = targetNode.asText("").trim();
                    appliesTo.add("FINAL".equalsIgnoreCase(target)
                            ? "FINAL"
                            : idMapping.getOrDefault(target, target));
                }
            }
            AcceptanceCriterion criterion = new AcceptanceCriterion(
                    id, category, description, testSignal, severity,
                    AcceptanceCriterion.VerificationMethod.parse(verificationMethod),
                    verifier, appliesTo);
            if (criterion.isValid()) {
                criteria.add(criterion);
                index++;
            }
        }
        return List.copyOf(criteria);
    }

    AcceptanceCriteriaPreflight.Report acceptanceCriteriaPreflight() {
        return AcceptanceCriteriaPreflight.validate(
                runState.acceptanceCriteria(), this::isAllowedAcceptanceVerifier,
                runState.planStepIds());
    }

    private boolean isAllowedAcceptanceVerifier(String toolName) {
        if (!toolRegistry.hasTool(toolName)) {
            return false;
        }
        ToolRegistry.ToolEffect effect = toolRegistry.toolEffect(toolName);
        return effect == ToolRegistry.ToolEffect.READ_ONLY
                || effect == ToolRegistry.ToolEffect.HOST_PROCESS;
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

    List<AgentOrchestrator.ExecutionStep> coarsenPlanIfNeeded(
            List<AgentOrchestrator.ExecutionStep> steps) {
        if (steps == null || steps.size() <= MAX_PLANNER_STEPS) {
            return steps;
        }
        List<AgentOrchestrator.ExecutionStep> analysisSteps = new ArrayList<>();
        List<AgentOrchestrator.ExecutionStep> verificationSteps = new ArrayList<>();
        List<AgentOrchestrator.ExecutionStep> implementationSteps = new ArrayList<>();
        for (AgentOrchestrator.ExecutionStep step : steps) {
            String type = step.type() == null ? "" : step.type().toUpperCase(Locale.ROOT);
            String text = ((step.type() == null ? "" : step.type()) + " " + step.description())
                    .toLowerCase(Locale.ROOT);
            if (type.contains("VERIFICATION") || text.contains("验证") || text.contains("test")) {
                verificationSteps.add(step);
            } else if (type.contains("ANALYSIS") || type.contains("FILE_READ")
                    || text.contains("分析") || text.contains("读取")) {
                analysisSteps.add(step);
            } else {
                implementationSteps.add(step);
            }
        }

        List<AgentOrchestrator.ExecutionStep> coarse = new ArrayList<>();
        appendCoarseStep(coarse, "分析与准备", "ANALYSIS", analysisSteps);
        appendCoarseStep(coarse, "核心实现", "FILE_WRITE", implementationSteps);
        appendCoarseStep(coarse, "验证与修正", "VERIFICATION", verificationSteps);
        if (coarse.isEmpty()) {
            coarse.add(AgentOrchestrator.ExecutionStep.pending(
                    "step_1", mergeStepDescriptions("完成任务", steps), "FILE_WRITE", List.of()));
        }
        return coarse;
    }

    private void appendCoarseStep(List<AgentOrchestrator.ExecutionStep> target,
                                  String title,
                                  String type,
                                  List<AgentOrchestrator.ExecutionStep> source) {
        if (source.isEmpty()) {
            return;
        }
        List<String> dependencies = target.isEmpty()
                ? List.of()
                : List.of(target.get(target.size() - 1).id());
        target.add(AgentOrchestrator.ExecutionStep.pending(
                "step_" + (target.size() + 1),
                mergeStepDescriptions(title, source), type, dependencies));
    }

    private String mergeStepDescriptions(
            String title,
            List<AgentOrchestrator.ExecutionStep> steps) {
        StringBuilder description = new StringBuilder(title).append("：");
        for (AgentOrchestrator.ExecutionStep step : steps) {
            description.append("\n- ").append(step.description());
        }
        return description.append("\n按原始需求交付完整可用结果，不要只完成局部文件或口头说明。")
                .toString();
    }

    List<AgentOrchestrator.ExecutionStep> getExecutableSteps(
            List<AgentOrchestrator.ExecutionStep> steps) {
        return ExecutionGraph.ready(steps, AgentOrchestrator::isFinalIntegrationStep);
    }

    boolean shouldFuseFinalIntegration(List<AgentOrchestrator.ExecutionStep> steps) {
        List<AgentOrchestrator.ExecutionStep> normalSteps = steps.stream()
                .filter(step -> !AgentOrchestrator.isFinalIntegrationStep(step))
                .toList();
        if (normalSteps.isEmpty()) {
            return false;
        }
        long failed = normalSteps.stream()
                .filter(step -> step.status() == AgentOrchestrator.StepStatus.FAILED)
                .count();
        return (double) failed / normalSteps.size() >= FINAL_INTEGRATION_FAILURE_RATIO_LIMIT;
    }

    List<AgentOrchestrator.ExecutionStep> appendFinalIntegrationStep(
            List<AgentOrchestrator.ExecutionStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        boolean exists = steps.stream().anyMatch(step -> {
            String text = (step.id() + " " + step.description()).toLowerCase(Locale.ROOT);
            return text.contains("final_integration")
                    || text.contains("最终集成")
                    || text.contains("integration");
        });
        if (exists) {
            return steps;
        }
        Set<String> depended = steps.stream()
                .flatMap(step -> step.dependencies().stream())
                .collect(Collectors.toSet());
        List<String> leafStepIds = steps.stream()
                .map(AgentOrchestrator.ExecutionStep::id)
                .filter(id -> !depended.contains(id))
                .toList();
        String description = """
                最终集成验收：基于原始用户任务检查并补齐整体功能入口、跨模块联动、默认参数、错误处理和端到端可运行性。
                先读取现有生产文件，确认已存在的 class / method / signature，不要创建第二套入口。
                你只负责胶水代码、入口 main、对外 API 导出、默认参数注入和跨模块联动。
                不要重写或大改已 COMPLETED 的底层模块；如果核心依赖失败或缺失，直接说明风险，不要强行擦屁股。
                完成后运行最小编译或自检命令，修复集成层问题。
                """;
        List<AgentOrchestrator.ExecutionStep> withFinal = new ArrayList<>(steps);
        withFinal.add(AgentOrchestrator.ExecutionStep.pending(
                "step_" + (steps.size() + 1), description, "INTEGRATION", leafStepIds));
        return withFinal;
    }

    String summarizeSteps(List<AgentOrchestrator.ExecutionStep> steps) {
        StringBuilder summary = new StringBuilder();
        for (AgentOrchestrator.ExecutionStep step : steps) {
            String dependencies = step.dependencies().isEmpty()
                    ? "无"
                    : String.join(", ", step.dependencies());
            summary.append(String.format("  %s [%s] %s (依赖: %s)%n",
                    step.status() == AgentOrchestrator.StepStatus.COMPLETED ? "✅" : "⏳",
                    step.id(), step.description(), dependencies));
        }
        return summary.toString();
    }
}
