package com.devcli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmTraceLogger;
import com.devcli.prompt.PromptAssembler;
import com.devcli.prompt.PromptContext;
import com.devcli.prompt.PromptMode;
import com.devcli.util.AnsiStyle;
import com.devcli.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.io.IOException;
import java.util.*;

/**
 * 规划器 - 使用LLM将复杂任务分解为执行计划
 */
public class Planner {
    private static final Logger log = LoggerFactory.getLogger(Planner.class);
    private static final int MAX_PLAN_TASKS = 12;
    private static final int MAX_PLAN_REPAIR_ATTEMPTS = 2;
    private static final int INVALID_OUTPUT_PREVIEW_LIMIT = 2_000;

    private final LlmClient llmClient;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    /**
     * 为复杂任务创建执行计划
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        out.println("📋 正在规划任务: " + goal + "\n");

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        return createPlanFromInput(goal, goal, 0, "", "", List.of());
    }

    private ExecutionPlan createPlanFromInput(String rootGoal, String planningInput,
                                              int revision, String parentPlanId,
                                              String revisionReason,
                                              List<Task> completedTasks) throws IOException {
        String request = "请为以下任务制定执行计划：\n" + planningInput;
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_PLAN_REPAIR_ATTEMPTS; attempt++) {
            List<LlmClient.Message> messages = Arrays.asList(
                    LlmClient.Message.system(promptAssembler.assemble(
                            PromptMode.PLANNER, PromptContext.empty())),
                    LlmClient.Message.user(request)
            );
            PlanningStreamRenderer streamRenderer = new PlanningStreamRenderer(out);
            LlmClient.ChatResponse response = llmClient.chat(messages, null, streamRenderer);
            LlmTraceLogger.logReasoning(log, "planner", llmClient, response.reasoningContent());
            streamRenderer.finish();
            String planJson = response.content();
            try {
                return parsePlan(rootGoal, planJson, revision,
                        parentPlanId, revisionReason, completedTasks);
            } catch (IOException e) {
                lastFailure = e;
                if (attempt >= MAX_PLAN_REPAIR_ATTEMPTS) {
                    break;
                }
                out.println("⚠️ 计划输出无效，正在请求修复（" + (attempt + 1)
                        + "/" + MAX_PLAN_REPAIR_ATTEMPTS + "）...\n");
                request = buildRepairPrompt(planningInput, planJson, e.getMessage(), attempt + 1);
            }
        }
        throw lastFailure == null ? new IOException("计划输出无效") : lastFailure;
    }

    /**
     * 解析LLM生成的计划JSON
     */
    private ExecutionPlan parsePlan(String rootGoal, String planJson, int revision,
                                    String parentPlanId, String revisionReason,
                                    List<Task> completedTasks) throws IOException {
        String cleaned = Objects.toString(planJson, "")
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        if (cleaned.isEmpty()) {
            throw new IOException("计划输出为空");
        }

        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (Exception e) {
            throw new IOException("计划不是合法 JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("计划根节点必须是 JSON 对象");
        }
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            // 兼容编排层与提示词中常见的 steps 命名，避免模型输出 steps 而计划层只认 tasks 导致空计划
            tasksNode = root.path("steps");
        }
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IOException("计划必须包含至少一个任务(tasks/steps)");
        }
        if (tasksNode.size() > MAX_PLAN_TASKS) {
            throw new IOException("计划任务数超过上限 " + MAX_PLAN_TASKS);
        }

        ExecutionPlan plan = new ExecutionPlan(
                generatePlanId(revision), rootGoal, revision, parentPlanId, revisionReason);
        plan.setSummary(root.path("summary").asText(""));
        copyCompletedTasks(plan, completedTasks);

