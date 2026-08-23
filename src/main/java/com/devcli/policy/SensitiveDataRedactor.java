package com.devcli.policy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 本机日志、审计、会话归档与长期记忆共用的敏感文本脱敏器。 */
public final class SensitiveDataRedactor {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:token|api[_-]?key|key|password|secret|authorization)\\b\\s*[:=]");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s\"'}]+");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\bauthorization\\b\\s*[:=]\\s*+)(?!Bearer\\b)([^\\r\\n,，;；}]+)");
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?i)(\"?(?:账号|账户|用户名|\\baccount|\\busername)\"?"
                    + "\\s*[:=：是]\\s*\"?)([^\"\\s,，;；}]+)(\"?)");
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{13,19}\\b");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)(\"?(?:住址|收货地址|家庭地址|home\\s+address|shipping\\s+address)\"?"
                    + "\\s*[:=：是]\\s*\"?)([^\",，;；\\n}]+)(\"?)");
    private static final Pattern MEDICAL = Pattern.compile(
            "(?i)(\"?(?:病历|医疗诊断|诊断结果|medical\\s+record|medical\\s+diagnosis)\"?"
                    + "\\s*[:=：是]\\s*\"?)([^\",，;；\\n}]+)(\"?)");

    private SensitiveDataRedactor() {
    }

    public static String redact(String text) {
        return inspect(text).sanitizedText();
    }

    public static RedactionResult inspect(String text) {
        if (text == null) {
            return new RedactionResult(null, Set.of(), "low");
        }
        Set<String> removedTypes = new LinkedHashSet<>();
        detect(removedTypes, "credential", CREDENTIAL, text);
        detect(removedTypes, "credential", BEARER, text);
        detect(removedTypes, "account", ACCOUNT, text);
        detect(removedTypes, "id_card", ID_CARD, text);
        detect(removedTypes, "phone", PHONE, text);
        detect(removedTypes, "bank_card", BANK_CARD, text);
        detect(removedTypes, "address", ADDRESS, text);
        detect(removedTypes, "medical", MEDICAL, text);

        String sanitized = BEARER.matcher(text).replaceAll("Bearer ***");
        sanitized = AUTHORIZATION.matcher(sanitized).replaceAll("$1***");
        sanitized = sanitized.replaceAll(
                "(?i)(\"?(?:token|api[_-]?key|key|password|secret)\"?"
                        + "\\s*[:=]\\s*\")([^\"]+)(\")",
                "$1***$3");
        sanitized = sanitized.replaceAll(
                "(?i)(\\b(?:token|api[_-]?key|key|password|secret)\\b"
                        + "\\s*[:=]\\s*)([^\\s,，;；}]+)",
                "$1***");
        sanitized = ACCOUNT.matcher(sanitized).replaceAll("$1***$3");
        sanitized = ID_CARD.matcher(sanitized).replaceAll("[REDACTED_ID_CARD]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[REDACTED_PHONE]");
        sanitized = BANK_CARD.matcher(sanitized).replaceAll("[REDACTED_BANK_CARD]");
        sanitized = ADDRESS.matcher(sanitized).replaceAll("$1[REDACTED_ADDRESS]$3");
        sanitized = MEDICAL.matcher(sanitized).replaceAll("$1[REDACTED_MEDICAL]$3");

        String sensitivity = removedTypes.contains("credential") || removedTypes.contains("id_card")
                ? "high"
                : removedTypes.isEmpty() ? "low" : "medium";
        return new RedactionResult(sanitized, removedTypes, sensitivity);
    }

    private static void detect(Set<String> types, String type, Pattern pattern, String text) {
        if (pattern.matcher(text).find()) {
            types.add(type);
        }
    }

    public record RedactionResult(String sanitizedText, Set<String> removedTypes, String sensitivity) {
        public RedactionResult {
            removedTypes = removedTypes == null ? Set.of() : Set.copyOf(removedTypes);
        }

        public boolean changed() {
            return !removedTypes.isEmpty();
        }

        public String removedTypesCsv() {
            return String.join(",", List.copyOf(removedTypes));
        }

        public boolean removed(String type) {
            return type != null && removedTypes.contains(type);
        }
    }
}
