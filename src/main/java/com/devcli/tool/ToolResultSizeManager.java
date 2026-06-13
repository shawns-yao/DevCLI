package com.devcli.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

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
 *       尾部截断到 5K + 标注剩余字符数。中等输出，截断后能保留 LLM 最关心的命令前缀（路径/文件名/错误关键字一般在前部）</li>
 *   <li>> {@link #PERSIST_THRESHOLD_CHARS} (50K)：完整落盘到
 *       {@code <projectPath>/.devcli/tool_outputs/<sessionId>/<toolUseId>.txt}，
 *       messages 里只保留 1.5K 预览 + 文件路径 + 提示"用 read_file 看完整内容"</li>
 * </ol>
 *
 * <p><b>不参与治理的工具白名单</b>（{@link #PASSTHROUGH_TOOLS}）：
 * <ul>
 *   <li>{@code read_file}：自身就是文件读取，再次落盘等于 read→file→read 死循环</li>
 *   <li>{@code list_dir}：目录树本身就是结构化短输出，截断会破坏可读性</li>
 *   <li>{@code revert_turn}：状态控制工具，结果是简单确认信息</li>
 *   <li>image-bearing 结果（含 imageParts）：图片 part 不能截断</li>
 * </ul>
 *
 * <p><b>跟 cc 的差别</b>：cc 用 {@code Tool.maxResultSizeChars} 字段让每个工具独立配置；
 * DevCLI 选择全局阈值 + 白名单——简单，且 DevCLI 工具数量小（9 个内置 + MCP 动态），
 * 不需要细粒度配置。
 *
 * <p>线程安全：{@link #process} 静态调用，无可变状态。落盘时按 {@code toolUseId}
 * 命名文件，并行工具调用不会冲突。
 */
public final class ToolResultSizeManager {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSizeManager.class);

    /** 不参与尺寸治理的工具名白名单。 */
    private static final Set<String> PASSTHROUGH_TOOLS = Set.of(
            "read_file",     // 自身就是文件读取，截断等于破坏功能
            "list_dir",      // 短结构化输出
            "revert_turn"    // 状态控制
    );

    /** ≤ 此字符数的结果直接原文返回，不做任何处理。 */
    public static final int INLINE_THRESHOLD_CHARS = 5_000;

    /** > 此字符数的结果完整落盘，messages 只放预览 + 路径。 */
    public static final int PERSIST_THRESHOLD_CHARS = 50_000;

    /** 落盘时 messages 里保留的预览字符数。 */
    public static final int PERSIST_PREVIEW_CHARS = 1_500;

    /** 中间档（5K~50K）的截断目标长度。 */
    public static final int TRUNCATE_TARGET_CHARS = INLINE_THRESHOLD_CHARS;

    /** 落盘根目录名（在 projectPath 下）。 */
    public static final String OUTPUTS_DIR = ".devcli/tool_outputs";

    private static final DateTimeFormatter SESSION_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    /** 当前会话的目录名（启动时确定，进程内复用）。 */
    private static final String SESSION_ID = SESSION_ID_FMT.format(Instant.now());

    private ToolResultSizeManager() {}

    /**
     * 处理工具执行结果，按尺寸分级。返回值是给 LLM 看的最终 result 文本。
     *
     * @param toolName    工具名（白名单判断）
     * @param toolUseId   工具调用 ID（落盘文件名）
     * @param projectPath 项目根目录（落盘根路径）
     * @param hasImages   结果是否含图片 part（含图片不治理）
     * @param result      原始工具结果文本
     * @return 处理后的结果文本（可能是原文 / 截断 / 预览+路径）
     */
    public static String process(String toolName, String toolUseId, String projectPath,
                                 boolean hasImages, String result) {
        if (result == null) return "";
        if (hasImages) return result;
        if (PASSTHROUGH_TOOLS.contains(toolName)) return result;
        // 防御 MCP 工具默认全部进入 size 治理（mcp__server__tool 命名）
        // 已经在 PASSTHROUGH 之外，自动接管

        int len = result.length();
        if (len <= INLINE_THRESHOLD_CHARS) {
            return result;
        }
        if (len <= PERSIST_THRESHOLD_CHARS) {
            return truncateInline(result);
        }
        return persistAndPreview(toolName, toolUseId, projectPath, result);
    }

    /**
     * 中间档：尾部截断到 {@link #TRUNCATE_TARGET_CHARS}，加截断提示。
     */
    static String truncateInline(String result) {
        int total = result.length();
        int kept = TRUNCATE_TARGET_CHARS;
        int dropped = total - kept;
        return result.substring(0, kept)
                + "\n\n...(已截断 " + dropped + " 字符 / 共 " + total
                + " 字符；使用 search_code 或 grep 进一步过滤可避免截断)";
    }

    /**
     * 高档：落盘 + 预览。落盘失败时降级为 {@link #truncateInline}（不阻断主流程）。
     */
    static String persistAndPreview(String toolName, String toolUseId, String projectPath, String result) {
        Path outFile;
        try {
            Path outDir = Path.of(projectPath, OUTPUTS_DIR, SESSION_ID);
            Files.createDirectories(outDir);
            String safeId = sanitizeFileName(toolUseId == null ? "anon" : toolUseId);
            outFile = outDir.resolve(safeId + ".txt");
            Files.writeString(outFile, result, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to persist tool output for {} ({}): {} — falling back to inline truncation",
                    toolName, toolUseId, e.getMessage());
            return truncateInline(result);
        }

        int total = result.length();
        String preview = result.length() <= PERSIST_PREVIEW_CHARS
                ? result
                : result.substring(0, PERSIST_PREVIEW_CHARS);

        return String.format(Locale.ROOT,
                "%s\n\n[工具输出过大已落盘 %d 字符 → 完整内容: %s]\n"
                        + "(以上为前 %d 字符预览，需要完整内容请用 read_file 读取该文件)",
                preview,
                total,
                outFile.toAbsolutePath(),
                Math.min(PERSIST_PREVIEW_CHARS, total));
    }

    /** 文件名安全化：去掉路径分隔符和控制字符。 */
    private static String sanitizeFileName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 128; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "anon" : sb.toString();
    }

    /** 暴露给测试：当前会话目录名。 */
    public static String currentSessionId() {
        return SESSION_ID;
    }
}
