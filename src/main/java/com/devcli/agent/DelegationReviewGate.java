package com.devcli.agent;

import java.util.List;
import java.util.Locale;

/** 默认委派路径的确定性独立 Reviewer 触发器。 */
final class DelegationReviewGate {
    private static final int LARGE_PATCH_FILE_THRESHOLD = 3;

    private DelegationReviewGate() {
    }

    static boolean requiresIndependentReview(Signals signals) {
        if (signals == null) {
            return false;
        }
        if (signals.everHadMutationFailure()) {
            return true;
        }
        if (signals.modifiedResources().size() >= LARGE_PATCH_FILE_THRESHOLD) {
            return true;
        }
        return signals.modifiedResources().stream().anyMatch(DelegationReviewGate::isCriticalResource);
    }

    static boolean isCriticalResource(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/migration/")
                || normalized.contains("/migrations/")
                || normalized.contains("schema")
                || normalized.contains("security")
                || normalized.contains("authentication")
                || normalized.contains("authorization")
                || normalized.contains("/auth/")
                || normalized.endsWith("securityconfig.java")
                || normalized.endsWith("application.yml")
                || normalized.endsWith("application.yaml")
                || normalized.endsWith("application.properties");
    }

    record Signals(List<String> modifiedResources, boolean everHadMutationFailure) {
        Signals {
            modifiedResources = modifiedResources == null ? List.of() : modifiedResources.stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(path -> path.replace('\\', '/'))
                    .distinct()
                    .toList();
        }
    }
}
