package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

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
    void factsUseProvidedRuntimeEventIdsAndSessionEpoch() {
        var ledger = new CompactionFactLedger();
        var context = CompactionContext.forTrigger(
                100, "project", "session-1", 9, 2,
                40, 50, "hash", null, List.of(), Map.of(), List.of(41L, 49L));

        new CompactionFactExtractor().extract(
                List.of(LlmClient.Message.user("modified: src/App.java"),
                        LlmClient.Message.assistant("收到")), ledger, context);

        var fact = ledger.snapshot().stream()
                .filter(value -> value.type() == CompactionFactLedger.Type.MODIFIED_FILE)
                .findFirst().orElseThrow();
        assertEquals(41L, fact.sequence());
        assertEquals(9L, fact.contextEpoch());
        assertTrue(fact.sourceMessageId().contains("session-1"));
        assertTrue(fact.sourceMessageId().contains("event:41"));
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
