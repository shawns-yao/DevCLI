package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryOrganizerTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesJsonObjectWrappedByExplanatoryText() throws Exception {
        List<MemoryOrganizer.Proposal> proposals = MemoryOrganizer.parseProposals("""
                plan follows
                {"actions":[{"action":"REVIEW","source_ids":["m1"],"reason":"check","confidence":0.7}]}
                done
                """);

        assertEquals(1, proposals.size());
        assertEquals(MemoryOrganizer.Action.REVIEW, proposals.getFirst().action());
        assertEquals(List.of("m1"), proposals.getFirst().sourceIds());
    }

    @Test
    void dryRunQueuesReviewWithoutMutatingMemory() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("m1", "用户偏好使用 Java", "user.language",
                    MemoryEvidence.ReviewState.UNREVIEWED));
            StubClient client = new StubClient("""
                    {"actions":[{"action":"REVIEW","source_ids":["m1"],"reason":"需要确认","confidence":0.8}]}
                    """);

            MemoryOrganizer.Report report = new MemoryOrganizer(client, memory)
                    .organize(MemoryOrganizer.Mode.DRY_RUN);

            assertEquals("completed", report.status());
            assertEquals(1, report.reviewRequired());
            assertEquals(0, report.applied());
            assertTrue(memory.retrieve("m1").orElseThrow().isActive());
        }
    }

    @Test
    void appliesOnlyCompleteLowRiskUnreviewedSubjectMerge() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("m1", "项目默认使用 Java 17", "project.java_version",
                    MemoryEvidence.ReviewState.UNREVIEWED));
            memory.store(entry("m2", "项目构建环境固定为 Java 17", "project.java_version",
                    MemoryEvidence.ReviewState.UNREVIEWED));
            StubClient client = new StubClient("""
                    {"actions":[{"action":"MERGE","source_ids":["m1","m2"],
                    "merged_content":"项目默认构建与运行环境使用 Java 17",
                    "reason":"合并同主题重复事实","confidence":0.96}]}
                    """);

            MemoryOrganizer.Report report = new MemoryOrganizer(client, memory)
                    .organize(MemoryOrganizer.Mode.APPLY_SAFE);

            assertEquals(1, report.applied());
            assertFalse(memory.retrieve("m1").orElseThrow().isActive());
            assertFalse(memory.retrieve("m2").orElseThrow().isActive());
            MemoryEntry merged = memory.search("Java 17", 5).getFirst();
            assertTrue(merged.getId().startsWith("organized-"));
            assertEquals(MemoryEvidence.ReviewState.UNREVIEWED, merged.getEvidence().reviewState());
        }
    }

    @Test
    void reviewedSourcePreventsAutomaticMerge() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("m1", "项目默认使用 Java 17", "project.java_version",
                    MemoryEvidence.ReviewState.REVIEWED));
            memory.store(entry("m2", "项目构建环境固定为 Java 17", "project.java_version",
                    MemoryEvidence.ReviewState.UNREVIEWED));
            StubClient client = new StubClient("""
                    {"actions":[{"action":"MERGE","source_ids":["m1","m2"],
                    "merged_content":"项目默认构建与运行环境使用 Java 17",
                    "reason":"合并同主题事实","confidence":0.99}]}
                    """);

            MemoryOrganizer.Report report = new MemoryOrganizer(client, memory)
                    .organize(MemoryOrganizer.Mode.APPLY_SAFE);

            assertEquals(0, report.applied());
            assertEquals(1, report.reviewRequired());
            assertEquals(MemoryOrganizer.Risk.MEDIUM, report.decisions().getFirst().risk());
            assertTrue(memory.retrieve("m1").orElseThrow().isActive());
            assertTrue(memory.retrieve("m2").orElseThrow().isActive());
        }
    }

    @Test
    void unknownSourceIsRejectedByProgramPolicy() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("m1", "用户偏好使用 Java", "user.language",
                    MemoryEvidence.ReviewState.UNREVIEWED));
            StubClient client = new StubClient("""
                    {"actions":[{"action":"REVIEW","source_ids":["invented"],
                    "reason":"check","confidence":0.9}]}
                    """);

            MemoryOrganizer.Report report = new MemoryOrganizer(client, memory)
                    .organize(MemoryOrganizer.Mode.APPLY_SAFE);

            assertEquals(0, report.applied());
            assertEquals(0, report.reviewRequired());
            assertEquals("rejected_by_policy", report.decisions().getFirst().outcome());
            assertTrue(report.decisions().getFirst().reasons().contains("unknown_source_id"));
        }
    }

    @Test
    void malformedPlanGetsOneBoundedRepairAttempt() throws Exception {
        try (LongTermMemory memory = new LongTermMemory(tempDir.toFile())) {
            memory.store(entry("m1", "用户偏好使用 Java", "user.language",
                    MemoryEvidence.ReviewState.UNREVIEWED));
            StubClient client = new StubClient(
                    "not-json",
                    "{\"actions\":[{\"action\":\"KEEP\",\"source_ids\":[\"m1\"],\"confidence\":1.0}]}"
            );

            MemoryOrganizer.Report report = new MemoryOrganizer(client, memory)
                    .organize(MemoryOrganizer.Mode.DRY_RUN);

            assertEquals("completed", report.status());
            assertEquals(2, client.calls);
            assertEquals(MemoryOrganizer.Action.KEEP, report.decisions().getFirst().proposal().action());
        }
    }

    private static MemoryEntry entry(String id, String content, String subject,
                                     MemoryEvidence.ReviewState reviewState) {
        return new MemoryEntry(
                id,
                content,
                MemoryEntry.MemoryType.FACT,
                Instant.now(),
                Map.of("source", "heuristic"),
                MemoryEntry.estimateTokens(content),
                subject,
                true,
                "",
                MemoryEntry.CURRENT_SCHEMA_VERSION,
                1,
                null,
                new MemoryEvidence(
                        MemoryEvidence.Confidence.HIGH,
                        content,
                        "test",
                        reviewState,
                        List.of()));
    }

    private static final class StubClient implements LlmClient {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private int calls;

        private StubClient(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            calls++;
            String response = responses.poll();
            if (response == null) throw new IOException("missing response");
            return new ChatResponse("assistant", response, null, 10, 5);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
