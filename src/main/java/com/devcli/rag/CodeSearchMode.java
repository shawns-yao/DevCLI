package com.devcli.rag;

import java.util.Locale;

public enum CodeSearchMode {
    AUTO,
    GENERAL,
    CALL_CHAIN,
    DEFINITION,
    ERROR_TRACE,
    CONFIG;

    public static CodeSearchMode resolve(String rawMode, String query) {
        CodeSearchMode requested = parse(rawMode);
        CodeSearchMode inferred = infer(query);
        if (requested == AUTO) {
            return inferred;
        }
        if (conflicts(requested, inferred)) {
            return inferred;
        }
        return requested;
    }

    static CodeSearchMode parse(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return AUTO;
        }
        String normalized = rawMode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return CodeSearchMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }

    private static CodeSearchMode infer(String query) {
        if (query == null || query.isBlank()) {
            return GENERAL;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "定义", "在哪里", "在哪", "声明", "class ", "interface ", "implements", "方法")) {
            return DEFINITION;
        }
        if (containsAny(lower, "exception", "error", "报错", "异常", "stacktrace", "npe", "nullpointer", "line ")) {
            return ERROR_TRACE;
        }
        if (containsAny(lower, "配置", "application.yml", "application.properties", "pom.xml", ".env", "yaml", "properties")) {
            return CONFIG;
        }
        if (containsAny(lower, "调用链", "链路", "从", "到", "controller", "service", "mapper", "dao")) {
            return CALL_CHAIN;
        }
        return GENERAL;
    }

    private static boolean conflicts(CodeSearchMode requested, CodeSearchMode inferred) {
        if (inferred == GENERAL) {
            return false;
        }
        if (requested == GENERAL) {
            return true;
        }
        return requested != inferred;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
