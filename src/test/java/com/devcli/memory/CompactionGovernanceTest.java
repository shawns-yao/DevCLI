package com.devcli.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;

class CompactionGovernanceTest {
    @Test
    void repairsMissingProtectedFacts() {
        CompactionFactLedger ledger = new CompactionFactLedger();
        ledger.put(new CompactionFactLedger.Fact("p", CompactionFactLedger.Type.FILE_PATH,
                "src/main/App.java", "history"));
        var validation = new CompactionConsistencyValidator().validate("summary", ledger);
        assertFalse(validation.valid());
        assertTrue(new CompactionRepairer().repair("summary", validation).contains("src/main/App.java"));
    }

    @Test
    void classifiesOversizedContent() {
        var reduced = DeterministicContentReducer.reduce("toolCallId=x\nstdout=ok", 200, "artifact");
        assertEquals(DeterministicContentReducer.Kind.TOOL_OUTPUT, reduced.kind());
    }

    @Test
    void rejectsResolvedUnresolvedFact() {
        CompactionFactLedger ledger = new CompactionFactLedger();
        ledger.put(new CompactionFactLedger.Fact("u", CompactionFactLedger.Type.UNRESOLVED_ITEM,
                "compile failure", "history"));
        var result = new CompactionConsistencyValidator()
                .validate("compile failure is completed", ledger);
        assertFalse(result.valid());
        assertEquals(1, result.stateConflicts().size());
    }

    @Test
    void persistsAndVerifiesBoundarySnapshot() throws Exception {
        var facts = new CompactionFactLedger();
        facts.put(new CompactionFactLedger.Fact("p", CompactionFactLedger.Type.FILE_PATH,
                "src/App.java", "history"));
        var snapshot = new CompactBoundarySnapshot("b", 3, facts.snapshot(),
                java.util.List.of("src/App.java"), java.util.List.of(), java.util.List.of(),
                java.util.Map.of(), "", java.util.Map.of("step", "DONE"), "ledger").sealed();
        var file = Files.createTempFile("compact-boundary-test", ".json");
        var store = new CompactBoundarySnapshotStore(file);
        store.save(snapshot);
        assertTrue(store.load().orElseThrow().checksumValid());
        Files.deleteIfExists(file);
    }
}
