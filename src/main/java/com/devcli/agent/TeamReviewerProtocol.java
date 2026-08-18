package com.devcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-Agent Reviewer 输出协议边界。
 *
 * <p>集中负责 JSON 解析、评分阈值、验收点覆盖和问题摘要，避免协议规则散落在编排器中。
 */
final class TeamReviewerProtocol {
    private static final Logger log = LoggerFactory.getLogger(TeamReviewerProtocol.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double MIN_REVIEW_SCORE = 0.6;
    private static final double REQUIRED_FUNCTIONAL_SCORE = 1.0;

    record Criterion(String id, String severity, String verificationMethod, String verifier) {
        Criterion {
            id = id == null ? "" : id;
            severity = severity == null ? "" : severity;
            verificationMethod = verificationMethod == null ? "" : verificationMethod;
            verifier = verifier == null ? "" : verifier;
        }

        Criterion(String id, String severity, String verificationMethod) {
            this(id, severity, verificationMethod, "");
        }

        Criterion(String id, String severity) {
            this(id, severity, "", "");
        }
    }

    record Evaluation(boolean approved, String issues) {
    }

    private TeamReviewerProtocol() {
    }

    static Evaluation evaluate(String reviewContent, List<Criterion> plannedCriteria) {
        return evaluate(reviewContent, plannedCriteria, null);
    }

    static Evaluation evaluate(String reviewContent, List<Criterion> plannedCriteria,
                               List<String> observedSuccessfulTools) {
        List<Criterion> criteria = plannedCriteria == null ? List.of() : List.copyOf(plannedCriteria);
        if (reviewContent == null || reviewContent.isBlank()) {
            log.warn("Reviewer returned empty content, defaulting to rejected");
            return new Evaluation(false, "审查未通过，请改进执行结果");
        }
        try {
            JsonNode root = MAPPER.readTree(clean(reviewContent));
            List<String> missingVerifiers = missingObservedVerifiers(
                    root, criteria, observedSuccessfulTools);
            if (!missingVerifiers.isEmpty()) {
                return new Evaluation(false,
                        "Reviewer 缺少声明验证器的真实成功证据: "
                                + String.join(", ", missingVerifiers));
            }
            return new Evaluation(parseApproval(root, criteria), parseIssues(root));
        } catch (Exception e) {
            log.warn("Reviewer output is not valid JSON, defaulting to rejected");
            return new Evaluation(false, "审查未通过，请改进执行结果");
        }
    }

    private static List<String> missingObservedVerifiers(
            JsonNode root, List<Criterion> criteria, List<String> observedSuccessfulTools) {
        if (!root.path("approved").asBoolean(false) || observedSuccessfulTools == null) {
            return List.of();
        }
        Set<String> observed = new HashSet<>(observedSuccessfulTools);
        return criteria.stream()
                .filter(criterion -> "TOOL".equalsIgnoreCase(criterion.verificationMethod()))
                .map(Criterion::verifier)
                .filter(verifier -> verifier.isBlank() || !observed.contains(verifier))
                .distinct()
                .toList();
    }

    private static boolean parseApproval(JsonNode root, List<Criterion> plannedCriteria) {
        JsonNode approvedNode = root.path("approved");
        if (approvedNode.isMissingNode() || approvedNode.isNull()) {
            log.warn("Reviewer JSON missing 'approved' field, defaulting to rejected");
            return false;
        }
        if (!approvedNode.asBoolean(false)) {
            return false;
        }
        JsonNode criteriaResults = root.path("criteria_results");
        if (hasInvalidVerificationResults(criteriaResults, plannedCriteria)) {
            log.warn("Reviewer JSON violates planned verification methods, defaulting to rejected");
            return false;
        }
        if (hasFailedBlockingCriteria(criteriaResults, plannedCriteria)) {
            log.warn("Reviewer approved despite failed blocking acceptance criteria, defaulting to rejected");
            return false;
        }
        if (hasMissingAcceptanceCriteriaCoverage(criteriaResults, plannedCriteria)) {
            log.warn("Reviewer JSON missing acceptance criteria coverage, defaulting to rejected");
            return false;
        }
        JsonNode scores = root.path("scores");
        if (!scores.isObject()) {
            log.warn("Reviewer JSON missing structured scores, defaulting to rejected");
            return false;
        }
        double functional = scores.path("functional_correctness").asDouble(-1.0);
        double integration = scores.path("integration_completeness").asDouble(-1.0);
        double quality = scores.path("code_quality").asDouble(-1.0);
        if (functional < REQUIRED_FUNCTIONAL_SCORE) {
            log.warn("Reviewer functional_correctness score {} below required {}",
                    functional, REQUIRED_FUNCTIONAL_SCORE);
            return false;
        }
        if (integration < MIN_REVIEW_SCORE || quality < MIN_REVIEW_SCORE) {
            log.warn("Reviewer scores below threshold: integration={}, quality={}, threshold={}",
                    integration, quality, MIN_REVIEW_SCORE);
            return false;
        }
        return true;
    }

    private static boolean hasInvalidVerificationResults(
            JsonNode results, List<Criterion> plannedCriteria) {
        if (plannedCriteria.isEmpty()) {
            return false;
        }
        if (results == null || !results.isArray()) {
            return true;
        }
        for (Criterion criterion : plannedCriteria) {
            if (criterion.verificationMethod().isBlank()) {
                continue;
            }
            JsonNode result = findResult(results, criterion.id());
            if (result == null) {
                return true;
            }
            String plannedMethod = criterion.verificationMethod().trim().toUpperCase(Locale.ROOT);
            String reportedMethod = result.path("verification_method").asText("")
                    .trim().toUpperCase(Locale.ROOT);
            if (!plannedMethod.equals(reportedMethod)) {
                return true;
            }
            if (result.path("evidence").asText("").isBlank()) {
                return true;
            }
            if ("HUMAN".equals(plannedMethod)) {
                String status = result.path("status").asText("").trim().toLowerCase(Locale.ROOT);
                if (!"pending_human".equals(status) || result.path("passed").asBoolean(false)) {
                    return true;
                }
            } else if ("TOOL".equals(plannedMethod)
                    && !result.path("passed").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode findResult(JsonNode results, String criterionId) {
        for (JsonNode result : results) {
            if (criterionId.equals(result.path("id").asText(""))) {
                return result;
            }
        }
        return null;
    }

    private static boolean hasFailedBlockingCriteria(JsonNode results, List<Criterion> plannedCriteria) {
        if (results == null || !results.isArray()) {
            return false;
        }
        for (JsonNode result : results) {
            if (result.path("passed").asBoolean(false)) {
                continue;
            }
            String id = result.path("id").asText("");
            Criterion planned = plannedCriterionFor(id, plannedCriteria);
            if (planned != null
                    && "HUMAN".equalsIgnoreCase(planned.verificationMethod())
                    && "pending_human".equalsIgnoreCase(result.path("status").asText(""))) {
                continue;
            }
            String reportedSeverity = result.path("severity").asText("");
            if (isBlockingSeverity(reportedSeverity)
                    || isBlockingSeverity(plannedSeverityFor(id, plannedCriteria))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMissingAcceptanceCriteriaCoverage(JsonNode results,
                                                                List<Criterion> plannedCriteria) {
        if (plannedCriteria.isEmpty()) {
            return false;
        }
        if (results == null || !results.isArray() || results.isEmpty()) {
            return true;
        }
        Set<String> coveredIds = new HashSet<>();
        for (JsonNode result : results) {
            String id = result.path("id").asText("");
            if (!id.isBlank()) {
                coveredIds.add(id);
            }
        }
        return plannedCriteria.stream()
                .map(Criterion::id)
                .filter(id -> !id.isBlank())
                .anyMatch(id -> !coveredIds.contains(id));
    }

    private static String plannedSeverityFor(String criterionId, List<Criterion> plannedCriteria) {
        if (criterionId == null || criterionId.isBlank()) {
            return "";
        }
        return plannedCriteria.stream()
                .filter(criterion -> criterion.id().equals(criterionId))
                .map(Criterion::severity)
                .findFirst()
                .orElse("");
    }

    private static Criterion plannedCriterionFor(String criterionId, List<Criterion> plannedCriteria) {
        if (criterionId == null || criterionId.isBlank()) {
            return null;
        }
        return plannedCriteria.stream()
                .filter(criterion -> criterion.id().equals(criterionId))
                .findFirst()
                .orElse(null);
    }

    private static boolean isBlockingSeverity(String severity) {
        String normalized = severity == null ? "" : severity.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("critical") || normalized.equals("high");
    }

    private static String parseIssues(JsonNode root) {
        String criteriaIssues = formatFailedCriteriaResults(root.path("criteria_results"));
        String issues = formatIssueArray(root.path("issues"));
        if (issues.isBlank()) {
            issues = formatIssueArray(root.path("suggestions"));
        }
        if (!criteriaIssues.isBlank()) {
            issues = issues.isBlank() ? criteriaIssues : issues + "\n" + criteriaIssues;
        }
        if (!issues.isBlank()) {
            return issues.trim();
        }
        String summary = root.path("summary").asText("");
        return summary.isBlank() ? "审查未通过，请改进执行结果" : summary;
    }

    private static String formatIssueArray(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode value : values) {
            sb.append("- ").append(formatReviewIssue(value)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatFailedCriteriaResults(JsonNode results) {
        if (results == null || !results.isArray() || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode result : results) {
            if (result.path("passed").asBoolean(false)) {
                continue;
            }
            String id = result.path("id").asText("");
            String severity = result.path("severity").asText("");
            String evidence = result.path("evidence").asText("");
            sb.append("- 验收失败");
            if (!id.isBlank()) {
                sb.append(' ').append(id);
            }
            if (!severity.isBlank()) {
                sb.append(" severity=").append(severity);
            }
            if (!evidence.isBlank()) {
                sb.append(": ").append(evidence);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatReviewIssue(JsonNode issue) {
        if (issue == null || issue.isNull()) {
            return "";
        }
        if (!issue.isObject()) {
            return issue.asText();
        }
        List<String> parts = new ArrayList<>();
        String criterionId = issue.path("criterion_id").asText("");
        String type = issue.path("type").asText("");
        String severity = issue.path("severity").asText("");
        String file = issue.path("file").asText("");
        String description = issue.path("description").asText("");
        String expected = issue.path("expected").asText("");
        String actual = issue.path("actual").asText("");
        String suggestedFix = issue.path("suggested_fix").asText("");
        if (!criterionId.isBlank()) {
            parts.add("criterion=" + criterionId);
        }
        if (!type.isBlank()) {
            parts.add("type=" + type);
        }
        if (!severity.isBlank()) {
            parts.add("severity=" + severity);
        }
        if (!description.isBlank()) {
            parts.add(description);
        }
        if (!file.isBlank()) {
            parts.add("file=" + file);
        }
        if (!expected.isBlank()) {
            parts.add("expected=" + expected);
        }
        if (!actual.isBlank()) {
            parts.add("actual=" + actual);
        }
        if (!suggestedFix.isBlank()) {
            parts.add("suggested_fix=" + suggestedFix);
        }
        return parts.isEmpty() ? issue.toString() : String.join(", ", parts);
    }

    private static String clean(String content) {
        return content.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
    }
}