        Map<String, String> idMapping = new LinkedHashMap<>();
        Set<String> completedIds = completedTasks == null
                ? Set.of()
                : completedTasks.stream().map(Task::getId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            if (!taskNode.isObject()) {
                throw new IOException("计划任务必须是 JSON 对象");
            }
            String originalId = taskNode.path("id").asText("").trim();
            if (originalId.isEmpty()) {
                throw new IOException("计划任务 id 不能为空");
            }
            if (idMapping.containsKey(originalId)) {
                throw new IOException("计划任务 id 重复: " + originalId);
            }
            String description = taskNode.path("description").asText("").trim();
            if (description.isEmpty()) {
                throw new IOException("计划任务描述不能为空: " + originalId);
            }
            if (completedTasks != null && completedTasks.stream()
                    .map(Task::getDescription)
                    .map(Planner::normalizeDescription)
                    .anyMatch(normalizeDescription(description)::equals)) {
                throw new IOException("修订计划重复了已完成任务: " + description);
            }
            Task.TaskType type = parseTaskType(taskNode.path("type").asText(""));
            String newId = revision == 0
                    ? "task_" + taskIndex
                    : "r" + revision + "_task_" + taskIndex;
            taskIndex++;
            idMapping.put(originalId, newId);
            plan.addTask(new Task(newId, description, type));
        }

        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = revision == 0
                    ? "task_" + taskIndex
                    : "r" + revision + "_task_" + taskIndex;
            taskIndex++;
            Task task = plan.getTask(newId);
            JsonNode depsNode = taskNode.path("dependencies");
            if (!depsNode.isMissingNode() && !depsNode.isArray()) {
                throw new IOException("任务 dependencies 必须是数组: " + taskNode.path("id").asText());
            }
            if (!depsNode.isArray()) {
                continue;
            }
            for (JsonNode depNode : depsNode) {
                String dependency = depNode.asText("").trim();
                if (dependency.isEmpty()) {
                    throw new IOException("任务依赖 id 不能为空: " + task.getId());
                }
                String mapped = resolveDependency(dependency, idMapping, completedIds);
                if (mapped == null) {
                    throw new IOException("计划缺少依赖: " + task.getId() + " -> " + dependency);
                }
                task.addDependency(mapped);
                Task dependencyTask = plan.getTask(mapped);
                if (dependencyTask != null) {
                    dependencyTask.addDependent(task.getId());
                }
            }
        }

        if (!plan.computeExecutionOrder()) {
            ExecutionGraph.ValidationResult validation = ExecutionGraph.validate(
                    new ArrayList<>(plan.getAllTasks()));
            throw new IOException("计划图无效: " + String.join("; ", validation.errors()));
        }
        return plan;
    }

    private static void copyCompletedTasks(ExecutionPlan plan, List<Task> completedTasks) {
        if (completedTasks == null || completedTasks.isEmpty()) {
            return;
        }
        for (Task source : completedTasks) {
            Task copy = new Task(
                    source.getId(), source.getDescription(), source.getType(), source.getDependencies());
            copy.applyArtifact(source.getArtifact());
            plan.addTask(copy);
        }
        for (Task task : plan.getAllTasks()) {
            for (String dependency : task.getDependencies()) {
                Task dependencyTask = plan.getTask(dependency);
                if (dependencyTask != null) {
                    dependencyTask.addDependent(task.getId());
                }
            }
        }
    }

    private static String resolveDependency(String dependency,
                                            Map<String, String> idMapping,
                                            Set<String> completedIds) {
        String mapped = idMapping.get(dependency);
        if (mapped != null) {
            return mapped;
        }
        String completedPrefix = "completed:";
        if (dependency.startsWith(completedPrefix)) {
            String completedId = dependency.substring(completedPrefix.length()).trim();
            return completedIds.contains(completedId) ? completedId : null;
        }
        return completedIds.contains(dependency) ? dependency : null;
    }

    private static String normalizeDescription(String value) {
        return Objects.toString(value, "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String buildRepairPrompt(String planningInput, String invalidOutput,
                                            String failureReason, int attempt) {
        String preview = compactForReplan(invalidOutput, INVALID_OUTPUT_PREVIEW_LIMIT);
        return """
                上一次计划输出未通过协议或 DAG 校验，请修复后重新输出。
                原始规划输入：
                %s

                失败原因：%s
                修复轮次：%d/%d
                无效输出预览：
                %s

                只输出一个 JSON 对象，格式必须为：
                {"summary":"...","tasks":[{"id":"task_1","description":"...","type":"FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION","dependencies":[]}]}
                tasks 必须非空，id 必须唯一，依赖必须引用同一计划内任务；修订计划可以用 completed:<旧任务ID> 引用已完成任务。
                """.formatted(
                Objects.toString(planningInput, ""),
                Objects.toString(failureReason, "计划输出无效"),
                attempt,
                MAX_PLAN_REPAIR_ATTEMPTS,
                preview);
    }

    /**
     * 解析任务类型
     */
    private Task.TaskType parseTaskType(String typeStr) throws IOException {
        return switch (Objects.toString(typeStr, "").trim().toUpperCase(Locale.ROOT)) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> throw new IOException("未知任务类型: " + typeStr);
        };
    }

    /**
     * 生成计划ID
     */
    private String generatePlanId(int revision) {
        return "plan_" + System.currentTimeMillis() + "_r" + Math.max(0, revision);
    }

    /**
     * 根据执行结果重新规划
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        out.println("🔄 重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原始目标: ").append(failedPlan.getGoal()).append("\n");
        context.append("当前计划版本: r").append(failedPlan.getRevision()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n\n");

        appendReplanTaskSection(context,
                "已完成的任务（请勿重复执行，这些任务的结果已落盘）",
                failedPlan.getAllTasks().stream()
                        .filter(task -> task.getStatus() == Task.TaskStatus.COMPLETED)
                        .toList());
        context.append("\n");
        appendReplanTaskSection(context,
                "失败或未完成的任务（新计划只覆盖这些任务，且必须考虑其已产生的部分副作用）",
                failedPlan.getAllTasks().stream()
                        .filter(task -> task.getStatus() != Task.TaskStatus.COMPLETED)
                        .toList());

        context.append("\n请制定新的执行计划，仅覆盖未完成或失败的任务。");
        context.append("新计划不得包含已完成的任务；如需依赖已完成任务，请在 dependencies 中使用 completed:<旧任务ID>。");
        context.append("如果需要再次触达已修改文件，必须说明是基于当前落盘内容继续处理。");
        context.append("\n失败原因: ").append(failureReason);

        List<Task> completedTasks = failedPlan.getAllTasks().stream()
                .filter(task -> task.getStatus() == Task.TaskStatus.COMPLETED)
                .toList();
        return createPlanFromInput(
                failedPlan.getGoal(),
                context.toString(),
                failedPlan.getRevision() + 1,
                failedPlan.getId(),
                failureReason,
                completedTasks);
    }

    public ExecutionPlan reviseForFeedback(ExecutionPlan plan, String feedback) throws IOException {
        String normalizedFeedback = Objects.toString(feedback, "").trim();
        String input = plan.getGoal() + "\n补充要求：" + normalizedFeedback;
        return createPlanFromInput(
                plan.getGoal(),
                input,
                plan.getRevision() + 1,
                plan.getId(),
                "用户补充要求",
                List.of());
    }

    private static void appendReplanTaskSection(StringBuilder context, String title, List<Task> tasks) {
        context.append(title).append("：\n");
        if (tasks == null || tasks.isEmpty()) {
            context.append("- 无\n");
            return;
        }
        for (Task task : tasks) {
            context.append("- ").append(task.getId())
                    .append(": ").append(task.getDescription())
                    .append(" / 状态=").append(task.getStatus())
                    .append("\n");
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("  可作为依赖引用: completed:").append(task.getId()).append("\n");
            }
            if (!task.getModifiedFiles().isEmpty()) {
                context.append("  修改文件: ").append(String.join(", ", task.getModifiedFiles())).append("\n");
            }
            String summary = task.getResultSummary();
            if (summary == null || summary.isBlank()) {
                summary = compactForReplan(task.getResult(), 300);
            }
            if (summary != null && !summary.isBlank()) {
                context.append("  结论: ").append(summary).append("\n");
            }
            if (task.getError() != null && !task.getError().isBlank()) {
                context.append("  错误: ").append(compactForReplan(task.getError(), 240)).append("\n");
            }
        }
    }

    private static String compactForReplan(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(0), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || normalized.contains("查看")
                && normalized.contains("文件")) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }

    private static final class PlanningStreamRenderer implements LlmClient.StreamListener {
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private boolean reasoningStarted;
        private boolean streamed;

        private PlanningStreamRenderer(PrintStream out) {
            this.out = out == null ? System.out : out;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 规划思考"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
                streamed = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        private void finish() {
            if (streamed) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                out.println("\n");
            }
        }
    }
}
