package com.devcli.memory;

import com.devcli.rag.RagEvidencePayload;
import com.devcli.rag.RagEvidenceSideChannel;
import com.devcli.policy.SensitiveDataRedactor;
import com.devcli.tool.ToolResultArtifact;
import com.devcli.tool.ToolSideChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 当前任务的会话记忆。
 *
 * <p>取代旧的 {@code ConversationMemory}（旧版本同时承担"对话原文笔记本"和"压缩状态"，
 * 职责模糊导致两条数据流跟 {@code conversationHistory} 重复）。
 *
 * <p>SessionMemory 是短期记忆的唯一深 Module：
 * <ul>
 *   <li>不参与 LLM messages 数组（messages 由 {@code Agent.conversationHistory} 维护）</li>
 *   <li>作为 system prompt 的<b>派生视图</b>注入，解决"摘要泛化掉精确实体"的痛点</li>
 *   <li>仅当前会话有效，不跨 session 持久化</li>
 *   <li>三类内容互不重叠：</li>
 * </ul>
 *
 * <p>内部只有两个区域：
 * <ol>
 *   <li><b>WorkState</b>：目标、计划、步骤、约束、根因、修改文件和下一步动作，按键覆盖或按状态机推进。</li>
 *   <li><b>EvidenceJournal</b>：工具证据按重要性和 Token 预算增量合并，失败保留摘要，可再生结果保留引用。</li>
 * </ol>
 *
 * <p>系统只把本类和 {@link LongTermMemory} 视为记忆。Conversation History / Summary 属于上下文治理，
 * 规则文件属于 RuleContext，均不是记忆层。
 * <p>线程安全：ReAct / Plan 主循环通常是单线程，但 Multi-Agent 会让多个 SubAgent
 * 并发回写工具证据到同一个 {@link MemoryManager}，因此所有读写方法都同步保护。
 */
public class SessionMemory {

    private static final Logger log = LoggerFactory.getLogger(SessionMemory.class);

    /** 条目数量只是防御性硬上限，正常治理由 Token 预算和重要性完成。 */
    public static final int DEFAULT_MAX_TOOL_RESULTS = 64;
    public static final int DEFAULT_EVIDENCE_TOKEN_BUDGET = 6_000;
    /** 默认保留多少个 volatile facts。 */
    public static final int DEFAULT_MAX_VOLATILE_FACTS = 16;
    private static final int MAX_PROTECTED_CONSTRAINTS = 64;
    private static final int PROTECTED_CONSTRAINT_CHARS = 600;
    /** 单条 tool 结果在注入时截断到此字符数。完整原文仍保留在 recentToolResults，仅渲染时截断。 */
    public static final int TOOL_RESULT_RENDER_CHARS = 1_500;
    public static final int DEFAULT_MAX_RAG_EVIDENCE = 8;
    // Bug #9 修复：第一个捕获组改为 \w+，只匹配 chunkType (method/class/file)
    // 避免在 Windows 路径 C:\Users\... 时被盘符冒号截断
    private static final Pattern SEARCH_RESULT_HEADER = Pattern.compile(
            "^\\s*\\d+\\. \\[(\\w+):([^\\]]+)] \\(相似度: ([^)]+)\\) (.+)$");
    private static final Pattern SEARCH_RESULT_EVIDENCE = Pattern.compile(
            "^\\s*evidence: symbolVersion=([^,]+), (?:indexEpoch=([^,]+), )?classpathEpoch=(.+)$");
    private static final Pattern SEARCH_RESULT_NEGATIVE_FACT = Pattern.compile("^\\s*negativeFact: (.+)$");
    /** 从 negativeFact 行提取被失效的旧 symbolVersion，用于即时清理对应的过期 RAG 证据。 */
    private static final Pattern NEGATIVE_FACT_OLD_SYMBOL_VERSION = Pattern.compile("oldSymbolVersion=([^,\\s]+)");
    private static final Pattern PATH_ARG = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MICROCOMPACT_TOOL_CALL_ID = Pattern.compile("(?m)^toolCallId=(.+)$");
    private static final Pattern MICROCOMPACT_ORIGINAL_CHARS = Pattern.compile("(?m)^originalChars=(.+)$");
    private static final Pattern MICROCOMPACT_STORED_PATH = Pattern.compile("(?m)^storedPath=(.+)$");

    private final int maxToolResults;
    private final int evidenceTokenBudget;
    private final int maxVolatileFacts;
    private final int maxRagEvidence;
    private final LinkedList<ToolEvidence> recentToolResults = new LinkedList<>();
    private final LinkedList<RagEvidence> ragEvidenceMemory = new LinkedList<>();
    private final LinkedList<KeyFact> volatileFacts = new LinkedList<>();
    private final LinkedHashMap<String, String> taskState = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> protectedConstraints = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> stateSequences = new LinkedHashMap<>();
    private final LinkedHashSet<String> modifiedFiles = new LinkedHashSet<>();
    private final LinkedHashMap<String, AttemptDigestSnapshot> attemptDigests = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> stepSequences = new LinkedHashMap<>();
    private final LinkedHashMap<String, EvidenceOrigin> activeEvidenceOrigins = new LinkedHashMap<>();
    private final LinkedHashSet<String> processedEventIds = new LinkedHashSet<>();
    private String taskId = "";
    private boolean taskEnded;
    private long planSequence = Long.MIN_VALUE;
    private long localSequence;
    private final TaskLedger taskLedger = new TaskLedger();

    public enum SessionView {
        FULL,
        PLANNER,
        WORKER,
        REVIEWER
    }

    public SessionMemory() {
        this(DEFAULT_MAX_TOOL_RESULTS, DEFAULT_MAX_VOLATILE_FACTS, DEFAULT_MAX_RAG_EVIDENCE);
    }

    public SessionMemory(int maxToolResults, int maxVolatileFacts) {
        this(maxToolResults, maxVolatileFacts, DEFAULT_MAX_RAG_EVIDENCE);
    }

    public SessionMemory(int maxToolResults, int maxVolatileFacts, int maxRagEvidence) {
        this.maxToolResults = Math.max(1, maxToolResults);
        this.maxVolatileFacts = Math.max(1, maxVolatileFacts);
        this.maxRagEvidence = Math.max(1, maxRagEvidence);
        this.evidenceTokenBudget = Math.max(1_000,
                maxToolResults == DEFAULT_MAX_TOOL_RESULTS
                        ? DEFAULT_EVIDENCE_TOKEN_BUDGET
                        : maxToolResults * TOOL_RESULT_RENDER_CHARS / 4);
    }

