package com.devcli.runtime.api;

import com.devcli.llm.LlmClient;
import com.devcli.memory.CompactBoundaryMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCheckpointCandidateFactoryTest {

    @Test
    void extractsMessagesFromLatestCompactionBoundary() {
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        String summary = "[已压缩的历史对话摘要]\n"
                + metadata.renderBoundaryBlock() + "\nsummary";
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("dynamic system"),
                LlmClient.Message.user(summary),
                LlmClient.Message.assistant("已恢复"),
                LlmClient.Message.user("latest"),
                LlmClient.Message.assistant("reasoning", "answer")
        );

        TurnRunner.CheckpointCandidate candidate = RuntimeCheckpointCandidateFactory
                .fromHistory(history, true)
                .orElseThrow();

        assertEquals(4, candidate.messages().size());
        assertEquals(summary, candidate.summary());
        assertEquals(40_000, candidate.metadata().preTokens());
        assertEquals(candidate.messages().size(), candidate.messageTree().size());
        assertEquals("", candidate.messageTree().getFirst().parentId());
        assertEquals(candidate.messageTree().get(0).id(), candidate.messageTree().get(1).parentId());
        assertTrue(candidate.messages().stream().noneMatch(message -> "system".equals(message.role())));
        assertTrue(candidate.messages().getLast().reasoningContent() == null
                || candidate.messages().getLast().reasoningContent().isBlank());
    }

    @Test
    void prefersLatestCompactionBoundary() {
        CompactBoundaryMetadata oldMetadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                20_000, 7_000, 20, 7, 3, 500);
        CompactBoundaryMetadata latestMetadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "incremental",
                35_000, 9_000, 28, 9, 4, 800);
        String oldSummary = "[已压缩的历史对话摘要]\n"
                + oldMetadata.renderBoundaryBlock() + "\nold";
        String latestSummary = "[已压缩的历史对话摘要]\n"
                + latestMetadata.renderBoundaryBlock() + "\nlatest";

        TurnRunner.CheckpointCandidate candidate = RuntimeCheckpointCandidateFactory.fromHistory(
                List.of(
                        LlmClient.Message.user(oldSummary),
                        LlmClient.Message.assistant("old ack"),
                        LlmClient.Message.user(latestSummary),
                        LlmClient.Message.assistant("latest ack")),
                true).orElseThrow();

        assertEquals(latestSummary, candidate.summary());
        assertEquals(2, candidate.messages().size());
        assertEquals("incremental", candidate.metadata().mode());
    }

    @Test
    void removesSystemReasoningAndImagePayloadsFromCheckpointMessages() {
        CompactBoundaryMetadata metadata = new CompactBoundaryMetadata(
                "history", "token_threshold", "full",
                40_000, 8_000, 30, 8, 4, 1_000);
        String summary = "[已压缩的历史对话摘要]\n"
                + metadata.renderBoundaryBlock() + "\nsummary";
        LlmClient.Message imageMessage = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("保留图片说明"),
                LlmClient.ContentPart.imageBase64("secret-image-payload", "image/png"),
                LlmClient.ContentPart.imageUrl("https://example.invalid/private.png")));

        TurnRunner.CheckpointCandidate candidate = RuntimeCheckpointCandidateFactory.fromHistory(
                List.of(
                        LlmClient.Message.system("dynamic system"),
                        LlmClient.Message.user(summary),
                        imageMessage,
                        LlmClient.Message.assistant("private reasoning", "answer")),
                true).orElseThrow();

        assertTrue(candidate.messages().stream().noneMatch(message -> "system".equals(message.role())));
        assertTrue(candidate.messages().stream().allMatch(message -> message.reasoningContent() == null));
        assertTrue(candidate.messages().stream()
                .flatMap(message -> message.contentParts() == null
                        ? java.util.stream.Stream.empty()
                        : message.contentParts().stream())
                .noneMatch(LlmClient.ContentPart::isImage));
        assertTrue(candidate.messages().stream()
                .map(LlmClient.Message::content)
                .noneMatch(content -> content != null && (content.contains("secret-image-payload")
                        || content.contains("private.png"))));
    }

    @Test
    void doesNotCreateCandidateWhenTurnDidNotCompact() {
        assertFalse(RuntimeCheckpointCandidateFactory.fromHistory(
                List.of(LlmClient.Message.user("plain")), false).isPresent());
    }
}
