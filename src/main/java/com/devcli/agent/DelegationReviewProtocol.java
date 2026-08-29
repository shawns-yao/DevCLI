package com.devcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 委派 Reviewer 的轻量协议；只阻断 critical/high，normal 进入旁路建议。 */
final class DelegationReviewProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DelegationReviewProtocol() {
    }

    static Decision evaluate(String content) {
        if (content == null || content.isBlank()) {
            return Decision.invalid("Reviewer 未返回可解析结果");
        }
        try {
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                return Decision.invalid("Reviewer 结果不是 JSON 对象");
            }
            if (!root.has("approved")) {
                JsonNode nested = root.path("summary");
                if (nested.isTextual() && !nested.asText().isBlank()) {
                    try {
                        JsonNode parsed = JSON.readTree(nested.asText());
                        if (parsed != null && parsed.isObject() && parsed.has("approved")) {
                            root = parsed;
                        }
                    } catch (Exception ignored) {
                        // fall through to the protocol error below
                    }
                }
            }
            if (!root.has("approved")) {
                return Decision.invalid("Reviewer 结果缺少 approved 字段");
            }
            List<String> blocking = new ArrayList<>();
            List<String> advisories = new ArrayList<>();
            JsonNode issues = root.path("issues");
            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    String description = issue.isObject()
                            ? issue.path("description").asText("") : issue.asText("");
                    if (description.isBlank()) {
                        continue;
                    }
                    String severity = issue.isObject()
                            ? issue.path("severity").asText("normal") : "normal";
                    if (isBlocking(severity)) {
                        blocking.add(description);
                    } else {
                        advisories.add(description);
                    }
                }
            }
            boolean approved = blocking.isEmpty();
            if (!approved && root.path("approved").asBoolean(false)) {
                return new Decision(false, true, "Reviewer 声明通过但存在 critical/high 问题",
                        blocking, advisories);
            }
            String summary = root.path("summary").asText("");
            return new Decision(approved, true, summary, blocking, advisories);
        } catch (Exception e) {
            return Decision.invalid("Reviewer 返回不是合法 JSON");
        }
    }

    private static boolean isBlocking(String severity) {
        String normalized = severity == null ? "" : severity.trim().toLowerCase(Locale.ROOT);
        return "critical".equals(normalized) || "high".equals(normalized);
    }

    record Decision(boolean approved, boolean protocolValid, String summary,
                    List<String> blockingIssues, List<String> advisoryIssues) {
        Decision {
            summary = summary == null ? "" : summary;
            blockingIssues = blockingIssues == null ? List.of() : List.copyOf(blockingIssues);
            advisoryIssues = advisoryIssues == null ? List.of() : List.copyOf(advisoryIssues);
        }

        boolean hasBlockingIssue() {
            return !blockingIssues.isEmpty();
        }

        int advisories() {
            return advisoryIssues.size();
        }

        static Decision invalid(String message) {
            return new Decision(false, false, message, List.of(), List.of());
        }
    }
}
