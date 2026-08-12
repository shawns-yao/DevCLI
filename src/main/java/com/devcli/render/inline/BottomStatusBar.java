package com.devcli.render.inline;

import com.devcli.render.StatusInfo;
import com.devcli.render.state.RunSnapshot;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JLine 托管的底部 dock。
 *
 * <p>只通过 {@link Status} 更新底部保留区，不再手写换行、绝对光标行号或
 * {@code CLEAR_TO_EOS}。正文输出、thinking activity 和 LineReader 输入区都交给
 * JLine 共同协调，避免多个组件争抢同一块物理终端区域。
 *
 * <p>保留类名是为了让 {@link InlineRenderer} 的边界稳定：外部仍然只看
 * start/update/close，不关心底层布局实现。
 */
public final class BottomStatusBar implements AutoCloseable {

    private static final int CONTEXT_BAR_WIDTH = 8;
    private static final Pattern SUMMARY_RATIO = Pattern.compile("(?i)^(?:MCP|Skill)\\s+(\\d+)/(\\d+)$");

    private final Terminal terminal;
    private final PrintStream out;
    private volatile StatusInfo current;
    private volatile RunSnapshot runSnapshot = RunSnapshot.empty();
    private Status status;
    private volatile boolean started;
    private volatile boolean closed;

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.out = System.out;
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    BottomStatusBar(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.out = out;
    }

