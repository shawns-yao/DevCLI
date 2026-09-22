package com.devcli.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic content inspection; findings are hints, not a complete DLP guarantee. */
public final class SensitiveContentPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----.*?(?:-----END (?:[A-Z0-9]+ )*PRIVATE KEY-----|\\z)");
    private static final Pattern API_TOKEN = Pattern.compile(
            "\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,}|"
                    + "github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\\b");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(?:.*[_-])?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|"
                    + "passwd|secret|client[_-]?secret|authorization|cookie|set-cookie)");

    private SensitiveContentPolicy() { }

    public enum Decision { ALLOW_ONCE, REDACT, REJECT }

    public record Inspection(String sanitized, Set<String> types) {
        public Inspection {
            types = Set.copyOf(types);
        }
        public boolean sensitive() { return !types.isEmpty(); }
        public String typeLabels() {
            return types.stream().sorted().map(type -> switch (type) {
                case "credential" -> "凭据或令牌";
                case "private_key" -> "私钥";
                case "account" -> "账号";
                case "id_card" -> "身份证号";
                case "phone" -> "手机号";
                case "bank_card" -> "银行卡号";
                case "address" -> "地址";
                case "medical" -> "医疗信息";
                case "file_upload" -> "文件上传内容（未检查）";
                default -> "敏感信息";
            }).collect(java.util.stream.Collectors.joining("、"));
        }
    }

    public static Inspection inspect(String text) {
        String raw = text == null ? "" : text;
        Set<String> types = new LinkedHashSet<>();
        var common = SensitiveDataRedactor.inspect(raw);
        String sanitized = common.sanitizedText();
        if (!raw.equals(sanitized)) types.addAll(common.removedTypes());
        if (PRIVATE_KEY.matcher(raw).find()) types.add("private_key");
        if (API_TOKEN.matcher(raw).find()) types.add("credential");
        sanitized = PRIVATE_KEY.matcher(sanitized).replaceAll("[REDACTED_PRIVATE_KEY]");
        sanitized = API_TOKEN.matcher(sanitized).replaceAll("[REDACTED_CREDENTIAL]");
        return new Inspection(sanitized, types);
    }

    public static Inspection inspectArguments(JsonNode arguments) {
        Set<String> types = new LinkedHashSet<>();
        JsonNode sanitized = sanitizeNode(arguments, "", types);
        return new Inspection(sanitized.toString(), types);
    }

    private static JsonNode sanitizeNode(JsonNode node, String field, Set<String> types) {
        if (node.isObject()) {
            ObjectNode copy = JSON.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    copy.set(entry.getKey(), sanitizeNode(entry.getValue(), entry.getKey(), types)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JSON.createArrayNode();
            node.forEach(item -> copy.add(sanitizeNode(item, field, types)));
            return copy;
        }
        if (SECRET_FIELD.matcher(field).matches() && !node.isNull() && !node.asText().isBlank()) {
            types.add("credential");
            return TextNode.valueOf("[REDACTED_CREDENTIAL]");
        }
        if (!node.isTextual()) return node;
        String raw = node.asText();
        Inspection found = inspect(raw);
        types.addAll(found.types());
        String safe = found.sanitized();
        // Decoding is for detection only. Never change routing or structured payload semantics.
        if (raw.contains("%")) {
            try {
                Inspection decoded = inspect(URLDecoder.decode(raw, StandardCharsets.UTF_8));
                if (decoded.sensitive()) {
                    types.addAll(decoded.types());
                    safe = "[REDACTED_ENCODED_CONTENT]";
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid encoded data remains subject to the tool's own validation.
            }
        }
        return TextNode.valueOf(safe);
    }

    public static String safeDisplayArguments(String arguments) {
        try {
            String raw = arguments == null ? "{}" : arguments;
            Inspection inspection = inspectArguments(JSON.readTree(raw));
            return inspection.sensitive() ? inspection.sanitized() : raw;
        } catch (Exception ignored) {
            return "[参数无法安全展示]";
        }
    }

    public static String hostOnly(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "目标主机未知" : inspect(uri.getHost()).sanitized();
        } catch (Exception ignored) {
            return "目标主机未知";
        }
    }
}
