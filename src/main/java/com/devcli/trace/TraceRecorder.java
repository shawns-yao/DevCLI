package com.devcli.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.policy.SensitiveDataRedactor;
import com.devcli.observability.TraceSpan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final int MAX_FIELD_CHARS = 1200;
    private final Path traceDir;
    private final Object writeLock = new Object();

    public TraceRecorder() {
        this(defaultTraceDir());
    }

    public TraceRecorder(Path traceDir) {
        this.traceDir = traceDir;
    }

    public Path getTraceDir() {
        return traceDir;
    }

    public void record(TraceContext context, String event, Map<String, ?> fields) {
        if (context == null || event == null || event.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("traceId", context.traceId());
        payload.put("phase", context.phase());
        payload.put("event", event);
        putTelemetry(payload, context.telemetry());
        if (fields != null) {
            fields.forEach((key, value) -> payload.put(key, sanitizeValue(value)));
        }
        try {
            synchronized (writeLock) {
                Files.createDirectories(traceDir);
                Files.writeString(todayFile(), MAPPER.writeValueAsString(payload) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("Trace 写入失败: {}", e.getMessage());
        }
    }

    public void record(TraceSpan span) {
        if (span == null) return;
        Map<String, Object> fields = new LinkedHashMap<>(span.attributes());
        fields.put("parentSpanId", span.parentSpanId());
        fields.put("startedAt", span.startedAt().toString());
        fields.put("endedAt", span.endedAt().toString());
        fields.put("durationMs", span.durationMillis());
        fields.put("status", span.status());
        record(new TraceContext(span.context().traceId(), span.name(), span.context()),
                "span.completed", fields);
    }

    private Path todayFile() {
        return traceDir.resolve("trace-" + LocalDate.now().format(DATE_FMT) + ".jsonl");
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

    private static Object sanitizeValue(Object value) {
        if (value instanceof String text) {
            return truncate(sanitize(text));
        }
        return value;
    }

    private static void putTelemetry(Map<String, Object> payload,
                                     com.devcli.observability.RunTelemetry telemetry) {
        if (telemetry == null) return;
        putIfPresent(payload, "runId", telemetry.runId());
        putIfPresent(payload, "turnId", telemetry.turnId());
        putIfPresent(payload, "stepId", telemetry.stepId());
        putIfPresent(payload, "agentId", telemetry.agentId());
        putIfPresent(payload, "attemptId", telemetry.attemptId());
    }

    private static void putIfPresent(Map<String, Object> payload, String name, String value) {
        if (value != null && !value.isBlank()) payload.put(name, value);
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_FIELD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_FIELD_CHARS) + "...(truncated)";
    }

    static String sanitize(String text) {
        return SensitiveDataRedactor.redact(text);
    }
}
