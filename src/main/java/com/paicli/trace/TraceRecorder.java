package com.paicli.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

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

public class TraceRecorder {
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
            System.err.println("⚠️ Trace 写入失败: " + e.getMessage());
        }
    }

    private Path todayFile() {
        return traceDir.resolve("trace-" + LocalDate.now().format(DATE_FMT) + ".jsonl");
    }

    private static Path defaultTraceDir() {
        String prop = System.getProperty("paicli.trace.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("PAICLI_TRACE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".paicli", "traces");
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof String text) {
            return truncate(sanitize(text));
        }
        return value;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_FIELD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_FIELD_CHARS) + "...(truncated)";
    }

    static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String sanitized = text.replaceAll("(?i)Bearer\\s+[^\\s\"'}]+", "Bearer ***");
        sanitized = sanitized.replaceAll(
                "(?i)(\"?(?:token|key|password|secret|api_key)\"?\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***$3");
        sanitized = sanitized.replaceAll(
                "(?i)(\\b(?:token|key|password|secret|api_key)\\b\\s*[:=]\\s*)([^\\s,}]+)",
                "$1***");
        return sanitized;
    }
}
