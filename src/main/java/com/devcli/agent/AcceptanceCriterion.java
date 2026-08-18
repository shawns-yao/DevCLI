package com.devcli.agent;

import java.util.Locale;
import java.util.List;
import java.util.Objects;

/** Team 计划中可判定的验收标准。 */
record AcceptanceCriterion(
        String id,
        String category,
        String description,
        String testSignal,
        String severity,
        VerificationMethod verificationMethod,
        String verifier,
        List<String> appliesTo
) {
    enum VerificationMethod {
        TOOL,
        HUMAN;

        static VerificationMethod parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    AcceptanceCriterion {
        id = normalize(id);
        category = normalize(category);
        description = normalize(description);
        testSignal = normalize(testSignal);
        severity = normalize(severity);
        verifier = normalize(verifier);
        appliesTo = appliesTo == null ? List.of() : appliesTo.stream()
                .map(AcceptanceCriterion::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    AcceptanceCriterion(String id, String category, String description, String testSignal,
                        String severity, VerificationMethod verificationMethod, String verifier) {
        this(id, category, description, testSignal, severity, verificationMethod, verifier,
                List.of("FINAL"));
    }

    boolean isValid() {
        return !id.isBlank() && !description.isBlank();
    }

    String formatForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(id);
        if (!category.isBlank()) {
            sb.append(" [").append(category).append(']');
        }
        if (!severity.isBlank()) {
            sb.append(" severity=").append(severity);
        }
        sb.append(": ").append(description);
        if (verificationMethod != null) {
            sb.append("；verification_method=").append(verificationMethod);
        }
        if (!verifier.isBlank()) {
            sb.append("；verifier=").append(verifier);
        }
        if (!testSignal.isBlank()) {
            sb.append("；test_signal=").append(testSignal);
        }
        if (!appliesTo.isEmpty()) {
            sb.append("；applies_to=").append(appliesTo);
        }
        return sb.toString();
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim();
    }
}
