package com.devcli.memory;

import com.devcli.rag.RagEvidencePayload;
import com.devcli.rag.RagEvidenceSideChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryTypedRagEvidenceTest {

    @Test
    void visibleTextFormatDoesNotAffectTypedEvidence() {
        SessionMemory memory = new SessionMemory();
        RagEvidencePayload.Payload payload = new RagEvidencePayload.Payload(
                List.of(new RagEvidencePayload.Evidence(
                        "src/main/java/com/devcli/agent/Agent.java",
                        "Agent.run",
                        "method",
                        "sv-current",
                        "idx-current",
                        "cp-current",
                        "agent execution",
                        0.91)),
                List.of());

        memory.recordToolResult(
                "search_code",
                "{\"query\":\"agent execution\"}",
                "展示文本可以任意调整，不包含旧证据标记。",
                List.of(new RagEvidenceSideChannel(payload)));

        assertEquals(1, memory.getRagEvidenceMemory().size());
        assertEquals("sv-current", memory.getRagEvidenceMemory().get(0).symbolVersion());
    }

    @Test
    void typedNegativeFactPrunesOldSymbolVersion() {
        SessionMemory memory = new SessionMemory();
        RagEvidencePayload.Payload oldEvidence = new RagEvidencePayload.Payload(
                List.of(new RagEvidencePayload.Evidence(
                        "src/main/java/com/devcli/rag/CodeRetriever.java",
                        "CodeRetriever.search",
                        "method",
                        "sv-old",
                        "idx-old",
                        "cp-1",
                        "search",
                        0.88)),
                List.of());
        memory.recordToolResult("search_code", "{}", "first",
                List.of(new RagEvidenceSideChannel(oldEvidence)));

        RagEvidencePayload.Payload invalidation = new RagEvidencePayload.Payload(
                List.of(),
                List.of(new RagEvidencePayload.NegativeFact(
                        "Do not rely on the old search symbol.",
                        "sv-old",
                        "sv-new",
                        "idx-old",
                        "idx-new")));
        memory.recordToolResult("search_code", "{}", "second",
                List.of(new RagEvidenceSideChannel(invalidation)));

        assertTrue(memory.getRagEvidenceMemory().isEmpty());
        assertTrue(memory.getVolatileFacts().stream()
                .anyMatch(fact -> fact.contains("sv-old") && fact.contains("sv-new")));
    }
}
