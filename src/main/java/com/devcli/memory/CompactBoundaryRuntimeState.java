package com.devcli.memory;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 压缩边界生成时的运行时状态快照。
 */
public record CompactBoundaryRuntimeState(
        List<String> loadedSkills,
        String ragEpoch,
        String mcpToolSnapshot,
        boolean postCompactRestoreEnabled
) {
    public static final CompactBoundaryRuntimeState EMPTY =
            new CompactBoundaryRuntimeState(List.of(), "none", "none", false);

    public CompactBoundaryRuntimeState {
        loadedSkills = normalizeList(loadedSkills);
        ragEpoch = normalizeText(ragEpoch);
        mcpToolSnapshot = normalizeText(mcpToolSnapshot);
    }

    public CompactBoundaryRuntimeState withPostCompactRestoreEnabled(boolean enabled) {
        if (postCompactRestoreEnabled == enabled) {
            return this;
        }
        return new CompactBoundaryRuntimeState(loadedSkills, ragEpoch, mcpToolSnapshot, enabled);
    }

    public static String mergeRagEpochSnapshots(String... snapshots) {
        if (snapshots == null || snapshots.length == 0) {
            return "none";
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String snapshot : snapshots) {
            if (snapshot == null || snapshot.isBlank()) {
                continue;
            }
            for (String part : snapshot.split(",")) {
                String epoch = part.trim();
                if (!epoch.isBlank() && !"none".equalsIgnoreCase(epoch)) {
                    merged.add(epoch);
                }
            }
        }
        return merged.isEmpty() ? "none" : String.join(", ", merged);
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim().replaceAll("\\R+", " "));
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.trim().replaceAll("\\R+", " ");
    }
}
