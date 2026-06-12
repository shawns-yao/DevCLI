package com.paicli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 当前会话工作记忆。
 *
 * <p>取代旧的 {@code ConversationMemory}（旧版本同时承担"对话原文笔记本"和"压缩状态"，
 * 职责模糊导致两条数据流跟 {@code conversationHistory} 重复）。
 *
 * <p>WorkingMemory 的清晰职责：
 * <ul>
 *   <li>不参与 LLM messages 数组（messages 由 {@code Agent.conversationHistory} 维护）</li>
 *   <li>作为 system prompt 的<b>派生视图</b>注入，解决"摘要泛化掉精确实体"的痛点</li>
 *   <li>仅当前会话有效，不跨 session 持久化</li>
 *   <li>三类内容互不重叠：</li>
 * </ul>
 *
 * <p>三个 sub-store：
 * <ol>
 *   <li><b>recentToolResults</b>：最近 K 个工具调用的完整结果（不被截断到 500 字符）。
 *       压缩摘要会泛化掉「读了 CodeRetriever.java 第 217 行」「mvn 输出 47.3s」「服务端口 8443」
 *       这类精确实体；这里保留原文让 LLM 能回看具体证据。FIFO 淘汰。</li>
 *   <li><b>taskState</b>：当前任务进度的键值对（"plan_task=task_3"、"react_iteration=12"、
 *       "last_error=MCP schema missing required"）。让 LLM 在长会话里知道自己在哪。</li>
 *   <li><b>volatileFacts</b>：当前会话临时事实（"刚跑过 mvn test"、"刚改了 X 文件"）。
 *       FIFO 淘汰。避免 LLM 重复跑同一工具。</li>
 * </ol>
 *
 * <p>跟其它三层 Memory 的关系：
 * <ul>
 *   <li>conversation history：真实 messages，{@code ConversationHistoryCompactor} 压它</li>
 *   <li><b>working memory</b>（本类）：派生视图，作为 system prompt 段注入，不进 messages</li>
 *   <li>long-term memory：跨会话持久化事实，按 query 检索注入</li>
 *   <li>sticky memory：跨会话持久化强约束，每轮全量注入</li>
 * </ul>
 *
 * <p>线程安全：ReAct / Plan 主循环通常是单线程，但 Multi-Agent 会让多个 SubAgent
 * 并发回写工具证据到同一个 {@link MemoryManager}，因此所有读写方法都同步保护。
 */
