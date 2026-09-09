package com.devcli.memory;

/** Deterministically repairs a summary by appending facts omitted by the model. */
public final class CompactionRepairer {
    public String repair(String summary, CompactionConsistencyValidator.Validation validation) {
        StringBuilder result = new StringBuilder(summary == null ? "" : summary);
        if (validation == null || validation.missing().isEmpty()) return result.toString();
        result.append("\n\n## 精确事实（自动保留）\n");
        validation.missing().forEach(f -> result.append("- ").append(f.type())
                .append(": ").append(f.value()).append('\n'));
        return result.toString();
    }
}
