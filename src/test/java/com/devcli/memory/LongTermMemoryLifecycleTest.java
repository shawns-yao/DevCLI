package com.devcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void sameSubjectIncrementsRevisionAndMarksConflict() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeWithSubject(entry("old", "项目默认 Java 版本是 17", "project.java_version", null));
            memory.storeWithSubject(entry("new", "项目默认 Java 版本是 21", "project.java_version", null));

            MemoryEntry latest = memory.search("Java 版本", 5).getFirst();
            MemoryEntry previous = memory.retrieve("old").orElseThrow();
            assertEquals(2, latest.getRevision());
            assertEquals(MemoryEntry.CURRENT_SCHEMA_VERSION, latest.getSchemaVersion());
            assertEquals("old", latest.getMetadata().get("conflict_with"));
            assertEquals(java.util.List.of("old"), latest.getEvidence().conflictsWith());
            assertFalse(previous.isActive());
            assertEquals("new", previous.getSupersededBy());
        }
    }

    @Test
    void inferredClaimConflictSupersedesUnversionedMemory() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeManaged(entry("old", "server.port=8080", "", null));
            memory.storeManaged(entry("new", "server.port=8443", "", null));

            assertFalse(memory.retrieve("old").orElseThrow().isActive());
            MemoryEntry latest = memory.search("server.port", 5).getFirst();
            assertEquals(2, latest.getRevision());
            assertEquals("old", latest.getMetadata().get("conflict_with"));
            assertEquals(java.util.List.of("old"), latest.getEvidence().conflictsWith());
            assertFalse(latest.getSubject().isBlank());
        }
    }

    @Test
    void lifecycleFieldsPersistAcrossReload() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3_600);
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeWithSubject(entry("old", "项目默认 Java 版本是 17", "project.java_version", expiresAt));
            memory.storeWithSubject(entry("new", "项目默认 Java 版本是 21", "project.java_version", expiresAt));
        }

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry latest = reloaded.retrieve("new").orElseThrow();
            assertEquals(2, latest.getRevision());
            assertEquals(expiresAt.toEpochMilli(), latest.getExpiresAt().toEpochMilli());
            assertEquals(MemoryEntry.CURRENT_SCHEMA_VERSION, latest.getSchemaVersion());
            assertEquals(java.util.List.of("old"), latest.getEvidence().conflictsWith());
        }
    }

    @Test
    void rejectedMemoryIsPersistedButExcludedFromRecall() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry candidate = entry("review", "用户偏好使用 Java", "user.language", null)
                    .withEvidence(new MemoryEvidence(
                            MemoryEvidence.Confidence.MEDIUM,
                            "用户偏好使用 Java",
                            "heuristic",
                            MemoryEvidence.ReviewState.UNREVIEWED,
                            java.util.List.of()));
            memory.storeManaged(candidate);
            assertTrue(memory.updateReviewState("review", MemoryEvidence.ReviewState.REJECTED));
            assertTrue(memory.search("Java", 5).isEmpty());
        }

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry rejected = reloaded.retrieve("review").orElseThrow();
            assertEquals(MemoryEvidence.Confidence.MEDIUM, rejected.getEvidence().confidence());
            assertEquals("用户偏好使用 Java", rejected.getEvidence().sourceQuote());
            assertEquals("heuristic", rejected.getEvidence().reasoning());
            assertEquals(MemoryEvidence.ReviewState.REJECTED, rejected.getEvidence().reviewState());
            assertFalse(rejected.isRecallable());
        }
    }

    @Test
    void confirmedCandidateAtomicallySupersedesCurrentFactAcrossReload() throws Exception {
        MemoryEvidence pendingEvidence = new MemoryEvidence(
                MemoryEvidence.Confidence.MEDIUM,
                "server.port=8443",
                "needs confirmation",
                MemoryEvidence.ReviewState.UNREVIEWED,
                java.util.List.of());
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeManaged(entry("old-port", "server.port=8080", "", null));
            memory.storeManaged(entry("new-port", "server.port=8443", "", null)
                    .withEvidence(pendingEvidence));

            assertTrue(memory.retrieve("old-port").orElseThrow().isRecallable());
            assertFalse(memory.retrieve("new-port").orElseThrow().isActive());
            assertTrue(memory.updateReviewState("new-port", MemoryEvidence.ReviewState.REVIEWED));
        }

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            assertFalse(reloaded.retrieve("old-port").orElseThrow().isActive());
            assertTrue(reloaded.retrieve("new-port").orElseThrow().isRecallable());
            assertEquals(1, reloaded.getAll().stream().filter(MemoryEntry::isActive).count());
        }
    }

    @Test
    void rejectedMemoryDoesNotBlockSameContentFromBeingSavedAgain() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry rejected = entry("rejected", "用户偏好使用 Java", "user.language", null)
                    .withEvidence(new MemoryEvidence(
                            MemoryEvidence.Confidence.LOW,
                            "用户偏好使用 Java",
                            "heuristic",
                            MemoryEvidence.ReviewState.REJECTED,
                            java.util.List.of()));
            memory.storeManaged(rejected);

            MemoryEntry reviewed = entry("reviewed", "用户偏好使用 Java", "user.language", null)
                    .withEvidence(new MemoryEvidence(
                            MemoryEvidence.Confidence.HIGH,
                            "用户偏好使用 Java",
                            "explicit",
                            MemoryEvidence.ReviewState.REVIEWED,
                            java.util.List.of()));
            memory.storeManaged(reviewed);

            assertEquals("reviewed", memory.search("Java", 5).getFirst().getId());
            assertEquals(MemoryEvidence.ReviewState.REJECTED,
                    memory.retrieve("rejected").orElseThrow().getEvidence().reviewState());
        }
    }

    @Test
    void semanticallyEquivalentClaimIsNotStoredTwice() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeManaged(entry("first", "项目默认 Java 版本是 21", "", null));
            memory.storeManaged(entry("duplicate", "项目 Java 版本设置为 21", "", null));

            assertEquals(1, memory.size());
            assertTrue(memory.retrieve("first").isPresent());
            assertTrue(memory.retrieve("duplicate").isEmpty());
        }
    }

    @Test
    void paraphrasedClaimWithDifferentValueCreatesConflict() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeManaged(entry("old-model", "项目默认模型是 glm-5", "", null));
            memory.storeManaged(entry("new-model", "项目当前模型设置为 gpt-5.5", "", null));

            assertFalse(memory.retrieve("old-model").orElseThrow().isActive());
            MemoryEntry latest = memory.retrieve("new-model").orElseThrow();
            assertEquals(2, latest.getRevision());
            assertEquals(java.util.List.of("old-model"), latest.getEvidence().conflictsWith());
        }
    }

    @Test
    void negatedUsageConflictsWithPreviousDefaultUsage() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.storeManaged(entry("docker-on", "项目默认使用 Docker", "", null));
            memory.storeManaged(entry("docker-off", "项目禁止使用 Docker", "", null));

            assertFalse(memory.retrieve("docker-on").orElseThrow().isActive());
            assertEquals(java.util.List.of("docker-on"),
                    memory.retrieve("docker-off").orElseThrow().getEvidence().conflictsWith());
        }
    }

    @Test
    void expiredMemoryIsArchivedInsteadOfPhysicallyDeleted() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("expired", "临时事实", "", Instant.now().minusSeconds(1)));

            assertTrue(memory.search("临时事实", 5).isEmpty());
            MemoryEntry archived = memory.retrieve("expired").orElseThrow();
            assertFalse(archived.isActive());
            assertEquals("ARCHIVED", archived.getMetadata().get("lifecycle_state"));
            assertEquals(1, memory.size());
        }
    }

    @Test
    void policyExpirationRenewsWhenMemoryIsActuallyRecalled() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("sliding", "项目构建命令是 mvn test", "project.build", null));
            MemoryEntry stored = memory.retrieve("sliding").orElseThrow();
            Instant initialExpiry = stored.getExpiresAt();
            Instant recalledAt = initialExpiry.minus(Duration.ofHours(1));

            assertEquals("SLIDING", stored.getMetadata().get("expiry_mode"));
            assertTrue(memory.recordRecalled(java.util.List.of("sliding"), recalledAt));

            MemoryEntry renewed = memory.retrieve("sliding").orElseThrow();
            assertEquals(recalledAt, renewed.getLastRecalledAt());
            assertTrue(renewed.getExpiresAt().isAfter(initialExpiry));
            assertEquals(MemoryLifecyclePolicy.expiresAt(MemoryEntry.MemoryType.FACT, recalledAt),
                    renewed.getExpiresAt());
        }
    }

    @Test
    void explicitExpirationRemainsFixedWhenMemoryIsRecalled() throws Exception {
        Instant fixedExpiry = Instant.now().plus(Duration.ofDays(30));
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("fixed", "临时发布窗口在九月结束", "project.release_window", fixedExpiry));
            MemoryEntry stored = memory.retrieve("fixed").orElseThrow();

            assertEquals("FIXED", stored.getMetadata().get("expiry_mode"));
            assertTrue(memory.recordRecalled(java.util.List.of("fixed"), Instant.now().plusSeconds(60)));

            MemoryEntry recalled = memory.retrieve("fixed").orElseThrow();
            assertEquals(fixedExpiry, recalled.getExpiresAt());
        }
    }

    @Test
    void renewedExpirationPersistsAcrossReload() throws Exception {
        Instant renewedExpiry;
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("persistent-sliding", "项目测试命令是 mvn test",
                    "project.test", null));
            Instant recalledAt = memory.retrieve("persistent-sliding").orElseThrow()
                    .getExpiresAt().minus(Duration.ofHours(1));
            assertTrue(memory.recordRecalled(java.util.List.of("persistent-sliding"), recalledAt));
            renewedExpiry = memory.retrieve("persistent-sliding").orElseThrow().getExpiresAt();
        }

        try (LongTermMemory reloaded = new LongTermMemory(tempDir.toFile())) {
            MemoryEntry recalled = reloaded.retrieve("persistent-sliding").orElseThrow();
            assertEquals(1, recalled.getRecallCount());
            assertEquals(renewedExpiry.toEpochMilli(), recalled.getExpiresAt().toEpochMilli());
            assertEquals("SLIDING", recalled.getMetadata().get("expiry_mode"));
        }
    }

    @Test
    void legacyPolicyExpirationBecomesSlidingAfterFirstRecall() throws Exception {
        Instant oldExpiry = Instant.now().plus(Duration.ofDays(10));
        MemoryEntry legacy = new MemoryEntry("legacy-sliding", "项目构建命令是 mvn verify",
                MemoryEntry.MemoryType.FACT, Instant.now().minus(Duration.ofDays(170)), Map.of(), 10,
                "project.build", true, "", 3, 1, oldExpiry,
                MemoryEvidence.legacy(Map.of()), 0, null);
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(legacy);
            MemoryEntry stored = memory.retrieve("legacy-sliding").orElseThrow();

            assertEquals("SLIDING", stored.getMetadata().get("expiry_mode"));
            assertTrue(memory.recordRecalled(java.util.List.of("legacy-sliding"), Instant.now()));
            assertTrue(memory.retrieve("legacy-sliding").orElseThrow().getExpiresAt().isAfter(oldExpiry));
        }
    }

    private static MemoryEntry entry(String id, String content, String subject, Instant expiresAt) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of(), MemoryEntry.estimateTokens(content), subject, true, "",
                MemoryEntry.CURRENT_SCHEMA_VERSION, 1, expiresAt);
    }
}