public class WorkingMemory {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemory.class);

    /** 默认保留最近多少个工具结果。超过 8 个的话注入 system prompt 会过长。 */
    public static final int DEFAULT_MAX_TOOL_RESULTS = 8;
    /** 默认保留多少个 volatile facts。 */
    public static final int DEFAULT_MAX_VOLATILE_FACTS = 16;
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

    private final int maxToolResults;
    private final int maxVolatileFacts;
    private final int maxRagEvidence;
    private final LinkedList<ToolEvidence> recentToolResults = new LinkedList<>();
    private final LinkedList<RagEvidence> ragEvidenceMemory = new LinkedList<>();
    private final LinkedList<String> volatileFacts = new LinkedList<>();
    private final LinkedHashMap<String, String> taskState = new LinkedHashMap<>();

    public enum View {
        FULL,
        PLANNER,
        WORKER,
        REVIEWER
    }

    public WorkingMemory() {
        this(DEFAULT_MAX_TOOL_RESULTS, DEFAULT_MAX_VOLATILE_FACTS, DEFAULT_MAX_RAG_EVIDENCE);
    }

    public WorkingMemory(int maxToolResults, int maxVolatileFacts) {
        this(maxToolResults, maxVolatileFacts, DEFAULT_MAX_RAG_EVIDENCE);
    }

    public WorkingMemory(int maxToolResults, int maxVolatileFacts, int maxRagEvidence) {
        this.maxToolResults = Math.max(1, maxToolResults);
        this.maxVolatileFacts = Math.max(1, maxVolatileFacts);
        this.maxRagEvidence = Math.max(1, maxRagEvidence);
    }

    // ─────────────────────────────────────────────────────────
    // recentToolResults
    // ─────────────────────────────────────────────────────────

    /**
     * 记录一次工具调用结果。FIFO 淘汰旧条目。
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
        if (toolName == null || result == null) return;
        recentToolResults.addLast(new ToolEvidence(
                toolName,
                argsJson == null ? "" : argsJson,
                result,
                Instant.now()));
        while (recentToolResults.size() > maxToolResults) {
            recentToolResults.removeFirst();
        }
        recordRagEvidenceIfPresent(toolName, argsJson, result);
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
        if (key == null || key.isBlank()) return;
        if (value == null || value.isBlank()) {
            taskState.remove(key);
        } else {
            taskState.put(key, value);
        }
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

    // ─────────────────────────────────────────────────────────
    // volatileFacts
    // ─────────────────────────────────────────────────────────

    /**
     * 添加一条本会话临时事实。例如 "刚跑过 mvn test -Pquick"、"刚改了 LongTermMemory.java"。
     * FIFO 淘汰最旧。同内容重复添加会更新到末尾。
     */
    public synchronized void addVolatileFact(String fact) {
        if (fact == null || fact.isBlank()) return;
        String trimmed = fact.trim();
        volatileFacts.remove(trimmed); // 去重 + 移到末尾
        volatileFacts.addLast(trimmed);
        while (volatileFacts.size() > maxVolatileFacts) {
            volatileFacts.removeFirst();
        }
    }

    public synchronized List<String> getVolatileFacts() {
        return Collections.unmodifiableList(new ArrayList<>(volatileFacts));
    }

    // ─────────────────────────────────────────────────────────
    // 派生视图：注入 system prompt
    // ─────────────────────────────────────────────────────────

    /**
     * 渲染为 system prompt 一段 Markdown。空内容返回空串（PromptAssembler 会跳过空段）。
     */
    public synchronized String renderForPrompt() {
        return renderForPrompt(View.FULL);
    }

    /**
     * 按 Agent 角色渲染工作记忆派生视图，避免 Multi-Agent 三角色共享同一份运行态证据。
     */
    public synchronized String renderForPrompt(View view) {
        View effectiveView = view == null ? View.FULL : view;
        StringBuilder sb = new StringBuilder();
        if (!taskState.isEmpty()) {
            sb.append("### 当前任务状态\n\n");
            for (Map.Entry<String, String> e : taskState.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            sb.append('\n');
        }
        if (shouldRenderVolatileFacts(effectiveView) && !volatileFacts.isEmpty()) {
            sb.append("### 本会话已发生的关键事件（避免重复执行）\n\n");
            // 倒序，最新事件在前
            List<String> reversed = new ArrayList<>(volatileFacts);
            Collections.reverse(reversed);
            for (String f : reversed) {
                sb.append("- ").append(f).append('\n');
            }
            sb.append('\n');
        }
        if (shouldRenderToolEvidence(effectiveView) && !ragEvidenceMemory.isEmpty()) {
            sb.append("### RAG 证据记忆（绑定 SymbolVersion）\n\n");
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
            sb.append('\n');
        }
        if (shouldRenderToolEvidence(effectiveView) && !recentToolResults.isEmpty()) {
            sb.append("### 最近工具调用证据（精确实体来源）\n\n");
            // 倒序，最新调用在前
            List<ToolEvidence> reversed = new ArrayList<>(recentToolResults);
            Collections.reverse(reversed);
            for (ToolEvidence ev : reversed) {
                sb.append("- **").append(ev.toolName).append("**");
                if (!ev.argsJson.isBlank()) {
                    sb.append(" args: `").append(truncate(ev.argsJson, 120)).append('`');
                }
                sb.append('\n');
                sb.append("  ```\n  ").append(truncate(ev.result, TOOL_RESULT_RENDER_CHARS)
                        .replace("\n", "\n  ")).append("\n  ```\n");
            }
        }
        return sb.toString().trim();
    }

    private static boolean shouldRenderVolatileFacts(View view) {
        return view == View.FULL || view == View.PLANNER || view == View.WORKER;
    }

    private static boolean shouldRenderToolEvidence(View view) {
        return view == View.FULL || view == View.WORKER || view == View.REVIEWER;
    }

    /** 状态摘要给 /memory 命令显示。 */
    public synchronized String getStatusSummary() {
        return String.format(Locale.ROOT,
                "工作记忆: %d 工具证据 / %d RAG证据 / %d 任务状态 / %d 临时事实",
                recentToolResults.size(), ragEvidenceMemory.size(), taskState.size(), volatileFacts.size());
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
    public synchronized int pruneInvalidEvidence(com.paicli.rag.SymbolInvalidation symbolInvalidation) {
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

    /** 清空整个 working memory（用于 /clear 命令）。 */
    public synchronized void clear() {
        recentToolResults.clear();
        ragEvidenceMemory.clear();
        volatileFacts.clear();
        taskState.clear();
    }

    private void recordRagEvidenceIfPresent(String toolName, String argsJson, String result) {
        if (!"search_code".equals(toolName) || result == null || result.isBlank()) {
            return;
        }
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
                addVolatileFact("NegativeFact（负向事实）: " + negativeFactMatcher.group(1).trim());
            }
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

        ToolEvidence(String toolName, String argsJson, String result, Instant capturedAt) {
            this.toolName = toolName;
            this.argsJson = argsJson;
            this.result = result;
            this.capturedAt = capturedAt;
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
