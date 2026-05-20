package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ResourceConflictDetector;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolRegistry;
import com.paicli.trace.TraceContext;
import com.paicli.trace.TraceRecorder;
import com.paicli.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
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
    private static final double MIN_REVIEW_SCORE = 0.6;
    private static final double REQUIRED_FUNCTIONAL_SCORE = 1.0;
    private static final double FINAL_INTEGRATION_FAILURE_RATIO_LIMIT = 0.5;
    private static final int PRE_REVIEW_TIMEOUT_SECONDS = 60;
    private static final int MAX_PLANNER_STEPS = 5;

    private final LlmClient llmClient;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final MemoryManager memoryManager;
    private final ToolRegistry toolRegistry;
    private final PrintStream out;
    private String currentUserTask = "";
    private Supplier<String> externalContextSupplier = () -> "";
    private Supplier<String> stickyMemorySupplier = () -> "";
    private com.paicli.skill.SkillRegistry skillRegistry;
    private com.paicli.skill.SkillContextBuffer skillContextBuffer;
    private final TraceRecorder traceRecorder = new TraceRecorder();
    private List<AcceptanceCriterion> currentAcceptanceCriteria = List.of();

    // 执行步骤的数据结构（package-private 供测试访问）
    record ExecutionStep(String id, String description, String type,
                                  List<String> dependencies, String result,
                                  StepStatus status) {
        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        ExecutionStep withResult(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String result) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    record PreReviewResult(boolean passed, String feedback) {
        static PreReviewResult ok() {
            return new PreReviewResult(true, "");
        }

        static PreReviewResult failed(String feedback) {
            return new PreReviewResult(false, feedback == null ? "Pre-review hard check failed" : feedback);
        }
    }

    record AcceptanceCriterion(String id, String category, String description, String testSignal) {
        boolean isValid() {
            return !id.isBlank() && !description.isBlank();
        }

        String formatForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("- ").append(id);
            if (!category.isBlank()) {
                sb.append(" [").append(category).append("]");
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
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setMemorySaver(memoryManager::storeFact);
        this.toolRegistry.setMemorySaveHandler(fact -> {
            MemoryManager.StoreResult result = memoryManager.storeFactWithPolicy(fact, true);
            return new ToolRegistry.MemorySaveResult(result.stored(), result.message());
        });
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        this.workers = List.of(
                new SubAgent("worker-1", AgentRole.WORKER, llmClient, toolRegistry),
                new SubAgent("worker-2", AgentRole.WORKER, llmClient, toolRegistry)
        );
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
        this.memoryManager = memoryManager;
        configureSubAgent(planner);
        workers.forEach(this::configureSubAgent);
        configureSubAgent(reviewer);
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
    public void setSkillSystem(com.paicli.skill.SkillRegistry skillRegistry,
                               com.paicli.skill.SkillContextBuffer skillContextBuffer) {
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
        agent.setToolResultConsumer(memoryManager::addToolResult);
        agent.setSkillRegistry(skillRegistry);
        agent.setSkillContextBuffer(skillContextBuffer == null ? null : skillContextBuffer.copy());
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
        currentAcceptanceCriteria = List.of();
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        // 1. 规划阶段：让规划者拆解任务
        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");

        AgentMessage planMessage = AgentMessage.task("orchestrator",
                "请为以下任务制定执行计划：\n" + userInput);
        AgentMessage planResult = planner.execute(planMessage, out);
        planner.clearHistory();
        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消当前多 Agent 任务。";
        }

        if (planResult.type() == AgentMessage.Type.ERROR) {
            return "❌ 规划阶段失败，规划者 LLM 调用出错：" + planResult.content();
        }
        if (planResult.content() == null || planResult.content().isBlank()) {
            return "❌ 规划失败：规划者未能生成有效计划";
        }

        // 2. 解析计划
        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "❌ 规划失败：无法解析执行计划\n原始输出:\n" + planResult.content();
        }
        steps = coarsenPlanIfNeeded(steps);
        steps = appendFinalIntegrationStep(steps);

        out.println(AnsiStyle.heading("📋 执行计划"));
        out.println(summarizeSteps(steps) + "\n");

        // 3. 执行阶段：按依赖顺序分配给执行者
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行"));
        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前多 Agent 任务。";
            }
            List<ExecutionStep> executable = getExecutableSteps(steps);
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
                SubAgent worker = workers.get(singleStepCursor % workers.size());
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

        return finalResult;
    }

    /**
     * 解析规划者输出的 JSON 计划
     */
    List<ExecutionStep> parsePlan(String planJson) {
        try {
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            JsonNode root = mapper.readTree(cleaned);
            currentAcceptanceCriteria = parseAcceptanceCriteria(firstPresent(root,
                    "acceptance_criteria", "acceptanceCriteria", "acceptancecriteria"));
            JsonNode stepsNode = root.path("steps");

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                // 尝试 "tasks" 字段（兼容 Plan-and-Execute 的格式）
                stepsNode = root.path("tasks");
            }

            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                log.warn("Plan JSON has no 'steps' or 'tasks' array");
                return List.of();
            }

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
                                deps, old.result(), old.status()));
                    }
                }
            }

            return coarsenPlanIfNeeded(steps);
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
            AcceptanceCriterion criterion = new AcceptanceCriterion(id, category, description, testSignal);
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
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        List<ExecutionStep> normalExecutable = steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> !isFinalIntegrationStep(step))
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
        if (!normalExecutable.isEmpty()) {
            return normalExecutable;
        }

        boolean hasRunningNonFinal = steps.stream()
                .filter(step -> !isFinalIntegrationStep(step))
                .anyMatch(step -> step.status() == StepStatus.RUNNING);
        if (hasRunningNonFinal) {
            return List.of();
        }
        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(this::isFinalIntegrationStep)
                .toList();
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
                你只负责胶水代码、入口 main、对外 API 导出、默认参数注入和跨模块联动。
                不要重写或大改已 COMPLETED 的底层模块；如果核心依赖失败或缺失，直接说明风险，不要强行擦屁股。
                完成后运行最小编译或自检命令，修复集成层问题。
                """;
        List<ExecutionStep> withFinal = new ArrayList<>(steps);
        withFinal.add(ExecutionStep.pending(finalId, description, "INTEGRATION", leafStepIds));
        return withFinal;
    }

    private boolean isFinalIntegrationStep(ExecutionStep step) {
        String text = (step.id() + " " + step.type() + " " + step.description()).toLowerCase(Locale.ROOT);
        return text.contains("final_integration")
                || text.contains("最终集成")
                || text.contains("integration");
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
            // 无法解析 JSON：必须同时不含否定关键词且含有肯定关键词，才视为通过
            String lower = reviewContent.toLowerCase();
            boolean hasNegativeKeyword = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean hasPositiveKeyword = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            if (hasNegativeKeyword) {
                return false;
            }
            if (!hasPositiveKeyword) {
                log.warn("Reviewer output unparseable and contains no explicit approval, defaulting to rejected");
                return false;
            }
            return true;
        }
    }

    private boolean hasFailedBlockingCriteria(JsonNode criteriaResultsNode) {
        if (criteriaResultsNode == null || !criteriaResultsNode.isArray()) {
            return false;
        }
        for (JsonNode result : criteriaResultsNode) {
            boolean passed = result.path("passed").asBoolean(false);
            String severity = result.path("severity").asText("").toLowerCase(Locale.ROOT);
            if (!passed && (severity.equals("critical") || severity.equals("high"))) {
                return true;
            }
        }
        return false;
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
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
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
            Thread t = new Thread(r, "paicli-multi-agent");
            t.setDaemon(true);
            return t;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        SubAgent.ForkContext workerForkContext = workers.get(0).createForkContext();
        SubAgent reviewerForkTemplate = new SubAgent("reviewer-fork-template", AgentRole.REVIEWER, llmClient, toolRegistry);
        configureSubAgent(reviewerForkTemplate);
        SubAgent.ForkContext reviewerForkContext = reviewerForkTemplate.createForkContext();

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
            String context = buildStepContext(steps, step);

            futures.add(executor.submit(() -> {
                SubAgent worker = null;
                SubAgent localReviewer = new SubAgent(
                        "reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);
                configureSubAgent(localReviewer);
                try {
                    worker = workerPool.take();
                    runStep(step, steps, retryCount, worker, localReviewer, context, stepOut,
                            workerForkContext, reviewerForkContext);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                } catch (RuntimeException e) {
                    log.error("Parallel step {} failed unexpectedly", step.id(), e);
                    updateStep(steps, step.id(), step.withFailed("并行执行异常: " + e.getMessage()));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    if (worker != null) {
                        worker.clearHistory();
                        workerPool.offer(worker);
                    }
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
        out.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        if (CancellationContext.isCancelled()) {
            updateStep(steps, step.id(), step.withFailed("用户取消"));
            out.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
            return;
        }

        AgentMessage taskMsg = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = workerForkContext == null
                ? worker.executeWithContext(taskMsg, context, out)
                : worker.executeForkedWithContext(taskMsg, context, workerForkContext, out);
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
        if (result.content() == null || result.content().isBlank()) {
            updateStep(steps, step.id(), step.withFailed("执行结果为空"));
            out.println("❌ 步骤 [" + step.id() + "] 执行失败：结果为空\n");
            return;
        }

        ReviewDecision reviewDecision = reviewWorkerResult(step, reviewer, result.content(), out, reviewerForkContext);
        boolean approved = reviewDecision.approved();
        String acceptedResult = result.content();

        if (approved) {
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            out.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
            return;
        }

        int retries = retryCount.getOrDefault(step.id(), 0);
        String issues = reviewDecision.issues();
        if (reviewDecision.reviewerError()) {
            updateStep(steps, step.id(), step.withFailed(issues));
            return;
        }
        log.info("Step {} rejected (retry {}/{}): {}", step.id(), retries, MAX_RETRIES_PER_STEP, issues);

        while (!approved && retries < MAX_RETRIES_PER_STEP) {
            retries++;
            retryCount.put(step.id(), retries);
            out.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，正在重新执行...");
            out.println("   反馈: " + issues + "\n");

            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = workerForkContext == null
                    ? worker.executeWithContext(taskMsg, feedbackContext, out)
                    : worker.executeForkedWithContext(taskMsg, feedbackContext, workerForkContext, out);
            if (retryResult.type() == AgentMessage.Type.ERROR) {
                log.warn("Step {} retry {} failed at LLM layer: {}", step.id(), retries, retryResult.content());
                issues = "重试时 LLM 调用失败：" + retryResult.content();
                approved = false;
                continue;
            }
            if (retryResult.content() == null || retryResult.content().isBlank()) {
                acceptedResult = "执行结果为空";
                approved = false;
                issues = "执行结果为空";
                log.info("Step {} retry {} returned empty result", step.id(), retries);
                continue;
            }

            acceptedResult = retryResult.content();
            ReviewDecision retryReview = reviewWorkerResult(step, reviewer, acceptedResult, out, reviewerForkContext);
            if (retryReview.reviewerError()) {
                issues = retryReview.issues();
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

    private ReviewDecision reviewWorkerResult(ExecutionStep step, SubAgent reviewer, String workerResult,
                                              PrintStream out, SubAgent.ForkContext reviewerForkContext) {
        PreReviewResult preReview = runPreReviewHook(step);
        if (!preReview.passed()) {
            out.println("⛔ 步骤 [" + step.id() + "] Pre-Review Hook 未通过，跳过 Reviewer LLM");
            out.println("   反馈: " + preReview.feedback() + "\n");
            return new ReviewDecision(false, preReview.feedback(), false);
        }

        out.println("🔍 " + reviewer.getName() + " 正在审查步骤 [" + step.id() + "] 的结果...");
        String reviewTask = buildReviewTask(step);
        List<String> reviewToolCalls = Collections.synchronizedList(new ArrayList<>());
        reviewer.setToolResultConsumer((name, args, result) -> {
            memoryManager.addToolResult(name, args, result);
            reviewToolCalls.add(name);
        });
        AgentMessage reviewResult = reviewerForkContext == null
                ? reviewer.review(reviewTask, workerResult, out)
                : reviewer.reviewForked(reviewTask, workerResult, reviewerForkContext, out);
        reviewer.clearHistory();

        if (reviewResult.type() == AgentMessage.Type.ERROR) {
            log.warn("Reviewer failed for step {}: {}", step.id(), reviewResult.content());
            out.println("❌ 步骤 [" + step.id() + "] 审查阶段 LLM 调用失败，阻止下游步骤继续执行\n");
            return new ReviewDecision(false, "审查 LLM 故障：" + reviewResult.content(), true);
        }
        if (requiresConcreteVerification(step) && reviewToolCalls.isEmpty()) {
            return new ReviewDecision(false,
                    "Reviewer 未调用工具验证真实产物；文件/代码/命令类任务不能只根据 Worker 文字说明批准。", false);
        }

        return new ReviewDecision(parseReviewApproval(reviewResult.content()),
                parseReviewIssues(reviewResult.content()), false);
    }

    record ReviewDecision(boolean approved, String issues, boolean reviewerError) {
    }

    PreReviewResult runPreReviewHook(ExecutionStep step) {
        if (!requiresConcreteVerification(step) || !requiresJavaHardCheck(step)) {
            return PreReviewResult.ok();
        }
        Path projectRoot = Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize();
        Path javaRoot = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(javaRoot)) {
            return PreReviewResult.ok();
        }

        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            return runPreReviewCommand(projectRoot,
                    mavenTestCompileCommand(),
                    "mvn -q -DskipTests test-compile");
        }

        List<Path> javaFiles;
        try (var stream = Files.walk(javaRoot)) {
            javaFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            return PreReviewResult.failed("Pre-review hard check failed: 无法扫描 Java 文件：" + e.getMessage());
        }
        if (javaFiles.isEmpty()) {
            return PreReviewResult.ok();
        }

        Path outputDir = projectRoot.resolve("target/paicli-pre-review-classes/" + step.id());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return PreReviewResult.failed("Pre-review hard check failed: 无法创建编译目录：" + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-d");
        command.add(outputDir.toString());
        for (Path file : javaFiles) {
            command.add(file.toString());
        }
        return runPreReviewCommand(projectRoot, command, "javac -encoding UTF-8");
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

    private List<String> mavenTestCompileCommand() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/c", "mvn", "-q", "-DskipTests", "test-compile");
        }
        return List.of("mvn", "-q", "-DskipTests", "test-compile");
    }

    private PreReviewResult runPreReviewCommand(Path projectRoot, List<String> command, String displayCommand) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(PRE_REVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return PreReviewResult.failed("Pre-review hard check failed: " + displayCommand
                        + " 超过 " + PRE_REVIEW_TIMEOUT_SECONDS + "s");
            }
            String output = decodeProcessOutput(process.getInputStream().readAllBytes());
            if (process.exitValue() == 0) {
                return PreReviewResult.ok();
            }
            return PreReviewResult.failed("Pre-review hard check failed: " + displayCommand
                    + "\n" + abbreviate(output, 4000));
        } catch (IOException e) {
            return PreReviewResult.failed("Pre-review hard check failed: 无法执行 " + displayCommand
                    + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PreReviewResult.failed("Pre-review hard check failed: " + displayCommand + " 被中断");
        }
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "\n...<truncated>";
    }

    private String decodeProcessOutput(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!looksMojibake(utf8)) {
            return utf8;
        }
        String platform = new String(bytes, Charset.defaultCharset());
        if (!looksMojibake(platform)) {
            return platform;
        }
        try {
            String gbk = new String(bytes, Charset.forName("GBK"));
            if (!looksMojibake(gbk)) {
                return gbk;
            }
        } catch (Exception ignored) {
        }
        return utf8;
    }

    private boolean looksMojibake(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.indexOf('\uFFFD') >= 0 || text.contains("????");
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        StringBuilder context = new StringBuilder();
        context.append("总任务上下文：\n");
        if (currentUserTask != null && !currentUserTask.isBlank()) {
            context.append("原始用户任务：\n").append(currentUserTask).append("\n\n");
        }
        appendAcceptanceCriteriaSection(context, "本步骤必须满足以下验收点");
        context.append("当前步骤：").append(currentStep.description()).append("\n\n");
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
                }
            }
            context.append("\n");
        }

        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && currentStep.dependencies().contains(step.id())) {
                context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                        .append(step.description()).append("\n");
                if (step.result() != null && !step.result().isBlank()) {
                    String preview = step.result().length() > 500
                            ? step.result().substring(0, 500) + "..."
                            : step.result();
                    context.append("结果：").append(preview).append("\n");
                }
                context.append("\n");
            }
        }

        return context.toString();
    }

    private String buildReviewTask(ExecutionStep step) {
        StringBuilder task = new StringBuilder();
        if (currentUserTask != null && !currentUserTask.isBlank()) {
            task.append("原始用户任务：\n").append(currentUserTask).append("\n\n");
        }
        appendAcceptanceCriteriaSection(task, "逐条验证以下验收点");
        task.append("当前步骤：").append(step.description());
        if (requiresConcreteVerification(step)) {
            task.append("\n\n审查要求：必须调用工具检查真实产物。")
                    .append("至少确认相关文件/入口/API 是否存在；如果步骤涉及代码，运行可行的最小编译或自检命令。")
                    .append("仅凭执行者文字说明不得批准。");
        }
        return task.toString();
    }

    private void appendAcceptanceCriteriaSection(StringBuilder sb, String title) {
        if (currentAcceptanceCriteria == null || currentAcceptanceCriteria.isEmpty()) {
            return;
        }
        sb.append("⚠️ ").append(title).append("：\n");
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
        boolean allCompleted = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
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
