package com.devcli.memory;

import java.util.Locale;
import java.util.Optional;

/** 对少量可确定的规则声明与当前项目状态做冲突标记，不擅自裁决规则。 */
final class RuleCurrentStateConflictDetector {

    private RuleCurrentStateConflictDetector() {
    }

    static Optional<String> detect(String ruleContext, MemoryObservationConflictDetector.Observation observation) {
        if (ruleContext == null || ruleContext.isBlank() || observation == null
                || observation.strength() != CurrentStateObservationSideChannel.ObservationStrength.HIGH
                || !"project.build_system".equals(observation.subject())) {
            return Optional.empty();
        }
        String rules = ruleContext.toLowerCase(Locale.ROOT);
        String required = requiredBuildSystem(rules);
        if (required.isBlank() || required.equals(observation.value())) {
            return Optional.empty();
        }
        return Optional.of("规则与当前状态冲突：规则要求使用 " + display(required)
                + "，但高置信项目证据表明当前使用 " + display(observation.value())
                + "（" + observation.evidence() + "）。不得静默选择任一方；涉及构建系统的修改前必须请求用户裁决。");
    }

    private static String requiredBuildSystem(String rules) {
        if (requires(rules, "maven") || requires(rules, "mvn")) {
            return "maven";
        }
        if (requires(rules, "gradle") || requires(rules, "gradlew")) {
            return "gradle";
        }
        return "";
    }

    private static boolean requires(String rules, String tool) {
        return rules.contains("必须使用 " + tool)
                || rules.contains("必须用 " + tool)
                || rules.contains("要求使用 " + tool)
                || rules.contains("must use " + tool);
    }

    private static String display(String value) {
        return "maven".equals(value) ? "Maven" : "Gradle";
    }
}
