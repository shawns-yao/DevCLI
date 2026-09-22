package com.devcli.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 读取 trace jsonl，按 runId 聚合执行结构。
 *
 * <p>trace 文件按天写入（见 {@link TraceRecorder}），查询时按文件名倒序扫描最近若干天，
 * 不维护第二份索引——文件本身就是唯一事实源。</p>
 */
public final class TraceQuery {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final int MAX_SCAN_FILES = 7;
    private static final int MAX_TIMELINE_EVENTS = 80;

    private final Path traceDir;

    public TraceQuery() {
        this(defaultTraceDir());
    }

    public TraceQuery(Path traceDir) {
        this.traceDir = traceDir;
    }

    /** 一次运行的聚合摘要。 */
    public record RunSummary(String runId, String firstAt, String lastAt, int eventCount,
                             int toolCallCount, int inputTokens, int outputTokens,
                             String terminalState, String firstInput) {
    }

    public List<RunSummary> listRuns(int limit) {
        Map<String, RunAggregator> byRun = new LinkedHashMap<>();
        for (JsonNode line : readRecentLines()) {
            RunAggregator aggregator = byRun.computeIfAbsent(
                    text(line, "traceId", "unknown"), RunAggregator::new);
            aggregator.accept(line);
        }
        List<RunSummary> summaries = byRun.values().stream()
                .map(RunAggregator::snapshot)
                .sorted(Comparator.comparing(RunSummary::lastAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
        return summaries;
    }

    public Optional<String> latestRunId() {
        List<RunSummary> runs = listRuns(1);
        return runs.stream().findFirst().map(RunSummary::runId);
    }

    /** 渲染某次运行的结构时间线；runId 为 "latest"/空 时取最近一次。 */
    public String renderRun(String runId) {
        String target = (runId == null || runId.isBlank() || "latest".equalsIgnoreCase(runId))
                ? latestRunId().orElse(null) : runId;
        if (target == null) {
            return "暂无 trace 记录（完成一次任务后自动生成）。";
        }
        List<JsonNode> events = readRecentLines().stream()
                .filter(node -> target.equals(text(node, "traceId", "")))
                .toList();
        if (events.isEmpty()) {
            return "未找到运行 " + target + " 的 trace（仅扫描最近 " + MAX_SCAN_FILES + " 天）。";
        }
        StringBuilder out = new StringBuilder()
                .append("运行 ").append(target).append(" 共 ").append(events.size()).append(" 个结构事件\n");
        events.stream().limit(MAX_TIMELINE_EVENTS).forEach(node ->
                out.append("  ").append(displayTime(node)).append("  ")
                        .append(pad(text(node, "type", text(node, "event", "-")), 24))
                        .append(summaryFields(node)).append('\n'));
        if (events.size() > MAX_TIMELINE_EVENTS) {
            out.append("  ... 其余 ").append(events.size() - MAX_TIMELINE_EVENTS)
                    .append(" 个事件已省略，完整内容见 traces 目录\n");
        }
        return out.toString();
    }

    public String renderList(int limit) {
        List<RunSummary> runs = listRuns(limit);
        if (runs.isEmpty()) {
            return "暂无 trace 记录（完成一次任务后自动生成）。";
        }
        StringBuilder out = new StringBuilder("最近运行：\n");
        for (RunSummary run : runs) {
            out.append("  ").append(run.runId())
                    .append("  ").append(run.firstAt()).append("→").append(run.lastAt())
                    .append("  事件").append(run.eventCount())
                    .append("  工具").append(run.toolCallCount())
                    .append("  tok ").append(run.inputTokens()).append("/").append(run.outputTokens())
                    .append("  [").append(run.terminalState()).append("]");
            if (run.firstInput() != null && !run.firstInput().isBlank()) {
                out.append("  ").append(run.firstInput());
            }
            out.append('\n');
        }
        out.append("\n/trace <runId> 查看时间线，/trace list 查看本列表\n");
        return out.toString();
    }

    private List<JsonNode> readRecentLines() {
        if (traceDir == null || !Files.isDirectory(traceDir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(traceDir)) {
            files = stream.filter(p -> p.getFileName().toString().startsWith("trace-"))
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_SCAN_FILES)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        List<JsonNode> lines = new ArrayList<>();
        for (Path file : files) {
            try {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    try {
                        lines.add(MAPPER.readTree(raw));
                    } catch (Exception ignored) {
                        // 损坏行跳过，不影响其余 trace。
                    }
                }
            } catch (IOException ignored) {
                // 单个文件不可读时继续扫描其他天文件。
            }
        }
        return lines;
    }

    private static String summaryFields(JsonNode node) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of("state", "tools", "category", "status", "error",
                "input_tokens", "decision", "input")) {
            JsonNode value = node.get(key);
            if (value != null && !value.asText("").isBlank()) {
                parts.add(key + "=" + value.asText());
            }
        }
        return String.join(" ", parts);
    }

    private static String displayTime(JsonNode node) {
        String timestamp = text(node, "timestamp", "");
        try {
            return TIME_FMT.format(LocalDateTime.ofInstant(Instant.parse(timestamp),
                    ZoneId.systemDefault()));
        } catch (Exception e) {
            return "--:--:--";
        }
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static Path defaultTraceDir() {
        String prop = System.getProperty("devcli.trace.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("DEVCLI_TRACE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".devcli", "traces");
    }

    /** 增量聚合器：包私有以便单测直接构造行验证。 */
    static final class RunAggregator {
        private final String runId;
        private String firstAt;
        private String lastAt;
        private int eventCount;
        private int toolCallCount;
        private int inputTokens;
        private int outputTokens;
        private String terminalState = "RUNNING";
        private String firstInput = "";

        RunAggregator(String runId) {
            this.runId = runId;
        }

        void accept(JsonNode node) {
            eventCount++;
            String timestamp = text(node, "timestamp", "");
            if (!timestamp.isBlank()) {
                String shortTime = timestamp.replace('T', ' ');
                if (firstAt == null) {
                    firstAt = shortTime;
                }
                lastAt = shortTime;
            }
            String type = text(node, "type", "");
            if ("tool.calls".equals(type)) {
                String tools = text(node, "tools", "");
                if (!tools.isBlank()) {
                    toolCallCount += (int) Stream.of(tools.split(","))
                            .filter(s -> !s.isBlank()).count();
                }
            } else if ("model.usage".equals(type)) {
                inputTokens += node.path("input_tokens").asInt(0);
                outputTokens += node.path("output_tokens").asInt(0);
            } else if ("turn.completed".equals(type)) {
                terminalState = text(node, "status", "completed");
            } else if ("turn.failed".equals(type)) {
                terminalState = "FAILED";
            } else if ("execution.state".equals(type)) {
                String state = text(node, "state", "");
                if (state.contains("CANCEL")) {
                    terminalState = "CANCELLED";
                } else if (state.contains("FAIL")) {
                    terminalState = state;
                }
            } else if ("turn.started".equals(type) && firstInput.isBlank()) {
                firstInput = text(node, "input", "");
            }
        }

        RunSummary snapshot() {
            return new RunSummary(runId,
                    firstAt == null ? "-" : firstAt,
                    lastAt == null ? "-" : lastAt,
                    eventCount, toolCallCount, inputTokens, outputTokens,
                    terminalState, firstInput);
        }
    }
}
