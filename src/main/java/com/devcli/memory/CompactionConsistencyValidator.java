package com.devcli.memory;

import java.util.List;

/** Compares semantic summaries with the deterministic fact ledger. */
public final class CompactionConsistencyValidator {
    public record Validation(boolean valid, List<CompactionFactLedger.Fact> missing,
                              List<CompactionFactLedger.Fact> covered,
                              List<CompactionFactLedger.Fact> stateConflicts) {
        public Validation {
            missing = missing == null ? List.of() : List.copyOf(missing);
            covered = covered == null ? List.of() : List.copyOf(covered);
            stateConflicts = stateConflicts == null ? List.of() : List.copyOf(stateConflicts);
        }
    }

    public Validation validate(String summary, CompactionFactLedger ledger) {
        if (ledger == null) return new Validation(true, List.of(), List.of(), List.of());
        List<CompactionFactLedger.Fact> missing = ledger.missingFrom(summary);
        List<CompactionFactLedger.Fact> all = ledger.snapshot();
        List<CompactionFactLedger.Fact> covered = all.stream().filter(f -> !missing.contains(f)).toList();
        String rendered = summary == null ? "" : summary.toLowerCase(java.util.Locale.ROOT);
        List<CompactionFactLedger.Fact> conflicts = all.stream()
                .filter(f -> f.type() == CompactionFactLedger.Type.UNRESOLVED_ITEM)
                .filter(f -> rendered.contains(f.value().toLowerCase(java.util.Locale.ROOT)))
                .filter(f -> {
                    String fact = java.util.regex.Pattern.quote(f.value().toLowerCase(java.util.Locale.ROOT));
                    String state = "(?:resolved|completed|已解决|已完成)";
                    return rendered.matches("(?s).*?" + state + ".*?" + fact + ".*")
                            || rendered.matches("(?s).*?" + fact + ".*?" + state + ".*");
                })
                .toList();
        return new Validation(missing.isEmpty() && conflicts.isEmpty(), missing, covered, conflicts);
    }

    public boolean hasBlockingMissingFacts(Validation validation) {
        if (validation == null) return true;
        return validation.missing().stream().anyMatch(fact ->
                fact.status() == CompactionFactLedger.FactStatus.UNRESOLVED
                        || fact.type() == CompactionFactLedger.Type.FILE_PATH
                        || fact.type() == CompactionFactLedger.Type.MODIFIED_FILE
                        || fact.type() == CompactionFactLedger.Type.ERROR_CODE
                        || fact.type() == CompactionFactLedger.Type.USER_DECISION);
    }
}
