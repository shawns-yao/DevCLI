package com.devcli.memory;

import java.util.List;

/** Observable result for context compaction, suitable for runtime events and recovery. */
public record CompactionResult(
        boolean compacted,
        List<CompactionFactLedger.Fact> protectedFacts,
        CompactionSummaryEnvelope summary,
        List<String> warnings,
        int beforeTokens,
        int afterTokens) {
    public CompactionResult {
        protectedFacts = protectedFacts == null ? List.of() : List.copyOf(protectedFacts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
