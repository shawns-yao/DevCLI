package com.devcli.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具结果尺寸治理 —— DevCLI 的"工具结果落盘 + 分级截断"机制。
 *
 * <p><b>解决的问题</b>：
 * <ul>
 *   <li>{@code execute_command "mvn test"} 输出 50K 字符直接占满 conversationHistory</li>
 *   <li>{@code web_fetch} 大网页内容把窗口打爆</li>
 *   <li>{@code search_code} ripgrep 命中数千行无关代码</li>
 * </ul>
 *
 * <p><b>三级处理策略</b>（按结果字符数）：
 * <ol>
 *   <li>≤ {@link #INLINE_THRESHOLD_CHARS} (5K)：原文进 messages，零额外开销</li>
 *   <li>{@link #INLINE_THRESHOLD_CHARS} ~ {@link #PERSIST_THRESHOLD_CHARS} (5K~50K)：
 *       保留 5K 预览，完整原文写入运行时结果存储并返回 result_ref</li>
 *   <li>> {@link #PERSIST_THRESHOLD_CHARS} (50K)：完整落盘到
 *       受控运行时结果目录，messages 里只保留 1.5K 预览 + result_ref</li>
 * </ol>
 *
 * <p><b>不参与尺寸治理的工具白名单</b>（{@link #PASSTHROUGH_TOOLS}）：
 * <ul>
 *   <li>{@code revert_turn}：状态控制工具，结果是简单确认信息</li>
 *   <li>image-bearing 结果（含 imageParts）：图片 part 不能截断</li>
 * </ul>
 *
 * <p><b>跟 cc 的差别</b>：cc 用 {@code Tool.maxResultSizeChars} 字段让每个工具独立配置；
 * DevCLI 选择全局阈值 + 白名单——简单，且 DevCLI 工具数量小（9 个内置 + MCP 动态），
 * 不需要细粒度配置。
 *
 * <p>线程安全：尺寸预算和本轮精确重复索引均为线程隔离状态；并行工具线程共享父轮快照。
 * 落盘时按 {@code toolUseId} 命名文件，并行工具调用不会冲突。
 */
public final class ToolResultSizeManager {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSizeManager.class);

    /** 不参与尺寸治理的工具名白名单。 */
    private static final Set<String> PASSTHROUGH_TOOLS = Set.of("revert_turn");

    /** ≤ 此字符数的结果直接原文返回，不做任何处理。 */
    public static final int INLINE_THRESHOLD_CHARS = 5_000;

    /** > 此字符数的结果完整落盘，messages 只放预览 + 路径。 */
    public static final int PERSIST_THRESHOLD_CHARS = 50_000;

    /** 落盘时 messages 里保留的预览字符数。 */
    public static final int PERSIST_PREVIEW_CHARS = 1_500;

    /** 中间档（5K~50K）的截断目标长度。 */
    public static final int TRUNCATE_TARGET_CHARS = INLINE_THRESHOLD_CHARS;

    /** 同轮所有工具结果聚合预算上限：超过此值后继续降低每项截断阈值。 */
    public static final int AGGREGATE_LIMIT_CHARS = INLINE_THRESHOLD_CHARS * 4;  // 20K

    /** 同轮已消耗的聚合预算；并行工具线程共享同一个计数器。 */
    private static final InheritableThreadLocal<AtomicInteger> currentTurnUsedBudget =
            new InheritableThreadLocal<>() {
                @Override
                protected AtomicInteger initialValue() {
                    return new AtomicInteger(0);
                }

                @Override
                protected AtomicInteger childValue(AtomicInteger parentValue) {
                    return parentValue == null ? new AtomicInteger(0) : parentValue;
                }
            };

    /** 本轮已落盘结果索引；只用于新结果写入时的精确重复引用。 */
    private static final InheritableThreadLocal<Map<String, ToolResultArtifact>> recentArtifacts =
            new InheritableThreadLocal<>() {
                @Override
                protected Map<String, ToolResultArtifact> initialValue() {
                    return new ConcurrentHashMap<>();
                }

                @Override
                protected Map<String, ToolResultArtifact> childValue(Map<String, ToolResultArtifact> parentValue) {
                    return parentValue == null ? new ConcurrentHashMap<>() : parentValue;
                }
            };

    /** 中间档（5K~50K）在聚合超限后的截断目标长度。 */
    private static final int TRUNCATE_TARGET_UNDER_PRESSURE = INLINE_THRESHOLD_CHARS / 2; // 2500

    private ToolResultSizeManager() {}

    public enum CollapseClassification {
        INLINE,
        PASSTHROUGH,
        IMAGE_PASSTHROUGH,
        INLINE_TRUNCATED,
        PERSISTED_PREVIEW
    }

    /**
     * 处理工具执行结果，按尺寸分级。返回值是给 LLM 看的最终 result 文本。
     *
     * @param toolName    工具名（白名单判断，空时注入标记）
     * @param toolUseId   工具调用 ID（落盘文件名）
     * @param projectPath 项目根目录（落盘根路径）
     * @param hasImages   结果是否含图片 part（含图片不治理）
     * @param result      原始工具结果文本
     * @return 处理后的结果文本（可能是原文 / 截断 / 预览+路径 / 空结果标记）
     */
    public static String process(String toolName, String toolUseId, String projectPath,
                                 boolean hasImages, String result) {
        return manage(toolName, toolUseId, hasImages, result).text();
    }

    /** 治理完整 ToolOutput，并把可恢复引用作为强类型 side channel 继续向下游传播。 */
    public static ToolOutput processOutput(String toolName, String toolUseId, ToolOutput output) {
        ToolOutput normalized = output == null ? ToolOutput.success("") : output;
        ManagedResult managed = manage(
                toolName, toolUseId, normalized.hasImageParts(), normalized.text());
        ToolOutput result = new ToolOutput(
                normalized.status(), normalized.errorCode(), normalized.retryable(),
                managed.text(), normalized.imageParts(), normalized.modifiedResources(),
                normalized.sideChannels());
        return managed.artifact() == null ? result : result.withSideChannel(managed.artifact());
    }

    private static ManagedResult manage(String toolName, String toolUseId,
                                        boolean hasImages, String result) {
        // 空结果注入：避免 LLM 看到空 tool_result 后断裂对话
        if (result == null || result.isBlank()) {
            String label = toolName == null ? "工具" : toolName;
            return new ManagedResult("(" + label + " 执行完毕无输出)", null);
        }
        CollapseClassification classification = classify(toolName, hasImages, result);
        if (classification != CollapseClassification.IMAGE_PASSTHROUGH
                && classification != CollapseClassification.PASSTHROUGH
                && classification != CollapseClassification.INLINE) {
            String duplicateKey = duplicateKey(toolName, result);
            ToolResultArtifact previous = recentArtifacts.get().get(duplicateKey);
            if (previous != null && !previous.artifactRef().isBlank()) {
                String reference = duplicateReference(toolName, result.length(), previous);
                if (estimateTokens(reference) < estimateTokens(result)) {
                    currentBudget().addAndGet(reference.length());
                    return new ManagedResult(reference, previous);
                }
            }
        }
        if (classification == CollapseClassification.IMAGE_PASSTHROUGH
                || classification == CollapseClassification.PASSTHROUGH
                || classification == CollapseClassification.INLINE) {
            // 低档不治理：直接计入聚合预算但不截断
            currentBudget().addAndGet(result.length());
            return new ManagedResult(result, null);
        }

        // 防御 MCP 工具默认全部进入 size 治理（mcp__server__tool 命名）
        // 已经在 PASSTHROUGH 之外，自动接管

        int previewChars;
        int reservedBudget = 0;
        if (classification == CollapseClassification.INLINE_TRUNCATED) {
            AtomicInteger budget = currentBudget();
            int usedBefore = budget.getAndAdd(TRUNCATE_TARGET_CHARS);
            reservedBudget = TRUNCATE_TARGET_CHARS;
            boolean underPressure = usedBefore >= AGGREGATE_LIMIT_CHARS;
            previewChars = underPressure
                    ? TRUNCATE_TARGET_UNDER_PRESSURE : TRUNCATE_TARGET_CHARS;
        } else {
            previewChars = PERSIST_PREVIEW_CHARS;
        }
        try {
            ToolResultArtifactStore.StoredArtifact stored =
                    ToolResultArtifactStore.store(toolUseId, result);
            int kept = Math.min(previewChars, result.length());
            String preview = diagnosticPreview(result, kept);
            int dropped = result.length() - kept;
            String nextCursor = dropped > 0 ? Integer.toString(kept) : "";
            ToolResultArtifact artifact = new ToolResultArtifact(
                    classification.name(), stored.chars(), stored.bytes(), kept,
                    stored.ref(), nextCursor, stored.sha256());
            recentArtifacts.get().putIfAbsent(duplicateKey(toolName, result), artifact);
            String managed = renderArtifactPreview(
                    preview, dropped, result.length(), classification, artifact);
            currentBudget().addAndGet(managed.length() - reservedBudget);
            return new ManagedResult(
                    appendMcpClassification(toolName, managed, classification), artifact);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to persist tool output for {} ({}): {} — falling back to inline truncation",
                    toolName, toolUseId, e.getMessage());
            String fallback = truncateInline(result, TRUNCATE_TARGET_CHARS);
            currentBudget().addAndGet(fallback.length() - reservedBudget);
            return new ManagedResult(
                    appendMcpClassification(toolName, fallback, classification), null);
        }
    }

    /** 暴露给测试或 Agent：当前轮已消耗的聚合预算。 */
    public static int turnUsedBudget() {
        return currentBudget().get();
    }

    /** Agent 每轮工具执行前调用，重置聚合预算计数器。 */
    public static void resetTurnBudget() {
        currentTurnUsedBudget.set(new AtomicInteger(0));
        recentArtifacts.set(new ConcurrentHashMap<>());
    }

    private static AtomicInteger currentBudget() {
        AtomicInteger budget = currentTurnUsedBudget.get();
        if (budget == null) {
            budget = new AtomicInteger(0);
            currentTurnUsedBudget.set(budget);
        }
        return budget;
    }

    public static CollapseClassification classify(String toolName, boolean hasImages, String result) {
        if (result == null) {
            return CollapseClassification.INLINE;
        }
        if (hasImages) {
            return CollapseClassification.IMAGE_PASSTHROUGH;
        }
        if (PASSTHROUGH_TOOLS.contains(toolName)) {
            return CollapseClassification.PASSTHROUGH;
        }
        int len = result.length();
        if (len <= INLINE_THRESHOLD_CHARS) {
            return CollapseClassification.INLINE;
        }
        if (len <= PERSIST_THRESHOLD_CHARS) {
            return CollapseClassification.INLINE_TRUNCATED;
        }
        return CollapseClassification.PERSISTED_PREVIEW;
    }

    private static String appendMcpClassification(String toolName, String managedResult,
                                                  CollapseClassification classification) {
        if (toolName == null || !toolName.startsWith("mcp__")) {
            return managedResult;
        }
        return managedResult + "\n[工具结果折叠分类: " + classification + "]";
    }

    private static String duplicateKey(String toolName, String result) {
        return (toolName == null ? "" : toolName) + "\n" + sha256(result);
    }

    private static String duplicateReference(String toolName, int chars, ToolResultArtifact artifact) {
        return String.format(Locale.ROOT,
                "[重复工具结果已折叠: tool=%s, original_chars=%d, result_ref=%s, sha256=%s；复用前一次结果]",
                toolName == null ? "unknown" : toolName, chars,
                artifact.artifactRef(), artifact.sha256());
    }

    private static int estimateTokens(String value) {
        return Math.max(1, (value == null ? 0 : value.length() + 3) / 4);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 中间档：保留头部和有限尾部预览，加截断提示。
     */
    static String truncateInline(String result, int keepChars) {
        int total = result.length();
        int kept = Math.min(keepChars, total);
        int dropped = total - kept;
        return diagnosticPreview(result, kept)
                + "\n\n...(已截断 " + dropped + " 字符 / 共 " + total
                + " 字符；使用 search_code 或 grep 进一步过滤可避免截断)";
    }

    /**
     * 保留完整头部预览，并追加有限尾部预览。头部长度保持原有预算语义，
     * 尾部用于暴露命令最终退出信息、测试失败摘要等诊断内容。
     */
    private static String diagnosticPreview(String result, int headChars) {
        if (result == null || result.length() <= headChars) {
            return result == null ? "" : result;
        }
        int tailChars = Math.min(1_000, result.length() - headChars);
        return result.substring(0, headChars)
                + "\n\n...[中间内容已省略，保留尾部 " + tailChars + " 字符]...\n\n"
                + result.substring(result.length() - tailChars);
    }

    private static String renderArtifactPreview(
            String preview, int dropped, int total,
            CollapseClassification classification, ToolResultArtifact artifact) {
        String headline = classification == CollapseClassification.PERSISTED_PREVIEW
                ? "[工具输出过大已落盘 " + total + " 字符]"
                : "...(已截断 " + dropped + " 字符 / 共 " + total + " 字符)";
        return String.format(Locale.ROOT,
                "%s\n\n%s\n"
                        + "[tool_result metadata: classification=%s, original_chars=%d, "
                        + "original_bytes=%d, preview_chars=%d, result_ref=%s, "
                        + "next_cursor=%s, sha256=%s]\n"
                        + "(需要精确恢复时调用 read_tool_result，并传入 result_ref 与 next_cursor)",
                preview,
                headline,
                classification,
                artifact.originalChars(),
                artifact.originalBytes(),
                artifact.previewChars(),
                artifact.artifactRef(),
                artifact.nextCursor().isBlank() ? "none" : artifact.nextCursor(),
                artifact.sha256());
    }

    private record ManagedResult(String text, ToolResultArtifact artifact) {
    }
}
