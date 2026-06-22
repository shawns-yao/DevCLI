package com.devcli.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable RAG evidence payload embedded in search_code tool results.
 *
 * <p>The human-readable formatter can change freely; WorkingMemory reads only this stable JSON contract.
 */
public final class RagEvidencePayload {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String START = "\n\n<RAG_EVIDENCE_JSON>";
    private static final String END = "</RAG_EVIDENCE_JSON>";

    private RagEvidencePayload() {
    }

    public static String appendTo(String visibleText, String query,
                                  List<VectorStore.SearchResult> results,
                                  List<SymbolInvalidation> invalidations) {
        Payload payload = from(query, results, invalidations);
        if (payload.evidence().isEmpty() && payload.negativeFacts().isEmpty()) {
            return visibleText == null ? "" : visibleText;
        }
        try {
            return (visibleText == null ? "" : visibleText)
                    + START
                    + JSON.writeValueAsString(payload)
                    + END;
        } catch (JsonProcessingException e) {
            return visibleText == null ? "" : visibleText;
        }
    }

    public static Payload extract(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return Payload.empty();
        }
        int start = toolResult.indexOf(START);
        if (start < 0) {
            return Payload.empty();
        }
        int payloadStart = start + START.length();
        int end = toolResult.indexOf(END, payloadStart);
        if (end < 0) {
            return Payload.empty();
        }
        String json = toolResult.substring(payloadStart, end).trim();
        if (json.isBlank()) {
            return Payload.empty();
        }
        try {
            Payload payload = JSON.readValue(json, Payload.class);
            return payload == null ? Payload.empty() : payload.normalized();
        } catch (Exception e) {
            return Payload.empty();
        }
    }

    private static Payload from(String query, List<VectorStore.SearchResult> results,
                                List<SymbolInvalidation> invalidations) {
        List<Evidence> evidence = new ArrayList<>();
        if (results != null) {
            for (VectorStore.SearchResult result : results) {
                evidence.add(new Evidence(
                        safe(result.filePath()),
                        safe(result.name()),
                        safe(result.chunkType()),
                        safe(result.symbolVersion()),
                        safe(result.indexEpoch()),
                        safe(result.classpathEpoch()),
                        safe(query),
                        result.similarity()));
            }
        }

        Map<String, NegativeFact> negativeFacts = new LinkedHashMap<>();
        if (results != null) {
            for (VectorStore.SearchResult result : results) {
                addInvalidations(negativeFacts, result.invalidations());
            }
        }
        addInvalidations(negativeFacts, invalidations);
        return new Payload(evidence, new ArrayList<>(negativeFacts.values()));
    }

    private static void addInvalidations(Map<String, NegativeFact> target, List<SymbolInvalidation> invalidations) {
        if (invalidations == null) {
            return;
        }
        for (SymbolInvalidation invalidation : invalidations) {
            if (invalidation == null || invalidation.negativeFact() == null || invalidation.negativeFact().isBlank()) {
                continue;
            }
            target.putIfAbsent(invalidation.negativeFact(), new NegativeFact(
                    safe(invalidation.negativeFact()),
                    safe(invalidation.oldSymbolVersion()),
                    safe(invalidation.newSymbolVersion()),
                    safe(invalidation.oldIndexEpoch()),
                    safe(invalidation.newIndexEpoch())));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(List<Evidence> evidence, List<NegativeFact> negativeFacts) {
        public static Payload empty() {
            return new Payload(List.of(), List.of());
        }

        Payload normalized() {
            return new Payload(
                    evidence == null ? List.of() : List.copyOf(evidence),
                    negativeFacts == null ? List.of() : List.copyOf(negativeFacts));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(String filePath,
                           String symbolName,
                           String chunkType,
                           String symbolVersion,
                           String indexEpoch,
                           String classpathEpoch,
                           String query,
                           double similarity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NegativeFact(String negativeFact,
                               String oldSymbolVersion,
                               String newSymbolVersion,
                               String oldIndexEpoch,
                               String newIndexEpoch) {
        public String renderForMemory() {
            StringBuilder sb = new StringBuilder(negativeFact == null ? "" : negativeFact);
            append(sb, "oldSymbolVersion", oldSymbolVersion);
            append(sb, "newSymbolVersion", newSymbolVersion);
            append(sb, "oldIndexEpoch", oldIndexEpoch);
            append(sb, "newIndexEpoch", newIndexEpoch);
            return sb.toString().trim();
        }

        private static void append(StringBuilder sb, String key, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(key).append('=').append(value);
        }
    }
}