    /** 初始化状态栏。重复调用无副作用。 */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        status = Status.getStatus(terminal);
        if (status != null) {
            status.setBorder(true);
        }
        started = true;
        renderDock();
    }

    public void update(StatusInfo info) {
        this.current = mergeEnvironment(info, current);
        renderDock();
    }

    public void updateSnapshot(RunSnapshot snapshot) {
        this.runSnapshot = snapshot == null ? RunSnapshot.empty() : snapshot;
        renderDock();
    }

    /** 当前 StatusInfo 快照，供 thinking 面板等组件复用同一份格式化结果。 */
    public StatusInfo currentStatus() {
        return current;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        renderDock();
    }

    /** 在即将读取输入时刷新 JLine dock；光标和输入行位置由 LineReader 管理。 */
    public void prepareInputLine() {
        renderDock();
        moveCursorToDockInputRow();
    }

    /** 输入提交后保留底部 dock；正文继续在 JLine 保留区上方滚动。 */
    public void finishInputLine() {
        renderDock();
    }

    private void renderDock() {
        StatusInfo info = current;
        Status dock = status;
        if ((info == null && runSnapshot.version() == 0) || dock == null || closed || !started) {
            return;
        }
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        synchronized (out) {
            dock.update(formatStatusLines(info, runSnapshot, cols));
        }
    }

    private void moveCursorToDockInputRow() {
        StatusInfo info = current;
        if ((info == null && runSnapshot.version() == 0) || closed || !started) {
            return;
        }
        int rows = TerminalCapabilities.safeSize(terminal).getRows();
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        int dockRows = formatStatusLines(info, runSnapshot, cols).size() + 1;
        int inputRow = inputDockRow(rows, dockRows);
        synchronized (out) {
            terminal.puts(InfoCmp.Capability.cursor_address, inputRow, 0);
            terminal.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Status dock = status;
        status = null;
        if (dock != null) {
            dock.close();
        }
    }

    static String formatStatusLine(StatusInfo info, int cols) {
        return formatStatusLine(info, null, cols);
    }

    static String formatFooterLine(StatusInfo info, int cols) {
        return formatFooterLine(info, null, cols);
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("  ").append(value.trim());
    }

    private static StatusInfo mergeEnvironment(StatusInfo next, StatusInfo previous) {
        if (next == null || previous == null) {
            return next;
        }
        String mcp = next.mcpSummary() == null || next.mcpSummary().isBlank()
                ? previous.mcpSummary()
                : next.mcpSummary();
        String skill = next.skillSummary() == null || next.skillSummary().isBlank()
                ? previous.skillSummary()
                : next.skillSummary();
        if (mcp == next.mcpSummary() && skill == next.skillSummary()) {
            return next;
        }
        return next.withEnvironment(mcp, skill);
    }

    static List<AttributedString> formatStatusLines(StatusInfo info, int cols) {
        return formatStatusLines(info, null, cols);
    }

    static List<AttributedString> formatStatusLines(StatusInfo info, RunSnapshot snapshot, int cols) {
        StatusInfo safe = info == null ? StatusInfo.idle("Auto Model", 0, false) : info;
        return List.of(
                new AttributedString(formatStatusLine(safe, snapshot, cols), AttributedStyle.DEFAULT),
                new AttributedString(formatFooterLine(safe, snapshot, cols), AttributedStyle.DEFAULT.faint())
        );
    }

    static String formatStatusLine(StatusInfo info, RunSnapshot snapshot, int cols) {
        String mode = info.hitlEnabled() ? "HITL Ctrl+Y for AUTO" : "YOLO Ctrl+Y to enable HITL";
        String phase = snapshot == null || snapshot.phase().isBlank() ? info.phase() : snapshot.phase();
        String security = snapshot == null ? "" : snapshot.securityDomain();
        String recovery = snapshot == null ? "" : snapshot.recoveryState();
        String left = joinFields(mode, phase, security, recovery);
        String right = environmentSummary(info);
        String rendered = fitPriority(left, right, cols);
        if (cols < 48 && !security.isBlank() && !rendered.contains(security)) {
            rendered = fitPriority(joinFields(compactMode(info.hitlEnabled()), security), "", cols);
        }
        return rendered;
    }

    static String formatFooterLine(StatusInfo info, RunSnapshot snapshot, int cols) {
        String model = info.model() == null || info.model().isBlank() ? "Auto Model" : info.model().trim();
        long input = snapshot == null || snapshot.version() == 0 ? info.inputTokens() : snapshot.inputTokens();
        long output = snapshot == null || snapshot.version() == 0 ? info.outputTokens() : snapshot.outputTokens();
        String cost = snapshot == null || snapshot.estimatedCost().isBlank()
                ? info.estimatedCost() : snapshot.estimatedCost();
        String trace = snapshot == null ? "" : compactId(snapshot.context().traceId());
        String phase = snapshot == null || snapshot.phase().isBlank() ? info.phase() : snapshot.phase();
        StringBuilder line = new StringBuilder("Auto Model · ").append(model);
        appendField(line, phase);
        appendField(line, contextSegment(info));
        if (input > 0 || output > 0) {
            String tokens = "in " + formatTokens(input) + " out " + formatTokens(output);
            long cached = snapshot == null || snapshot.version() == 0
                    ? info.cachedInputTokens() : snapshot.cachedInputTokens();
            if (cached > 0) tokens += " cache " + formatTokens(cached);
            appendField(line, tokens);
        }
        appendField(line, cost);
        if (info.elapsedMillis() > 0) appendField(line, formatElapsed(info.elapsedMillis()));
        appendField(line, trace.isBlank() ? "" : "trace " + trace);
        appendField(line, compactCwd());
        return fitToColumns(" " + line, cols);
    }

    private static String joinFields(String... values) {
        return java.util.Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" · "));
    }

    private static String fitPriority(String left, String right, int cols) {
        if (right == null || right.isBlank()) return fitToColumns(" " + left, cols);
        int gap = Math.max(1, cols - visibleLength(left) - visibleLength(right) - 2);
        if (gap == 1 && visibleLength(left) + visibleLength(right) + 2 > cols) {
            return fitToColumns(" " + left, cols);
        }
        return fitToColumns(" " + left + " ".repeat(gap) + right + " ", cols);
    }

    private static String compactId(String value) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= 10 ? value : value.substring(0, 10);
    }

    private static String compactMode(boolean hitlEnabled) {
        return hitlEnabled ? "HITL" : "YOLO";
    }

    static int inputDockRow(int terminalRows, int dockRows) {
        return Math.max(0, terminalRows - Math.max(0, dockRows) - 1);
    }

    private static String fitToColumns(String text, int cols) {
        if (cols <= 0) {
            return "";
        }
        String safe = text == null ? "" : text;
        if (safe.length() > cols) {
            return safe.substring(0, cols);
        }
        return safe + " ".repeat(cols - safe.length());
    }

    private static String environmentSummary(StatusInfo info) {
        String mcp = formatEnvironment(info.mcpSummary(), "MCP server", "MCP servers");
        String skill = formatEnvironment(info.skillSummary(), "skill", "skills");
        if (mcp.isBlank()) {
            return skill;
        }
        if (skill.isBlank()) {
            return mcp;
        }
        return mcp + " · " + skill;
    }

    private static String formatEnvironment(String raw, String singular, String plural) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        Matcher matcher = SUMMARY_RATIO.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        int active = Integer.parseInt(matcher.group(1));
        int total = Integer.parseInt(matcher.group(2));
        if (active == total) {
            return total + " " + (total == 1 ? singular : plural);
        }
        return active + "/" + total + " " + plural;
    }

    private static String contextSegment(StatusInfo info) {
        long total = Math.max(Math.max(0L, info.totalTokens()),
                Math.max(0L, info.inputTokens()) + Math.max(0L, info.outputTokens()));
        long window = Math.max(0L, info.contextWindow());
        int percent = window <= 0L ? 0 : (int) Math.min(100L, Math.round(total * 100.0 / window));
        int filled = window <= 0L ? 0 : (int) Math.min(CONTEXT_BAR_WIDTH,
                Math.round(total * CONTEXT_BAR_WIDTH * 1.0 / window));
        String bar = "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, CONTEXT_BAR_WIDTH - filled));
        return "ctx " + bar + " " + percent + "% (" + formatTokens(total) + "/" + formatTokens(window) + ")";
    }

    private static String compactCwd() {
        String cwd = System.getProperty("user.dir");
        if (cwd == null || cwd.isBlank()) {
            return "";
        }
        String normalized = Path.of(cwd).toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && normalized.startsWith(home)) {
            normalized = "~" + normalized.substring(home.length());
        }
        return normalized;
    }

    private static int visibleLength(String text) {
        return text == null ? 0 : text.length();
    }

    private static String formatTokens(long t) {
        if (t >= 1_000_000) {
            return String.format("%.1fM", t / 1_000_000.0);
        }
        if (t >= 1_000) {
            return String.format("%.1fk", t / 1_000.0);
        }
        return String.valueOf(t);
    }

    private static String formatElapsed(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }
}
