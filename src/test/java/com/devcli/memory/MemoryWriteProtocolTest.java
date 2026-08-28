package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryWriteProtocolTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void stableKeySupersedesOnlySamePredicateAndScope() {
        try (LongTermMemory memory = new LongTermMemory(new InMemoryLongTermMemoryStore(), tempDir)) {
            memory.storeManaged(entry("old", "server.port=8080", Map.of("claim_scope", "project-a")));
            memory.storeManaged(entry("other", "server.port=9090", Map.of("claim_scope", "project-b")));
            memory.storeManaged(entry("new", "server.port=8443", Map.of("claim_scope", "project-a")));

            assertFalse(memory.retrieve("old").orElseThrow().isActive());
            assertTrue(memory.retrieve("other").orElseThrow().isActive());
            assertTrue(memory.retrieve("new").orElseThrow().isActive());
            assertEquals(2, memory.getAll().stream().filter(MemoryEntry::isActive).count());
        }
    }

    @Test
    void stableKeyNormalizesSubjectPredicateAliasesAndScope() {
        MemoryEntry first = new MemoryEntry("first", "runtime=jdk17",
                MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of("claim_predicate", "uses", "claim_scope", "Project-A\\"),
                10, "Project.Runtime", true, "");
        MemoryEntry second = new MemoryEntry("second", "runtime=jdk21",
                MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of("claim_predicate", "value", "claim_scope", "project-a"),
                10, "project.runtime", true, "");

        assertEquals(MemoryWriteProtocol.stableKey(first), MemoryWriteProtocol.stableKey(second));
        assertEquals(MemoryWriteProtocol.stableKey(first),
                MemoryWriteProtocol.stableKey(new MemoryEntry("third", "runtime=jdk22",
                        MemoryEntry.MemoryType.FACT, Instant.now(),
                        Map.of("claim_predicate", "is", "claim_scope", "project-a/"),
                        10, "PROJECT.RUNTIME", true, "")));
        try (LongTermMemory memory = new LongTermMemory(new InMemoryLongTermMemoryStore(), tempDir)) {
            memory.storeManaged(first);
            memory.storeManaged(second);
            assertFalse(memory.retrieve("first").orElseThrow().isActive());
            assertTrue(memory.retrieve("second").orElseThrow().isActive());
        }
    }

    @Test
    void pendingConfirmationIsPersistedButNotRecallable() {
        MemoryEvidence pendingEvidence = new MemoryEvidence(MemoryEvidence.Confidence.LOW,
                "", "needs confirmation", MemoryEvidence.ReviewState.UNREVIEWED, List.of());
        MemoryEntry pending = entry("pending", "可能改用某个新框架", Map.of())
                .withEvidence(pendingEvidence);
        try (LongTermMemory memory = new LongTermMemory(new InMemoryLongTermMemoryStore(), tempDir)) {
            memory.storeManaged(pending);
            MemoryEntry stored = memory.retrieve("pending").orElseThrow();
            assertEquals("PENDING_CONFIRMATION", stored.getStructureState());
            assertFalse(stored.isActive());
            assertFalse(stored.isRecallable());
        }
    }

    @Test
    void pendingCandidateDoesNotSupersedeCurrentFactUntilReviewed() {
        MemoryEvidence pendingEvidence = new MemoryEvidence(MemoryEvidence.Confidence.MEDIUM,
                "server.port=8443", "needs confirmation",
                MemoryEvidence.ReviewState.UNREVIEWED, List.of());
        try (LongTermMemory memory = new LongTermMemory(new InMemoryLongTermMemoryStore(), tempDir)) {
            memory.storeManaged(entry("old", "server.port=8080", Map.of()));
            memory.storeManaged(entry("candidate", "server.port=8443", Map.of())
                    .withEvidence(pendingEvidence));

            assertTrue(memory.retrieve("old").orElseThrow().isRecallable());
            assertFalse(memory.retrieve("candidate").orElseThrow().isActive());
            assertEquals(1, memory.getAll().stream().filter(MemoryEntry::isActive).count());

            assertTrue(memory.updateReviewState("candidate", MemoryEvidence.ReviewState.REVIEWED));
            assertFalse(memory.retrieve("old").orElseThrow().isActive());
            assertTrue(memory.retrieve("candidate").orElseThrow().isRecallable());
            assertEquals(1, memory.getAll().stream().filter(MemoryEntry::isActive).count());
        }
    }

    @Test
    void unstructuredEntryCannotSupersedeStructuredFactBySubjectAlone() {
        try (LongTermMemory memory = new LongTermMemory(new InMemoryLongTermMemoryStore(), tempDir)) {
            memory.storeManaged(entry("structured", "server.port=8080", Map.of()));
            MemoryEntry unstructured = new MemoryEntry("note", "端口配置可能需要重新讨论",
                    MemoryEntry.MemoryType.FACT, Instant.now(), Map.of(), 10,
                    "key:server.port", true, "");

            memory.storeManaged(unstructured);

            assertTrue(memory.retrieve("structured").orElseThrow().isActive());
            assertTrue(memory.retrieve("note").orElseThrow().isActive());
            assertEquals(2, memory.getAll().stream().filter(MemoryEntry::isActive).count());
            assertEquals("UNSTRUCTURED", memory.retrieve("note").orElseThrow().getStructureState());
        }
    }

    @Test
    void freshnessIsTypeSpecificAndHasNoGlobalFloor() {
        Instant old = Instant.now().minusSeconds(400L * 24 * 3600);
        MemoryEntry fact = new MemoryEntry("fact", "project.version=1", MemoryEntry.MemoryType.FACT,
                old, Map.of(), 1);
        MemoryEntry feedback = new MemoryEntry("feedback", "preference.style=brief",
                MemoryEntry.MemoryType.FEEDBACK, old, Map.of(), 1);

        double factWeight = MemoryFreshnessPolicy.weight(fact, Instant.now());
        double feedbackWeight = MemoryFreshnessPolicy.weight(feedback, Instant.now());
        assertTrue(factWeight < 0.5, "旧事实应能自然降到 0.5 以下");
        assertTrue(feedbackWeight > factWeight, "反馈的半衰期应长于普通事实");
    }

    @Test
    void freshnessUsesValidationInsteadOfRecallAsItsAgeAnchor() {
        Instant now = Instant.now();
        MemoryEntry neverRecalled = new MemoryEntry("never", "project.version=1",
                MemoryEntry.MemoryType.FACT, now.minus(Duration.ofDays(240)), Map.of(), 1);
        MemoryEntry recentlyRecalled = new MemoryEntry("recent", "project.version=1",
                MemoryEntry.MemoryType.FACT, now.minus(Duration.ofDays(240)), Map.of(), 1,
                "", true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1, null,
                MemoryEvidence.legacy(Map.of()), 5, now.minus(Duration.ofDays(1)));
        MemoryEntry recentlyValidated = new MemoryEntry("validated", "project.version=1",
                MemoryEntry.MemoryType.FACT, now.minus(Duration.ofDays(240)), Map.of(), 1,
                "", true, "", MemoryEntry.CURRENT_SCHEMA_VERSION, 1, null,
                MemoryEvidence.legacy(Map.of()), 5, now.minus(Duration.ofDays(1)),
                MemoryEntry.MemoryKind.FACT, 1, now.minus(Duration.ofDays(1)));

        assertEquals(MemoryFreshnessPolicy.weight(neverRecalled, now),
                MemoryFreshnessPolicy.weight(recentlyRecalled, now), 0.000001);
        assertTrue(MemoryFreshnessPolicy.weight(recentlyValidated, now)
                > MemoryFreshnessPolicy.weight(recentlyRecalled, now));
    }

    @Test
    void rejectedAtomicRevisionLeavesPreviousActiveEntryUntouched() {
        AtomicRejectingStore store = new AtomicRejectingStore();
        try (LongTermMemory memory = new LongTermMemory(store, tempDir)) {
            memory.storeManaged(entry("old", "server.port=8080", Map.of()));
            store.rejectAtomicRevision = true;

            memory.storeManaged(entry("new", "server.port=8443", Map.of()));

            assertTrue(memory.retrieve("old").orElseThrow().isActive());
            assertTrue(memory.retrieve("new").isEmpty());
            assertEquals(1, memory.getAll().stream().filter(MemoryEntry::isActive).count());
        }
    }

    private static MemoryEntry entry(String id, String content, Map<String, String> metadata) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT,
                Instant.now(), metadata, MemoryEntry.estimateTokens(content));
    }

    private static class InMemoryLongTermMemoryStore implements LongTermMemoryStore {
        private final Map<String, MemoryEntry> entries = new java.util.LinkedHashMap<>();

        @Override
        public List<MemoryEntry> loadAll() {
            return List.copyOf(entries.values());
        }

        @Override
        public boolean upsert(MemoryEntry entry) {
            entries.put(entry.getId(), entry);
            return true;
        }

        @Override
        public void delete(String id) {
            entries.remove(id);
        }

        @Override
        public void clear() {
            entries.clear();
        }

        @Override
        public void close() {
        }
    }

    private static final class AtomicRejectingStore extends InMemoryLongTermMemoryStore {
        private boolean rejectAtomicRevision;

        @Override
        public boolean upsertAll(List<MemoryEntry> entries) {
            if (rejectAtomicRevision && entries.size() > 1) return false;
            return super.upsertAll(entries);
        }
    }
}
