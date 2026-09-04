package com.devcli.memory;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextProjectionBuilderTest {

    @Test
    void rebuildsWindowFromStablePrefixSummaryRestoreAndRecentTail() {
        ContextProjectionBuilder builder = new ContextProjectionBuilder();
        List<LlmClient.Message> projection = builder.build(
                List.of(LlmClient.Message.system("system")),
                "## 文件和代码\n- src/A.java",
                "- pending: step-2",
                List.of(LlmClient.Message.user("recent request")));

        assertEquals(6, projection.size());
        assertEquals("system", projection.get(0).content());
        assertTrue(projection.get(1).content().startsWith(ConversationHistoryCompactor.SUMMARY_MARKER));
        assertEquals("OK.", projection.get(2).content());
        assertTrue(projection.get(3).content().startsWith(ConversationHistoryCompactor.POST_COMPACT_RESTORE_MARKER));
        assertEquals("OK.", projection.get(4).content());
        assertEquals("recent request", projection.get(5).content());
    }

    @Test
    void returnsImmutableProjectionMetadataAndIgnoresBoundaryMetadataInFingerprint() {
        ContextProjectionBuilder builder = new ContextProjectionBuilder();
        var result = builder.project(
                List.of(LlmClient.Message.system("system")),
                "summary",
                "restore",
                List.of(LlmClient.Message.user("tail")));

        assertEquals(1, result.systemMessageCount());
        assertEquals(1, result.recentMessageCount());
        assertEquals(6, result.messages().size());
        assertTrue(result.fingerprint().matches("[0-9a-f]{64}"));
        assertThrowsUnsupported(result.messages());

        String withBoundary = ConversationHistoryCompactor.SUMMARY_MARKER
                + "<compact_boundary>\nsourceHash=abc\n</compact_boundary>\nsummary";
        String withoutBoundary = ConversationHistoryCompactor.SUMMARY_MARKER + "summary";
        assertEquals(ContextProjectionBuilder.fingerprintOf(
                        List.of(LlmClient.Message.internalUser(withBoundary))),
                ContextProjectionBuilder.fingerprintOf(
                        List.of(LlmClient.Message.internalUser(withoutBoundary))));
        assertNotEquals(result.summaryHash(), result.restoreContextHash());
    }

    @Test
    void fingerprintKeepsMessageSourceAndImageShapeDistinct() {
        String userHash = ContextProjectionBuilder.fingerprintOf(
                List.of(LlmClient.Message.user("same")));
        String internalHash = ContextProjectionBuilder.fingerprintOf(
                List.of(LlmClient.Message.internalUser("same")));
        String imageHash = ContextProjectionBuilder.fingerprintOf(
                List.of(LlmClient.Message.user(List.of(
                        LlmClient.ContentPart.text("same"),
                        LlmClient.ContentPart.imageBase64("ZmFrZQ==", "image/png")))));

        assertNotEquals(userHash, internalHash);
        assertNotEquals(userHash, imageHash);
    }

    private static void assertThrowsUnsupported(List<LlmClient.Message> messages) {
        try {
            messages.add(LlmClient.Message.user("mutate"));
            throw new AssertionError("projection must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
