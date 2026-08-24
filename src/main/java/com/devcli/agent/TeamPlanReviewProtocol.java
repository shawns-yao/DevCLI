package com.devcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Team 计划在 Worker 启动前的语义评审协议。 */
final class TeamPlanReviewProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Evaluation(boolean approved, boolean protocolValid, String summary, String issues) {
        Evaluation {
            summary = summary == null ? "" : summary.trim();
            issues = issues == null ? "" : issues.trim();
        }

        static Evaluation skipped() {
            return new Evaluation(true, true, "计划语义评审未启用", "");
        }
    }

    private TeamPlanReviewProtocol() {
    }

    static Evaluation evaluate(String content, List<String> criterionIds, Set<String> stepIds) {
        return evaluate(content, criterionIds, stepIds, Set.of());
    }

    static Evaluation evaluate(String content, List<String> criterionIds, Set<String> stepIds,
                               Set<String> counterexampleRequiredCriterionIds) {
        if (content == null || content.isBlank()) {
            return new Evaluation(false, false, "", "计划评审结果为空");
        }
        try {
            JsonNode root = MAPPER.readTree(clean(content));
            String summary = root.path("summary").asText("");
            List<String> problems = new ArrayList<>();
            validateRequirementCoverage(root.path("requirement_coverage"),
                    safeSet(criterionIds), safeSet(stepIds), problems);
            validateCriteriaReviews(root.path("criteria_reviews"),
                    safeSet(criterionIds), problems);
            validateCounterexamples(root.path("counterexamples"),
                    safeSet(counterexampleRequiredCriterionIds), safeSet(stepIds), problems);
            appendReportedIssues(root.path("issues"), problems);
            if (!root.path("approved").asBoolean(false) && problems.isEmpty()) {
                problems.add(summary.isBlank() ? "计划语义评审未通过" : summary);
            }
            boolean approved = root.path("approved").asBoolean(false) && problems.isEmpty();
            return new Evaluation(approved, true, summary, String.join("\n", problems));
        } catch (Exception error) {
            return new Evaluation(false, false, "", "计划评审输出不是有效 JSON");
        }
    }

    private static void validateCounterexamples(JsonNode counterexamples,
                                                Set<String> requiredCriterionIds,
                                                Set<String> stepIds,
                                                List<String> problems) {
        if (requiredCriterionIds.isEmpty()) return;
        if (!counterexamples.isArray()) {
            problems.add("计划评审缺少关键验收标准的反例生成结果");
            return;
        }
        Set<String> covered = new HashSet<>();
        for (JsonNode counterexample : counterexamples) {
            String criterionId = counterexample.path("criterion_id").asText("").trim();
            if (!requiredCriterionIds.contains(criterionId)) continue;
            boolean concrete = !counterexample.path("input").asText("").isBlank()
                    && !counterexample.path("expected_failure_signal").asText("").isBlank();
            if (!concrete) {
                problems.add("关键验收标准反例不具体: " + criterionId);
                continue;
            }
            int before = problems.size();
            validateReferences(counterexample.path("step_ids"), stepIds,
                    "关键验收标准反例缺少有效执行节点: " + criterionId, problems);
            if (problems.size() == before) covered.add(criterionId);
        }
        for (String criterionId : requiredCriterionIds) {
            if (!covered.contains(criterionId)) {
                problems.add("计划评审遗漏关键验收标准反例: " + criterionId);
            }
        }
    }

    private static void validateRequirementCoverage(JsonNode coverage,
                                                    Set<String> criterionIds,
                                                    Set<String> stepIds,
                                                    List<String> problems) {
        if (!coverage.isArray() || coverage.isEmpty()) {
            problems.add("计划评审缺少需求覆盖结果");
            return;
        }
        for (JsonNode item : coverage) {
            String requirement = item.path("requirement").asText("").trim();
            String label = requirement.isBlank() ? "未命名需求" : requirement;
            if (!"covered".equalsIgnoreCase(item.path("status").asText(""))) {
                problems.add("需求覆盖不足: " + label);
            }
            validateReferences(item.path("step_ids"), stepIds,
                    "需求缺少有效执行节点: " + label, problems);
            validateReferences(item.path("criterion_ids"), criterionIds,
                    "需求缺少有效验收标准: " + label, problems);
        }
    }

    private static void validateCriteriaReviews(JsonNode reviews,
                                                Set<String> criterionIds,
                                                List<String> problems) {
        if (!reviews.isArray()) {
            problems.add("计划评审缺少验收标准逐条评审");
            return;
        }
        Set<String> covered = new HashSet<>();
        for (JsonNode review : reviews) {
            String id = review.path("id").asText("").trim();
            if (!id.isBlank()) {
                covered.add(id);
            }
            boolean valid = review.path("clear").asBoolean(false)
                    && review.path("verifiable").asBoolean(false)
                    && review.path("scope_valid").asBoolean(false)
                    && !review.path("evidence").asText("").isBlank();
            if (!valid) {
                problems.add("验收标准不清晰、不可验证或作用范围错误: "
                        + (id.isBlank() ? "unknown" : id));
            }
        }
        for (String id : criterionIds) {
            if (!covered.contains(id)) {
                problems.add("计划评审遗漏验收标准: " + id);
            }
        }
    }

    private static void validateReferences(JsonNode values, Set<String> allowed,
                                           String message, List<String> problems) {
        if (!values.isArray() || values.isEmpty()) {
            problems.add(message);
            return;
        }
        for (JsonNode value : values) {
            if (!allowed.contains(value.asText(""))) {
                problems.add(message);
                return;
            }
        }
    }

    private static void appendReportedIssues(JsonNode issues, List<String> problems) {
        if (!issues.isArray()) {
            problems.add("计划评审缺少 issues 数组");
            return;
        }
        for (JsonNode issue : issues) {
            if (!issue.isObject()) {
                String text = issue.asText("").trim();
                if (!text.isBlank()) {
                    problems.add(text);
                }
                continue;
            }
            List<String> parts = new ArrayList<>();
            add(parts, issue, "type");
            add(parts, issue, "severity");
            add(parts, issue, "requirement");
            add(parts, issue, "description");
            add(parts, issue, "suggested_fix");
            problems.add(parts.isEmpty() ? issue.toString() : String.join(", ", parts));
        }
    }

    private static void add(List<String> parts, JsonNode issue, String field) {
        String value = issue.path(field).asText("").trim();
        if (!value.isBlank()) {
            parts.add(field + "=" + value);
        }
    }

    private static Set<String> safeSet(Iterable<String> values) {
        Set<String> result = new HashSet<>();
        if (values != null) {
            values.forEach(value -> {
                if (value != null && !value.isBlank()) {
                    result.add(value);
                }
            });
        }
        return result;
    }

    private static String clean(String content) {
        return content.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
    }
}
