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
        List<CompactionFactLedger.Fact> all = ledger.activeFacts();
        List<CompactionFactLedger.Fact> covered = all.stream().filter(f -> !missing.contains(f)).toList();
        String rendered = summary == null ? "" : summary.toLowerCase(java.util.Locale.ROOT);
        List<CompactionFactLedger.Fact> conflicts = all.stream()
                .filter(f -> f.status() == CompactionFactLedger.FactStatus.UNRESOLVED)
                .filter(f -> incorrectlyResolved(rendered, f))
                .toList();
        return new Validation(missing.isEmpty() && conflicts.isEmpty(), missing, covered, conflicts);
    }

    private boolean incorrectlyResolved(String summary, CompactionFactLedger.Fact fact) {
        // 状态必须归属于同一条事实，不能跨事项、换行或否定词推断完成。
        String value = java.util.regex.Pattern.quote(fact.value().toLowerCase(java.util.Locale.ROOT));
        String state = "(?:\\b(?:resolved|completed)\\b|已解决|已完成)";
        String link = "[\\s:：=\\[\\]【】*_-]*(?:(?:is|was|status|状态|已经)\\s*[:：=]?\\s*)?";
        var declaration = java.util.regex.Pattern.compile(
                "(?:" + value + link + state + "|" + state + link + value + ")");
        for (String clause : summary.split("[\\r\\n;；。!?！？]+")) {
            if (clause.stripLeading().startsWith("<!--")) continue;
            String normalized = clause.replaceAll(
                    "(?i)\\b(?:not|never|not yet)\\s+(?:resolved|completed)\\b"
                            + "|(?:尚未|未|没有)(?:解决|完成)", " pending ");
            if (declaration.matcher(normalized).find()) return true;
        }
        if (summary.contains("<!-- summary-item ")) {
            return RollingSummary.parse(summary).allItems().stream().anyMatch(item ->
                    item.lifecycle() == SummaryItem.Lifecycle.RESOLVED
                            && (item.subject().equalsIgnoreCase(fact.id())
                            || item.subject().equalsIgnoreCase(fact.value())
                            || item.evidenceRefs().contains(fact.id())));
        }
        return false;
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
