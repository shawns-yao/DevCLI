package com.devcli.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** 在创建 checkpoint 和执行 DAG 前验证验收标准是否可判定。 */
final class AcceptanceCriteriaPreflight {

    record Issue(String criterionId, String message) {
        Issue {
            criterionId = criterionId == null ? "" : criterionId;
            message = message == null ? "" : message;
        }

        String describe() {
            return criterionId.isBlank() ? message : criterionId + ": " + message;
        }
    }

    record Report(boolean executable, boolean requiresHumanReview, List<Issue> issues) {
        Report {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        String describeIssues() {
            return issues.stream().map(Issue::describe).reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
    }

    private AcceptanceCriteriaPreflight() {
    }

    static Report validate(List<AcceptanceCriterion> criteria, Predicate<String> toolExists) {
        return validate(criteria, toolExists, Set.of("FINAL"));
    }

    static Report validate(List<AcceptanceCriterion> criteria, Predicate<String> toolExists,
                           Set<String> validTargets) {
        List<AcceptanceCriterion> safeCriteria = criteria == null ? List.of() : criteria;
        Predicate<String> safeToolExists = toolExists == null ? ignored -> false : toolExists;
        Set<String> safeTargets = new HashSet<>(validTargets == null ? Set.of() : validTargets);
        safeTargets.add("FINAL");
        List<Issue> issues = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        boolean requiresHumanReview = false;

        if (safeCriteria.isEmpty()) {
            issues.add(new Issue("", "acceptance_criteria 不能为空"));
        }
        for (AcceptanceCriterion criterion : safeCriteria) {
            if (criterion == null) {
                issues.add(new Issue("", "验收标准不能为空"));
                continue;
            }
            String id = criterion.id();
            if (id.isBlank()) {
                issues.add(new Issue("", "验收标准 id 不能为空"));
            } else if (!ids.add(id)) {
                issues.add(new Issue(id, "验收标准 id 重复"));
            }
            if (criterion.description().isBlank()) {
                issues.add(new Issue(id, "description 不能为空"));
            }
            if (criterion.testSignal().isBlank()) {
                issues.add(new Issue(id, "test_signal 不能为空，必须声明预期证据"));
            }
            if (criterion.verificationMethod() == null) {
                issues.add(new Issue(id, "verification_method 必须是 TOOL 或 HUMAN"));
                continue;
            }
            if (criterion.verifier().isBlank()) {
                issues.add(new Issue(id, "verifier 不能为空"));
                continue;
            }
            if (criterion.verificationMethod() == AcceptanceCriterion.VerificationMethod.TOOL
                    && !safeToolExists.test(criterion.verifier())) {
                issues.add(new Issue(id, "验证工具不存在或具有副作用: " + criterion.verifier()));
            }
            if (criterion.verificationMethod() == AcceptanceCriterion.VerificationMethod.HUMAN) {
                requiresHumanReview = true;
            }
            if (criterion.appliesTo().isEmpty()) {
                issues.add(new Issue(id, "applies_to 不能为空，必须关联 DAG 节点或 FINAL"));
            } else {
                for (String target : criterion.appliesTo()) {
                    if (!safeTargets.contains(target)) {
                        issues.add(new Issue(id, "applies_to 引用了不存在的 DAG 节点: " + target));
                    }
                }
            }
        }
        return new Report(issues.isEmpty(), requiresHumanReview, issues);
    }
}