    /** 会话记忆的统一写入 seam。调用方只提交事件，不参与内部合并和裁剪。 */
    public synchronized void accept(SessionEvent event) {
        if (event == null) return;
        long sequence = event.sequence() > 0 ? event.sequence() : ++localSequence;
        String eventId = event.getClass().getSimpleName() + "\u0000" + event.agentId()
                + "\u0000" + event.stepId() + "\u0000" + sequence;
        if (!processedEventIds.add(eventId)) return;
        while (processedEventIds.size() > 4_096) {
            processedEventIds.remove(processedEventIds.iterator().next());
        }
        localSequence = Math.max(localSequence, sequence);
        if (event instanceof StateChanged changed) {
            applyStateChange(changed.key(), changed.value(), sequence);
        } else if (event instanceof EvidenceScopeStarted started) {
            String key = evidenceOriginKey(started.agentId(), started.stepId());
            EvidenceOrigin previous = activeEvidenceOrigins.get(key);
            if (previous == null || started.originSequence() >= previous.originSequence()) {
                activeEvidenceOrigins.put(key, new EvidenceOrigin(
                        started.originSequence(), started.contextEpoch()));
            }
        } else if (event instanceof ToolResultObserved observed) {
            if (!acceptEvidenceOrigin(observed)) return;
            recordToolResultInternal(observed.toolName(), observed.argsJson(), observed.result(),
                    observed.sideChannels(), observed.agentId(), observed.stepId(),
                    observed.originSequence(), observed.contextEpoch(), sequence);
        } else if (event instanceof KeyEvent keyEvent) {
            int importance = keyEvent.importance() > 0
                    ? keyEvent.importance() : inferEventImportance(keyEvent.description());
            addKeyEvent(keyEvent.description(), importance, keyEvent.agentId(), keyEvent.stepId(), sequence);
        } else if (event instanceof PlanChanged plan) {
            if (sequence < planSequence) return;
            planSequence = sequence;
            taskLedger.setPlan(plan.planId(), plan.goal(), plan.steps());
            applyStateChange("goal", plan.goal(), sequence);
            applyStateChange("plan_version", plan.planId(), sequence);
        } else if (event instanceof StepChanged step) {
            long previous = stepSequences.getOrDefault(step.stepId(), Long.MIN_VALUE);
            if (sequence < previous) return;
            stepSequences.put(step.stepId(), sequence);
            switch (step.status()) {
                case RUNNING -> taskLedger.startStep(step.stepId());
                case DONE -> taskLedger.completeStep(step.stepId());
                case FAILED -> taskLedger.failStep(step.stepId(), step.detail());
                case PENDING, SKIPPED -> taskLedger.transitionStep(step.stepId(), step.status(), step.detail());
            }
        }
    }

    private boolean acceptEvidenceOrigin(ToolResultObserved observed) {
        if (observed.originSequence() <= 0) return true;
        String key = evidenceOriginKey(observed.agentId(), observed.stepId());
        EvidenceOrigin active = activeEvidenceOrigins.get(key);
        if (active == null) {
            activeEvidenceOrigins.put(key, new EvidenceOrigin(
                    observed.originSequence(), observed.contextEpoch()));
            return true;
        }
        return observed.originSequence() == active.originSequence()
                && (observed.contextEpoch() == 0
                || active.contextEpoch() == 0
                || observed.contextEpoch() == active.contextEpoch());
    }

    private static String evidenceOriginKey(String agentId, String stepId) {
        return (agentId == null ? "" : agentId) + '\u0000' + (stepId == null ? "" : stepId);
    }

    /** 开始一个明确任务。切换 taskId 时清理上一个任务的运行投影。 */
    public synchronized void beginTask(String nextTaskId) {
        String normalized = nextTaskId == null ? "" : nextTaskId.trim();
        if (normalized.isBlank()) return;
        if (!taskId.equals(normalized) && (!taskId.isBlank() || hasProjection())) {
            clearProjection();
        }
        taskId = normalized;
        taskEnded = false;
    }

    /** 标记任务结束；投影保留到下一个任务开始，供最终答复和审计读取。 */
    public synchronized void endTask(String completedTaskId) {
        String normalized = completedTaskId == null ? "" : completedTaskId.trim();
        if (!normalized.isBlank() && normalized.equals(taskId)) {
            taskEnded = true;
        }
    }

    /** 按角色和预算生成 Prompt 视图。 */
    public synchronized String render(SessionView view, int tokenBudget) {
        return renderForPrompt(view, tokenBudget);
    }

    /** 返回不可变运行投影，不包含工具原文。 */
    public synchronized SessionSnapshot snapshot() {
        List<EvidenceSnapshot> evidence = recentToolResults.stream()
                .map(item -> new EvidenceSnapshot(item.toolName, item.kind, item.importance,
                        item.reference, item.agentId, item.stepId, item.occurrences,
                        item.originSequence, item.contextEpoch, item.artifact))
                .toList();
        return new SessionSnapshot(Map.copyOf(taskState), List.copyOf(evidence),
                List.copyOf(modifiedFiles), List.copyOf(attemptDigests.values()),
                taskLedger.render(), localSequence, taskId, taskEnded,
                volatileFacts.stream().map(KeyFact::snapshot).toList(),
                List.copyOf(protectedConstraints.values()));
    }

    // ─────────────────────────────────────────────────────────
    // recentToolResults
    // ─────────────────────────────────────────────────────────

    /**
     * 记录一次工具调用结果。内部按重要性、可再生性和 Token 预算合并或裁剪。
     *
     * <p>注意：传入的 {@code result} 已经过 {@code ToolResultSizeManager} 处理（截断 /
     * 落盘），所以这里存的就是 LLM 在 conversationHistory 里看到的同一份内容——保证
     * "工具证据" 和 "对话历史" 内容一致。renderForPrompt 时再按
     * {@link #TOOL_RESULT_RENDER_CHARS} 二次截断，让 system prompt 段不致太长。
     *
     * @param toolName  工具名（read_file / execute_command / mcp__xxx 等）
     * @param argsJson  调用参数 JSON（用于 LLM 识别 "刚刚读的是哪个文件"）
     * @param result    工具返回（已被 ToolResultSizeManager 处理过的版本）
     */
    public synchronized void recordToolResult(String toolName, String argsJson, String result) {
        recordToolResult(toolName, argsJson, result, List.of());
    }

    public synchronized void recordToolResult(String toolName, String argsJson, String result,
                                              List<ToolSideChannel> sideChannels) {
        recordToolResult(toolName, argsJson, result, sideChannels, "");
    }

    /**
     * @param scope 产生该证据的执行范围（Multi-Agent 下为步骤 id）；单 Agent 路径传空串
     */
    public synchronized void recordToolResult(String toolName, String argsJson, String result,
                                              List<ToolSideChannel> sideChannels, String scope) {
        accept(new ToolResultObserved(toolName, argsJson, result, sideChannels,
                "", scope, 0));
    }

    private void recordToolResultInternal(String toolName, String argsJson, String result,
                                          List<ToolSideChannel> sideChannels, String agentId, String stepId,
                                          long originSequence, long contextEpoch, long sequence) {
        if (toolName == null || result == null) return;
        String safeArgs = argsJson == null ? "" : argsJson;
        EvidenceKind kind = classifyEvidence(toolName, result);
        int importance = baselineImportance(kind, toolName, result);
        String reference = evidenceReference(toolName, safeArgs, result);
        String normalizedResult = normalizeEvidenceResult(kind, toolName, safeArgs, result, reference);
        ToolResultArtifact artifact = sideChannels == null ? null : sideChannels.stream()
                .filter(ToolResultArtifact.class::isInstance)
                .map(ToolResultArtifact.class::cast)
                .findFirst().orElse(null);
        ToolEvidence incoming = new ToolEvidence(toolName, safeArgs, normalizedResult, Instant.now(),
                agentId, stepId, kind, importance, reference, 1, sequence, artifact,
                originSequence, contextEpoch);
        mergeOrAppend(incoming);
        if (kind == EvidenceKind.FAILURE) {
            attemptDigests.put(reference, new AttemptDigestSnapshot(
                    reference, normalizedResult, agentId, stepId, sequence));
            while (attemptDigests.size() > maxVolatileFacts) {
                attemptDigests.remove(attemptDigests.keySet().iterator().next());
            }
        }
        if (kind == EvidenceKind.CRITICAL
                && ("write_file".equals(toolName) || "edit_file".equals(toolName))) {
            String path = extractPath(safeArgs);
            if (!path.isBlank()) modifiedFiles.add(path);
        }
        evictToolResultsIfNeeded();
        recordRagEvidenceIfPresent(toolName, safeArgs, result, sideChannels);
    }

