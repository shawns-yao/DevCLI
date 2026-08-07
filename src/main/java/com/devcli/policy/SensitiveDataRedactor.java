package com.devcli.policy;

/** 本机日志、审计和会话归档共用的敏感文本脱敏器。 */
public final class SensitiveDataRedactor {
    private SensitiveDataRedactor() {
    }

    public static String redact(String text) {
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