    private void mergeOrAppend(ToolEvidence incoming) {
        for (int i = recentToolResults.size() - 1; i >= 0; i--) {
            ToolEvidence existing = recentToolResults.get(i);
            if (!existing.agentId.equals(incoming.agentId)
                    || !existing.stepId.equals(incoming.stepId)
                    || existing.originSequence != incoming.originSequence
                    || !existing.toolName.equals(incoming.toolName)
                    || !existing.argsJson.equals(incoming.argsJson)) {
                continue;
            }
            int occurrences = existing.occurrences + 1;
            EvidenceKind mergedKind = occurrences >= 3 && incoming.kind != EvidenceKind.CRITICAL
                    ? EvidenceKind.MILESTONE : incoming.kind;
            String mergedResult = occurrences >= 3
                    ? "已合并 " + occurrences + " 次同类调用：" + truncate(incoming.result, 500)
                    : incoming.result;
            recentToolResults.set(i, new ToolEvidence(incoming.toolName, incoming.argsJson,
                    mergedResult, incoming.capturedAt, incoming.agentId, incoming.stepId, mergedKind,
                    Math.max(existing.importance, baselineImportance(mergedKind, incoming.toolName, mergedResult)),
                    incoming.reference, occurrences, incoming.sequence,
                    incoming.artifact == null ? existing.artifact : incoming.artifact,
                    incoming.originSequence, incoming.contextEpoch));
            return;
        }
        recentToolResults.addLast(incoming);
    }

    /**
     * 工具证据淘汰：副作用证据（改文件系统 / 项目状态，不可再生）优先保留，淘汰时先牺牲
     * 只读证据（read_file / search 等可再生）。这样一串只读操作不会把关键的 write_file
     * 副作用挤出工作记忆——后续步骤 / 轮次仍能看到"本会话改过哪些文件"。总量仍受
     * {@link #maxToolResults} 约束；全是副作用且超限时才淘汰最旧副作用。
     */
    private void evictToolResultsIfNeeded() {
        while (recentToolResults.size() > maxToolResults || evidenceTokens() > evidenceTokenBudget) {
            int victim = selectLowestImportanceVictim();
            if (victim >= 0) {
                recentToolResults.remove(victim);
                continue;
            }
            if (compactCriticalEvidence()) continue;
            foldOldestCriticalEvidence();
        }
    }

    private int evidenceTokens() {
        int total = 0;
        for (ToolEvidence evidence : recentToolResults) {
            total += MemoryEntry.estimateTokens(evidence.argsJson)
                    + MemoryEntry.estimateTokens(evidence.result) + 12;
        }
        return total;
    }

    private int selectLowestImportanceVictim() {
        int lowest = recentToolResults.stream()
                .filter(item -> item.kind != EvidenceKind.CRITICAL)
                .mapToInt(item -> item.importance)
                .min().orElse(Integer.MAX_VALUE);
        if (lowest == Integer.MAX_VALUE) return -1;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ToolEvidence item : recentToolResults) {
            if (item.kind != EvidenceKind.CRITICAL && item.importance == lowest) {
                counts.merge(item.stepId, 1, Integer::sum);
            }
        }
        String busiestScope = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
        for (int i = 0; i < recentToolResults.size(); i++) {
            ToolEvidence item = recentToolResults.get(i);
            if (item.kind != EvidenceKind.CRITICAL && item.importance == lowest
                    && item.stepId.equals(busiestScope)) return i;
        }
        return -1;
    }

    private boolean compactCriticalEvidence() {
        boolean changed = false;
        for (int i = 0; i < recentToolResults.size(); i++) {
            ToolEvidence evidence = recentToolResults.get(i);
            if (evidence.kind != EvidenceKind.CRITICAL || evidence.result.length() <= 500) continue;
            recentToolResults.set(i, evidence.withResult(truncate(evidence.result, 500)));
            changed = true;
        }
        return changed;
    }

    private void foldOldestCriticalEvidence() {
        if (recentToolResults.isEmpty()) return;
        ToolEvidence evidence = recentToolResults.removeFirst();
        addKeyEvent("关键工具证据已折叠: " + evidence.toolName + "，引用: " + evidence.reference,
                evidence.importance, evidence.agentId, evidence.stepId, evidence.sequence);
    }

    private static EvidenceKind classifyEvidence(String toolName, String result) {
        String normalized = result == null ? "" : result.toLowerCase(Locale.ROOT);
        if (normalized.contains("negativefact") || "write_file".equals(toolName)
                || "edit_file".equals(toolName)
                || "create_project".equals(toolName) || isPassingTestResult(toolName, normalized)) {
            return EvidenceKind.CRITICAL;
        }
        if (looksLikeFailure(normalized)) return EvidenceKind.FAILURE;
        if ("execute_command".equals(toolName)) return EvidenceKind.MILESTONE;
        if ("read_file".equals(toolName) || "list_dir".equals(toolName)
                || "search_code".equals(toolName) || "grep_code".equals(toolName)) {
            return EvidenceKind.REGENERABLE;
        }
        return EvidenceKind.ORDINARY;
    }

    private static int baselineImportance(EvidenceKind kind, String toolName, String result) {
        return switch (kind) {
            case CRITICAL -> 95;
            case FAILURE -> 82;
            case MILESTONE -> 65;
            case ORDINARY -> 40;
            case REGENERABLE -> 18;
        };
    }

    private static boolean looksLikeFailure(String normalized) {
        return normalized.contains("toolstatus=error") || normalized.contains("execution_failed")
                || normalized.contains("exit code: 1") || normalized.contains("exitcode=1")
                || normalized.contains("build failure") || normalized.contains("失败")
                || normalized.contains("exception") || normalized.contains("error:");
    }

    private static boolean isPassingTestResult(String toolName, String normalized) {
        return "execute_command".equals(toolName)
                && (normalized.contains("build success") || normalized.contains("tests run:")
                && !normalized.contains("failures: 1") && !normalized.contains("errors: 1"));
    }

    private static String evidenceReference(String toolName, String argsJson, String result) {
        String storedPath = extractMicrocompactValue(MICROCOMPACT_STORED_PATH, result == null ? "" : result);
        if (!storedPath.isBlank()) return storedPath;
        String path = extractPath(argsJson);
        if (!path.isBlank()) return path;
        return toolName + ":" + Integer.toHexString((argsJson + "\n" + result).hashCode());
    }

    private static String normalizeEvidenceResult(EvidenceKind kind, String toolName, String argsJson,
                                                  String result, String reference) {
        return switch (kind) {
            case FAILURE -> "尝试内容: " + toolName + " " + truncate(argsJson, 180)
                    + "\n失败原因: " + truncate(result.replaceAll("\\s+", " "), 900)
                    + "\n避免重复: 未改变前置条件前不要重复同一调用"
                    + "\n引用: " + reference;
            case REGENERABLE -> "可再生成证据，摘要: "
                    + truncate(result.replaceAll("\\s+", " "), 260) + "\n引用: " + reference;
            case ORDINARY -> truncate(result, 700) + "\n引用: " + reference;
            case MILESTONE -> truncate(result, 1_000) + "\n引用: " + reference;
            case CRITICAL -> truncate(result, TOOL_RESULT_RENDER_CHARS) + "\n引用: " + reference;
        };
    }

    private void applyStateChange(String key, String value, long sequence) {
        if (key == null || key.isBlank()) return;
        long previous = stateSequences.getOrDefault(key, Long.MIN_VALUE);
        if (sequence < previous) return;
        stateSequences.put(key, sequence);
        if (value == null || value.isBlank()) taskState.remove(key);
        else taskState.put(key, value.trim());
    }

    private void addKeyEvent(String description, int importance, String agentId, String stepId, long sequence) {
        if (description == null || description.isBlank()) return;
        String normalized = description.trim();
        volatileFacts.removeIf(item -> item.description.equals(normalized));
        KeyFact fact = new KeyFact(normalized, importance, agentId, stepId, sequence);
        if (importance >= 70) volatileFacts.addFirst(fact);
        else volatileFacts.addLast(fact);
        while (volatileFacts.size() > maxVolatileFacts) volatileFacts.removeLast();
    }

    public synchronized List<ToolEvidence> getRecentToolResults() {
        return Collections.unmodifiableList(new ArrayList<>(recentToolResults));
    }

    public synchronized List<RagEvidence> getRagEvidenceMemory() {
        return Collections.unmodifiableList(new ArrayList<>(ragEvidenceMemory));
    }

    // ─────────────────────────────────────────────────────────
    // taskState
    // ─────────────────────────────────────────────────────────

    /**
     * 设置一项任务状态（覆盖同名 key）。例：
     * <ul>
     *   <li>{@code setTaskState("plan_task", "task_3 (analyzing log)")}</li>
     *   <li>{@code setTaskState("react_iteration", "12")}</li>
     *   <li>{@code setTaskState("last_error", "MCP schema missing required")}</li>
     * </ul>
     */
    public synchronized void setTaskState(String key, String value) {
        accept(new StateChanged(key, value, "", "", 0));
    }

    public synchronized Optional<String> getTaskState(String key) {
        return Optional.ofNullable(taskState.get(key));
    }

    public synchronized Map<String, String> taskStateSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(taskState));
    }

    public synchronized void clearTaskState() {
        taskState.clear();
    }

    /** 保存从用户消息中提取的硬约束；它不依赖摘要生命周期，也不与普通事件混存。 */
    public synchronized void addProtectedConstraint(String constraint) {
        if (constraint == null || constraint.isBlank()) return;
        String normalized = constraint.replace("\r\n", "\n")
                .replace('\r', '\n').trim();
        if (normalized.isBlank()) return;
        if (normalized.length() > PROTECTED_CONSTRAINT_CHARS) {
            normalized = normalized.substring(0, PROTECTED_CONSTRAINT_CHARS) + "...";
        }
        normalized = SensitiveDataRedactor.redact(normalized);
        if (normalized == null || normalized.isBlank()) return;
        protectedConstraints.remove(normalized);
        protectedConstraints.put(normalized, normalized);
        while (protectedConstraints.size() > MAX_PROTECTED_CONSTRAINTS) {
            protectedConstraints.remove(protectedConstraints.keySet().iterator().next());
        }
    }

    public synchronized void addProtectedConstraints(Collection<String> constraints) {
        if (constraints == null) return;
        constraints.forEach(this::addProtectedConstraint);
    }

    public synchronized List<String> getProtectedConstraints() {
        return List.copyOf(protectedConstraints.values());
    }

    // ─────────────────────────────────────────────────────────
    // taskLedger（计划执行进度投影）
    // ─────────────────────────────────────────────────────────

    /** 任务账本：计划执行进度的结构化投影，供 MemoryManager 门面写入、renderForPrompt 注入。 */
    public synchronized TaskLedger taskLedger() {
        return taskLedger;
    }

    // ─────────────────────────────────────────────────────────
    // volatileFacts
    // ─────────────────────────────────────────────────────────

    /**
     * 添加一条本会话临时事实。例如 "刚跑过 mvn test -Pquick"、"刚改了 LongTermMemory.java"。
     * 添加关键事件。同内容增量合并，高重要性事件优先保留。
     */
    public synchronized void addVolatileFact(String fact) {
        accept(new KeyEvent(fact, inferEventImportance(fact), "", "", 0));
    }

    private static int inferEventImportance(String fact) {
        if (fact == null) return 20;
        String normalized = fact.toLowerCase(Locale.ROOT);
        if (normalized.contains("用户确认") || normalized.contains("negativefact")
                || normalized.contains("根因") || normalized.contains("测试通过")
                || normalized.contains("已修改") || normalized.contains("写入")) return 95;
        if (normalized.contains("失败") || normalized.contains("阻塞")
                || normalized.contains("回滚") || normalized.contains("冲突")) return 82;
        return 35;
    }

    public synchronized List<String> getVolatileFacts() {
        return volatileFacts.stream().map(item -> item.description).toList();
    }

    // ─────────────────────────────────────────────────────────
    // 派生视图：注入 system prompt
    // ─────────────────────────────────────────────────────────

    /**
     * 渲染为 system prompt 一段 Markdown。空内容返回空串（PromptAssembler 会跳过空段）。
     */
    public synchronized String renderForPrompt() {
        return renderForPrompt(SessionView.FULL, DEFAULT_EVIDENCE_TOKEN_BUDGET);
    }

    /**
     * 按 Agent 角色渲染工作记忆派生视图，避免 Multi-Agent 三角色共享同一份运行态证据。
     */
    public synchronized String renderForPrompt(SessionView view) {
        return renderForPrompt(view, DEFAULT_EVIDENCE_TOKEN_BUDGET);
    }

    public synchronized String renderForPrompt(SessionView view, int tokenBudget) {
        SessionView effectiveView = view == null ? SessionView.FULL : view;
        int effectiveBudget = Math.max(256, tokenBudget);
        StringBuilder sb = new StringBuilder();
        String ledger = taskLedger.render();
        if (!ledger.isEmpty()) {
            appendBudgeted(sb, ledger, effectiveBudget);
        }
        if (!taskState.isEmpty()) {
            StringBuilder section = new StringBuilder("### 当前任务状态\n\n");
            if (!taskId.isBlank()) {
                section.append("- task_id: ").append(taskId)
                        .append(taskEnded ? " (已结束)" : " (进行中)").append('\n');
            }
            for (Map.Entry<String, String> e : taskState.entrySet()) {
                section.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (!protectedConstraints.isEmpty()) {
            StringBuilder section = new StringBuilder("### 用户硬约束（压缩保护）\n\n");
            protectedConstraints.values().forEach(value -> section.append("- ").append(value).append('\n'));
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (!modifiedFiles.isEmpty()) {
            StringBuilder section = new StringBuilder("### 已修改文件集合\n\n");
            for (String file : modifiedFiles) section.append("- `").append(file).append("`\n");
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        List<ToolEvidence> rankedToolEvidence = new ArrayList<>();
        if (shouldRenderToolEvidence(effectiveView)) {
            rankedToolEvidence.addAll(recentToolResults);
            rankedToolEvidence.sort((left, right) -> {
                int byImportance = Integer.compare(right.importance, left.importance);
                return byImportance != 0 ? byImportance : Long.compare(right.sequence, left.sequence);
            });
            appendToolEvidence(sb, rankedToolEvidence.stream()
                    .filter(evidence -> evidence.importance >= 70)
                    .toList(), "### 关键工具证据（精确实体来源）", effectiveBudget);
        }
        if (shouldRenderToolEvidence(effectiveView) && !attemptDigests.isEmpty()) {
            StringBuilder section = new StringBuilder("### 已尝试但失败的方案\n\n");
            for (AttemptDigestSnapshot attempt : attemptDigests.values()) {
                section.append("- ").append(truncate(attempt.digest(), 700).replace("\n", "; ")).append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (shouldRenderVolatileFacts(effectiveView) && !volatileFacts.isEmpty()) {
            StringBuilder section = new StringBuilder("### 本会话已发生的关键事件（避免重复执行）\n\n");
            List<KeyFact> ranked = new ArrayList<>(volatileFacts);
            ranked.sort((left, right) -> {
                int byImportance = Integer.compare(right.importance, left.importance);
                return byImportance != 0 ? byImportance : Long.compare(right.sequence, left.sequence);
            });
            for (KeyFact fact : ranked) {
                section.append("- ").append(fact.description);
                appendOrigin(section, fact.agentId, fact.stepId);
                section.append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (shouldRenderToolEvidence(effectiveView) && !ragEvidenceMemory.isEmpty()) {
            StringBuilder section = new StringBuilder("### RAG 证据记忆（绑定 SymbolVersion）\n\n");
            List<RagEvidence> reversed = new ArrayList<>(ragEvidenceMemory);
            Collections.reverse(reversed);
            for (RagEvidence evidence : reversed) {
                section.append("- [").append(evidence.chunkType()).append(':').append(evidence.symbolName()).append("] ")
                        .append(evidence.filePath())
                        .append(" | symbolVersion=").append(evidence.symbolVersion())
                        .append(" | indexEpoch=").append(evidence.indexEpoch())
                        .append(" | classpathEpoch=").append(evidence.classpathEpoch());
                if (!evidence.query().isBlank()) {
                    section.append(" | query=").append(evidence.query());
                }
                section.append('\n');
            }
            appendBudgeted(sb, section.toString(), effectiveBudget);
        }
        if (!rankedToolEvidence.isEmpty()) {
            appendToolEvidence(sb, rankedToolEvidence.stream()
                    .filter(evidence -> evidence.importance < 70)
                    .toList(), "### 普通工具证据（精确实体来源）", effectiveBudget);
        }
        return sb.toString().trim();
    }

    private static void appendToolEvidence(StringBuilder target, List<ToolEvidence> evidence,
                                           String heading, int tokenBudget) {
        if (evidence.isEmpty() || !appendBudgeted(target, heading, tokenBudget)) return;
        for (ToolEvidence item : evidence) {
            StringBuilder rendered = new StringBuilder("- **").append(item.toolName).append("**")
                    .append(" [").append(item.kind).append('/').append(item.importance).append("]");
            appendOrigin(rendered, item.agentId, item.stepId);
            if (item.originSequence > 0) {
                rendered.append(" [origin=").append(item.originSequence).append(']');
            }
            if (item.contextEpoch > 0) {
                rendered.append(" [context_epoch=").append(item.contextEpoch).append(']');
            }
            if (!item.argsJson.isBlank()) {
                rendered.append(" args: `").append(truncate(item.argsJson, 120)).append('`');
            }
            rendered.append('\n');
            rendered.append("  ```\n  ").append(truncate(item.result, TOOL_RESULT_RENDER_CHARS)
                    .replace("\n", "\n  ")).append("\n  ```\n");
            if (!appendBudgeted(target, rendered.toString(), tokenBudget)) break;
        }
    }

    private static boolean appendBudgeted(StringBuilder target, String section, int tokenBudget) {
        if (section == null || section.isBlank()) return true;
        int used = MemoryEntry.estimateTokens(target.toString());
        int remaining = tokenBudget - used;
        if (remaining <= 0) return false;
        String normalized = section.trim();
        String addition = target.isEmpty() ? normalized : "\n\n" + normalized;
        if (MemoryEntry.estimateTokens(addition) <= remaining) {
            target.append(addition);
            return true;
        }
        String prefix = target.isEmpty() ? "" : "\n\n";
        String marker = "\n[本区其余内容已按 Token 预算压缩]";
        int contentBudget = remaining
                - MemoryEntry.estimateTokens(prefix)
                - MemoryEntry.estimateTokens(marker);
        String fitted = fitWithinTokens(normalized, contentBudget);
        if (fitted.isBlank()) return false;
        target.append(prefix).append(fitted).append(marker);
        return false;
    }

    private static String fitWithinTokens(String value, int tokenBudget) {
        if (value == null || value.isBlank() || tokenBudget <= 0) return "";
        if (MemoryEntry.estimateTokens(value) <= tokenBudget) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (MemoryEntry.estimateTokens(value.substring(0, mid)) <= tokenBudget) low = mid;
            else high = mid - 1;
        }
        return value.substring(0, low).trim();
    }

    private static void appendOrigin(StringBuilder target, String agentId, String stepId) {
        if ((agentId == null || agentId.isBlank()) && (stepId == null || stepId.isBlank())) return;
        target.append(" [来自 ");
        if (agentId != null && !agentId.isBlank()) target.append(agentId);
        if (stepId != null && !stepId.isBlank()) {
            if (agentId != null && !agentId.isBlank()) target.append('/');
            target.append(stepId);
        }
        target.append(']');
    }

    /**
     * 压缩后恢复 messages 的结构化短上下文。
     *
     * <p>这里不复用完整 {@link #renderForPrompt()}，避免把大段工具输出再次写回
     * conversationHistory。恢复段只保留可定位实体：文件路径、未完成子任务、短工具引用和 RAG epoch。
     */
    public synchronized String renderForPostCompactRestore() {
        return renderForPostCompactRestore(SessionView.FULL);
    }

    public synchronized String renderForPostCompactRestore(SessionView view) {
        SessionView effectiveView = view == null ? SessionView.FULL : view;
        StringBuilder sb = new StringBuilder();
        if (shouldRenderToolEvidence(effectiveView)) {
            appendRecentFileSection(sb);
        }
        appendProtectedConstraintSection(sb);
        appendOpenTaskSection(sb);
        if (shouldRenderToolEvidence(effectiveView)) {
            appendKeyToolReferenceSection(sb);
            appendRagEpochSection(sb);
        }
        return sb.toString().trim();
    }

    private void appendRecentFileSection(StringBuilder sb) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        for (ToolEvidence ev : recentToolResults) {
            if (!"read_file".equals(ev.toolName) && !"write_file".equals(ev.toolName)
                    && !"edit_file".equals(ev.toolName)) {
                continue;
            }
            String path = extractPath(ev.argsJson);
            if (path.isBlank()) {
                continue;
            }
            files.remove(path);
            files.put(path, ev.toolName);
        }
        if (files.isEmpty()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### 最近读写文件\n\n");
        List<Map.Entry<String, String>> entries = new ArrayList<>(files.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, String> entry : entries) {
            sb.append("- ").append(entry.getValue()).append(": `").append(entry.getKey()).append("`\n");
        }
    }

    private void appendOpenTaskSection(StringBuilder sb) {
        String ledger = taskLedger.renderPostCompactRestore();
        if (taskState.isEmpty() && ledger.isBlank()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### 未完成子任务状态\n\n");
        if (!ledger.isBlank()) {
            sb.append(ledger).append('\n');
        }
        for (Map.Entry<String, String> entry : taskState.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
    }

    private void appendKeyToolReferenceSection(StringBuilder sb) {
        if (recentToolResults.isEmpty()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### 关键工具结果引用\n\n");
        List<ToolEvidence> reversed = new ArrayList<>(recentToolResults);
        Collections.reverse(reversed);
        LinkedHashSet<String> renderedMicrocompactReferences = new LinkedHashSet<>();
        int count = 0;
        for (ToolEvidence ev : reversed) {
            if (count >= 5) {
                break;
            }
            String microcompactKey = microcompactReferenceKey(ev.result);
            if (!microcompactKey.isBlank() && !renderedMicrocompactReferences.add(microcompactKey)) {
                continue;
            }
            sb.append("- **").append(ev.toolName).append("**");
            if (!ev.argsJson.isBlank()) {
                sb.append(" args: `").append(truncate(ev.argsJson, 120)).append('`');
            }
            String result = renderToolResultReference(ev.result);
            if (!result.isBlank()) {
                sb.append(" -> ").append(result);
            }
            sb.append('\n');
            count++;
        }
    }

    private static String renderToolResultReference(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        if (!result.contains("<microcompact_boundary>")) {
            return truncate(result, 240).replaceAll("\\s+", " ").trim();
        }
        String toolCallId = extractMicrocompactValue(MICROCOMPACT_TOOL_CALL_ID, result);
        String originalChars = extractMicrocompactValue(MICROCOMPACT_ORIGINAL_CHARS, result);
        String storedPath = extractMicrocompactValue(MICROCOMPACT_STORED_PATH, result);
        StringBuilder sb = new StringBuilder("microcompact tool_result");
        if (!toolCallId.isBlank()) {
            sb.append(" toolCallId=").append(toolCallId);
        }
        if (!originalChars.isBlank()) {
            sb.append(" originalChars=").append(originalChars);
        }
        if (!storedPath.isBlank()) {
            sb.append(" storedPath=").append(storedPath);
        }
        return sb.toString();
    }

    private static String microcompactReferenceKey(String result) {
        if (result == null || !result.contains("<microcompact_boundary>")) {
            return "";
        }
        String storedPath = extractMicrocompactValue(MICROCOMPACT_STORED_PATH, result);
        if (!storedPath.isBlank()) {
            return "storedPath=" + storedPath;
        }
        String toolCallId = extractMicrocompactValue(MICROCOMPACT_TOOL_CALL_ID, result);
        return toolCallId.isBlank() ? "" : "toolCallId=" + toolCallId;
    }

    private static String extractMicrocompactValue(Pattern pattern, String result) {
        Matcher matcher = pattern.matcher(result);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private void appendRagEpochSection(StringBuilder sb) {
        if (ragEvidenceMemory.isEmpty()) {
            return;
        }
        appendSectionBreak(sb);
        sb.append("### RAG 证据 epoch\n\n");
        List<RagEvidence> reversed = new ArrayList<>(ragEvidenceMemory);
        Collections.reverse(reversed);
        for (RagEvidence evidence : reversed) {
            sb.append("- [").append(evidence.chunkType()).append(':').append(evidence.symbolName()).append("] ")
                    .append(evidence.filePath())
                    .append(" | symbolVersion=").append(evidence.symbolVersion())
                    .append(" | indexEpoch=").append(evidence.indexEpoch())
                    .append(" | classpathEpoch=").append(evidence.classpathEpoch());
            if (!evidence.query().isBlank()) {
                sb.append(" | query=").append(evidence.query());
            }
            sb.append('\n');
        }
    }

    private static void appendSectionBreak(StringBuilder sb) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
    }

    private static boolean shouldRenderVolatileFacts(SessionView view) {
        return view == SessionView.FULL || view == SessionView.PLANNER || view == SessionView.WORKER;
    }

    private void appendProtectedConstraintSection(StringBuilder sb) {
        if (protectedConstraints.isEmpty()) return;
        appendSectionBreak(sb);
        sb.append("### 用户硬约束\n\n");
        for (String constraint : protectedConstraints.values()) {
            sb.append("- ").append(constraint).append('\n');
        }
    }

    private static boolean shouldRenderToolEvidence(SessionView view) {
        return view == SessionView.FULL || view == SessionView.WORKER || view == SessionView.REVIEWER;
    }

    /** 状态摘要给 /memory 命令显示。 */
    public synchronized String getStatusSummary() {
        return String.format(Locale.ROOT,
                "会话记忆: %d 工具证据 / %d RAG证据 / %d 工作状态 / %d 关键事件 / 预算 %d tokens",
                recentToolResults.size(), ragEvidenceMemory.size(), taskState.size(), volatileFacts.size(),
                evidenceTokenBudget);
    }

    /** 获取工具结果数量（供维护服务使用）。 */
    public synchronized int getToolResultsCount() {
        return recentToolResults.size();
    }

    /** 获取临时事实数量（供维护服务使用）。 */
    public synchronized int getVolatileFactsCount() {
        return volatileFacts.size();
    }

    /** 获取 RAG 证据数量（供维护服务使用）。 */
    public synchronized int getRagEvidenceCount() {
        return ragEvidenceMemory.size();
    }

    /**
     * 清理失效的 RAG 证据（供维护服务调用）。
     *
     * @param symbolInvalidation 符号失效记录
     * @return 清理的证据数量
     */
    public synchronized int pruneInvalidEvidence(com.devcli.rag.SymbolInvalidation symbolInvalidation) {
        if (symbolInvalidation == null) {
            return 0;
        }
        String oldEpoch = symbolInvalidation.oldIndexEpoch();
        if (oldEpoch == null || oldEpoch.isBlank()) {
            return 0;
        }
        int sizeBefore = ragEvidenceMemory.size();
        ragEvidenceMemory.removeIf(evidence -> {
            String evidenceEpoch = evidence.indexEpoch();
            return evidenceEpoch != null && evidenceEpoch.equals(oldEpoch);
        });
        int removed = sizeBefore - ragEvidenceMemory.size();
        if (removed > 0) {
            log.info("pruneInvalidEvidence: removed {} stale RAG evidence from old index epoch {}",
                removed, oldEpoch);
        }
        return removed;
    }

    /** 清空当前任务的会话记忆。 */
    public synchronized void clear() {
        clearProjection();
        taskId = "";
        taskEnded = false;
    }

    private void clearProjection() {
        recentToolResults.clear();
        ragEvidenceMemory.clear();
        volatileFacts.clear();
        taskState.clear();
        stateSequences.clear();
        stepSequences.clear();
        activeEvidenceOrigins.clear();
        processedEventIds.clear();
        modifiedFiles.clear();
        attemptDigests.clear();
        protectedConstraints.clear();
        localSequence = 0;
        planSequence = Long.MIN_VALUE;
        taskLedger.clear();
    }

    private boolean hasProjection() {
        return !recentToolResults.isEmpty()
                || !ragEvidenceMemory.isEmpty()
                || !volatileFacts.isEmpty()
                || !taskState.isEmpty()
                || !modifiedFiles.isEmpty()
                || !attemptDigests.isEmpty()
                || !taskLedger.isEmpty();
    }

    private void recordRagEvidenceIfPresent(String toolName, String argsJson, String result,
                                            List<ToolSideChannel> sideChannels) {
        if (!"search_code".equals(toolName) || result == null || result.isBlank()) {
            return;
        }
        boolean typedPayloadPresent = false;
        if (sideChannels != null) {
            for (ToolSideChannel sideChannel : sideChannels) {
                if (sideChannel instanceof RagEvidenceSideChannel ragEvidence) {
                    typedPayloadPresent = true;
                    recordRagEvidencePayload(ragEvidence.payload());
                }
            }
        }
        if (typedPayloadPresent) {
            return;
        }
        RagEvidencePayload.Payload payload = RagEvidencePayload.extract(result);
        if (!payload.evidence().isEmpty() || !payload.negativeFacts().isEmpty()) {
            recordRagEvidencePayload(payload);
            return;
        }
        recordLegacyRagEvidenceIfPresent(argsJson, result);
    }

    private void recordRagEvidencePayload(RagEvidencePayload.Payload payload) {
        if (payload == null) {
            return;
        }
        for (RagEvidencePayload.Evidence evidence : payload.evidence()) {
            addRagEvidence(new RagEvidence(
                    evidence.filePath(),
                    evidence.symbolName(),
                    evidence.chunkType(),
                    evidence.symbolVersion(),
                    evidence.classpathEpoch(),
                    evidence.indexEpoch(),
                    evidence.query(),
                    evidence.similarity(),
                    Instant.now()));
        }
        for (RagEvidencePayload.NegativeFact negativeFact : payload.negativeFacts()) {
            String rendered = negativeFact.renderForMemory();
            addVolatileFact("NegativeFact（负向事实）: " + rendered);
            pruneEvidenceForOldSymbolVersion(negativeFact.oldSymbolVersion());
        }
    }

    private void recordLegacyRagEvidenceIfPresent(String argsJson, String result) {
        String query = extractQuery(argsJson);
        String[] lines = result.split("\\R");
        PendingRagEvidence pending = null;
        for (String line : lines) {
            Matcher headerMatcher = SEARCH_RESULT_HEADER.matcher(line);
            if (headerMatcher.matches()) {
                pending = new PendingRagEvidence(
                        headerMatcher.group(1).trim(),
                        headerMatcher.group(2).trim(),
                        safeParseDouble(headerMatcher.group(3).trim()),
                        headerMatcher.group(4).trim());
                continue;
            }
            Matcher evidenceMatcher = SEARCH_RESULT_EVIDENCE.matcher(line);
            if (pending != null && evidenceMatcher.matches()) {
                addRagEvidence(new RagEvidence(
                        pending.filePath,
                        pending.symbolName,
                        pending.chunkType,
                        evidenceMatcher.group(1).trim(),
                        evidenceMatcher.group(3).trim(),
                        evidenceMatcher.group(2) == null ? "none" : evidenceMatcher.group(2).trim(),
                        query,
                        pending.similarity,
                        Instant.now()));
                pending = null;
                continue;
            }
            Matcher negativeFactMatcher = SEARCH_RESULT_NEGATIVE_FACT.matcher(line);
            if (negativeFactMatcher.matches()) {
                String negativeFact = negativeFactMatcher.group(1).trim();
                addVolatileFact("NegativeFact（负向事实）: " + negativeFact);
                pruneEvidenceForNegativeFact(negativeFact);
            }
        }
    }

    /**
     * negativeFact 表明某个旧 symbolVersion 已失效；立即清理引用该版本的 RAG 证据，
     * 避免过期证据继续留在 system prompt 里误导模型（不再依赖外部维护服务触发）。
     * 行内没有结构化 oldSymbolVersion= 字段时不做任何清理。
     */
    private void pruneEvidenceForNegativeFact(String negativeFact) {
        if (negativeFact == null || negativeFact.isBlank()) {
            return;
        }
        Matcher matcher = NEGATIVE_FACT_OLD_SYMBOL_VERSION.matcher(negativeFact);
        if (!matcher.find()) {
            return;
        }
        String oldSymbolVersion = matcher.group(1).trim();
        pruneEvidenceForOldSymbolVersion(oldSymbolVersion);
    }

    private void pruneEvidenceForOldSymbolVersion(String oldSymbolVersion) {
        if (oldSymbolVersion == null) {
            return;
        }
        String version = oldSymbolVersion.trim();
        if (version.isEmpty() || "none".equals(version)) {
            return;
        }
        int sizeBefore = ragEvidenceMemory.size();
        ragEvidenceMemory.removeIf(evidence -> version.equals(evidence.symbolVersion()));
        int removed = sizeBefore - ragEvidenceMemory.size();
        if (removed > 0) {
            log.info("pruneEvidenceForNegativeFact: removed {} stale RAG evidence with symbolVersion={}",
                    removed, version);
        }
    }

    private void addRagEvidence(RagEvidence evidence) {
        ragEvidenceMemory.removeIf(existing ->
                existing.filePath().equals(evidence.filePath())
                        && existing.symbolName().equals(evidence.symbolName())
                        && existing.symbolVersion().equals(evidence.symbolVersion()));
        ragEvidenceMemory.addLast(evidence);
        while (ragEvidenceMemory.size() > maxRagEvidence) {
            ragEvidenceMemory.removeFirst();
        }
    }

    private static String extractQuery(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"query\"\\s*:\\s*\"([^\"]+)\"").matcher(argsJson);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String extractPath(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        Matcher matcher = PATH_ARG.matcher(argsJson);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static double safeParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "...(truncated)";
    }

    /** 单条工具调用证据。 */
    public static final class ToolEvidence {
        public final String toolName;
        public final String argsJson;
        public final String result;
        public final Instant capturedAt;
        /**
         * 产生该证据的执行范围（Multi-Agent 下为步骤 id）。单 Agent / Plan 路径为空串，
         * 表示"无步骤概念"，渲染时不加出处标签。
         */
        public final String agentId;
        public final String stepId;
        /** @deprecated 使用 {@link #stepId}。 */
        @Deprecated
        public final String scope;
        public final EvidenceKind kind;
        public final int importance;
        public final String reference;
        public final int occurrences;
        public final long sequence;
        public final ToolResultArtifact artifact;
        public final long originSequence;
        public final long contextEpoch;

        ToolEvidence(String toolName, String argsJson, String result, Instant capturedAt,
                     String agentId, String stepId, EvidenceKind kind, int importance,
                     String reference, int occurrences, long sequence,
                     ToolResultArtifact artifact) {
            this(toolName, argsJson, result, capturedAt, agentId, stepId, kind, importance,
                    reference, occurrences, sequence, artifact, 0, 0);
        }

        ToolEvidence(String toolName, String argsJson, String result, Instant capturedAt,
                     String agentId, String stepId, EvidenceKind kind, int importance,
                     String reference, int occurrences, long sequence,
                     ToolResultArtifact artifact, long originSequence, long contextEpoch) {
            this.toolName = toolName;
            this.argsJson = argsJson;
            this.result = result;
            this.capturedAt = capturedAt;
            this.agentId = agentId == null ? "" : agentId;
            this.stepId = stepId == null ? "" : stepId;
            this.scope = this.stepId;
            this.kind = kind == null ? EvidenceKind.ORDINARY : kind;
            this.importance = Math.max(0, Math.min(100, importance));
            this.reference = reference == null ? "" : reference;
            this.occurrences = Math.max(1, occurrences);
            this.sequence = sequence;
            this.artifact = artifact;
            this.originSequence = Math.max(0, originSequence);
            this.contextEpoch = Math.max(0, contextEpoch);
        }

        ToolEvidence withResult(String compactedResult) {
            return new ToolEvidence(toolName, argsJson, compactedResult, capturedAt, agentId, stepId,
                    kind, importance, reference, occurrences, sequence, artifact,
                    originSequence, contextEpoch);
        }
    }

    private static final class KeyFact {
        private final String description;
        private final int importance;
        private final String agentId;
        private final String stepId;
        private final long sequence;

        private KeyFact(String description, int importance, String agentId, String stepId, long sequence) {
            this.description = description;
            this.importance = Math.max(0, Math.min(100, importance));
            this.agentId = agentId == null ? "" : agentId;
            this.stepId = stepId == null ? "" : stepId;
            this.sequence = sequence;
        }

        private KeyEventSnapshot snapshot() {
            return new KeyEventSnapshot(description, importance, agentId, stepId, sequence);
        }
    }

    public enum EvidenceKind {
        CRITICAL,
        FAILURE,
        MILESTONE,
        ORDINARY,
        REGENERABLE
    }

    public sealed interface SessionEvent permits StateChanged, ToolResultObserved, KeyEvent,
            EvidenceScopeStarted,
            PlanChanged, StepChanged {
        String agentId();
        String stepId();
        long sequence();
    }

    public record StateChanged(String key, String value, String agentId, String stepId,
                               long sequence) implements SessionEvent {}

    public record ToolResultObserved(String toolName, String argsJson, String result,
                                     List<ToolSideChannel> sideChannels, String agentId,
                                     String stepId, long originSequence, long contextEpoch,
                                     long sequence) implements SessionEvent {
        public ToolResultObserved {
            sideChannels = sideChannels == null ? List.of() : List.copyOf(sideChannels);
            originSequence = Math.max(0, originSequence);
            contextEpoch = Math.max(0, contextEpoch);
        }

        public ToolResultObserved(String toolName, String argsJson, String result,
                                  List<ToolSideChannel> sideChannels, String agentId,
                                  String stepId, long sequence) {
            this(toolName, argsJson, result, sideChannels, agentId, stepId, 0, 0, sequence);
        }
    }

    public record EvidenceScopeStarted(String agentId, String stepId, long originSequence,
                                       long contextEpoch, long sequence) implements SessionEvent {
        public EvidenceScopeStarted {
            agentId = agentId == null ? "" : agentId;
            stepId = stepId == null ? "" : stepId;
            originSequence = Math.max(0, originSequence);
            contextEpoch = Math.max(0, contextEpoch);
        }
    }

    public record KeyEvent(String description, int importance, String agentId, String stepId,
                           long sequence) implements SessionEvent {}

    public record PlanChanged(String planId, String goal, Map<String, String> steps,
                              String agentId, String stepId, long sequence) implements SessionEvent {
        public PlanChanged {
            steps = steps == null ? Map.of() : Map.copyOf(steps);
        }
    }

    public record StepChanged(String stepId, TaskLedger.StepStatus status, String detail,
                              String agentId, long sequence) implements SessionEvent {
        public StepChanged {
            status = status == null ? TaskLedger.StepStatus.PENDING : status;
            detail = detail == null ? "" : detail;
        }
    }

    public record EvidenceSnapshot(String toolName, EvidenceKind kind, int importance,
                                   String reference, String agentId, String stepId, int occurrences,
                                   long originSequence, long contextEpoch,
                                   ToolResultArtifact artifact) {
        public EvidenceSnapshot(String toolName, EvidenceKind kind, int importance,
                                String reference, String agentId, String stepId, int occurrences,
                                ToolResultArtifact artifact) {
            this(toolName, kind, importance, reference, agentId, stepId, occurrences,
                    0, 0, artifact);
        }
    }

    private record EvidenceOrigin(long originSequence, long contextEpoch) {
    }

    public record KeyEventSnapshot(String description, int importance, String agentId,
                                   String stepId, long sequence) {}

    public record AttemptDigestSnapshot(String reference, String digest,
                                        String agentId, String stepId, long sequence) {
        public AttemptDigestSnapshot {
            reference = reference == null ? "" : reference;
            digest = digest == null ? "" : digest;
            agentId = agentId == null ? "" : agentId;
            stepId = stepId == null ? "" : stepId;
            sequence = Math.max(0, sequence);
        }
    }

    public record SessionSnapshot(Map<String, String> workState,
                                  List<EvidenceSnapshot> evidenceJournal,
                                  List<String> modifiedFiles,
                                  List<AttemptDigestSnapshot> attemptDigests,
                                  String taskLedger,
                                  long sequence,
                                  String taskId,
                                  boolean taskEnded,
                                  List<KeyEventSnapshot> keyEvents,
                                  List<String> protectedConstraints) {
        public SessionSnapshot(Map<String, String> workState,
                               List<EvidenceSnapshot> evidenceJournal,
                               List<String> modifiedFiles,
                               List<AttemptDigestSnapshot> attemptDigests,
                               String taskLedger,
                               long sequence,
                               String taskId,
                               boolean taskEnded,
                               List<KeyEventSnapshot> keyEvents) {
            this(workState, evidenceJournal, modifiedFiles, attemptDigests, taskLedger,
                    sequence, taskId, taskEnded, keyEvents, List.of());
        }

        public SessionSnapshot {
            protectedConstraints = protectedConstraints == null
                    ? List.of() : List.copyOf(protectedConstraints);
        }
    }

    public record RagEvidence(String filePath,
                              String symbolName,
                              String chunkType,
                              String symbolVersion,
                              String classpathEpoch,
                              String indexEpoch,
                              String query,
                              double similarity,
                              Instant capturedAt) {}

    private record PendingRagEvidence(String chunkType, String symbolName, double similarity, String filePath) {}
}
