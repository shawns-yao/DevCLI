package com.devcli.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic ledger of facts that must survive conversation compaction. */
public final class CompactionFactLedger {
    public enum Type { FILE_PATH, SYMBOL, NUMBER, ERROR_CODE, COMMAND, CONFIG_VALUE,
        MODIFIED_FILE, USER_DECISION, UNRESOLVED_ITEM }

    public enum FactStatus { ACTIVE, UNRESOLVED, RESOLVED, SUPERSEDED, EXPIRED }

    /**
     * A protected fact keeps its provenance beside the value.  The legacy
     * {@link #sourceId()} accessor remains available for callers written
     * against the first ledger version.
     */
    public record Fact(String id, Type type, String value,
                       String sourceMessageId, String sourceToolCallId,
                       FactStatus status, long sequence, long contextEpoch) {
        public Fact {
            id = normalize(id);
            type = type == null ? Type.NUMBER : type;
            value = normalize(value);
            sourceMessageId = normalize(sourceMessageId);
            sourceToolCallId = normalize(sourceToolCallId);
            status = status == null
                    ? (type == Type.UNRESOLVED_ITEM ? FactStatus.UNRESOLVED : FactStatus.ACTIVE)
                    : status;
            sequence = Math.max(0, sequence);
            contextEpoch = Math.max(0, contextEpoch);
        }

        /** Compatibility constructor used by pre-provenance callers. */
        public Fact(String id, Type type, String value, String sourceId) {
            this(id, type, value, sourceId, "",
                    type == Type.UNRESOLVED_ITEM ? FactStatus.UNRESOLVED : FactStatus.ACTIVE,
                    0, 0);
        }

        /** Compatibility alias for the former source field. */
        public String sourceId() {
            return sourceMessageId;
        }

        public Fact withStatus(FactStatus nextStatus) {
            return new Fact(id, type, value, sourceMessageId, sourceToolCallId,
                    nextStatus, sequence, contextEpoch);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record DecisionConflict(String subject, Fact previous, Fact replacement) {}

    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Map<String, Fact> latestDecisions = new LinkedHashMap<>();
    private final List<DecisionConflict> decisionConflicts = new ArrayList<>();

    public synchronized void put(Fact fact) {
        if (fact == null || fact.id() == null || fact.id().isBlank()
                || fact.value() == null || fact.value().isBlank()) return;
        facts.put(fact.id(), fact);
    }

    /** Stores a decision by stable subject key; later history replaces the earlier value. */
    public synchronized void putLatestDecision(String subject, String value, String sourceId) {
        putLatestDecision(subject, value, sourceId, "", 0, 0);
    }

    public synchronized void putLatestDecision(String subject, String value,
                                                String sourceMessageId,
                                                String sourceToolCallId,
                                                long sequence,
                                                long contextEpoch) {
        if (subject == null || subject.isBlank() || value == null || value.isBlank()) return;
        String normalizedSubject = subject.trim();
        Fact replacement = new Fact("decision:" + normalizedSubject, Type.USER_DECISION,
                normalizedSubject + "=" + value.trim(), sourceMessageId,
                sourceToolCallId, FactStatus.ACTIVE, sequence, contextEpoch);
        Fact previous = latestDecisions.get(normalizedSubject);
        if (previous != null && sequence > 0 && previous.sequence() > sequence) {
            return;
        }
        if (previous != null && !Objects.equals(previous.value(), replacement.value())) {
            decisionConflicts.add(new DecisionConflict(normalizedSubject, previous, replacement));
        }
        latestDecisions.put(normalizedSubject, replacement);
        put(replacement);
    }

    public synchronized List<Fact> snapshot() {
        return List.copyOf(new ArrayList<>(facts.values()));
    }

    public synchronized List<Fact> activeFacts() {
        return facts.values().stream()
                .filter(f -> f.status() != FactStatus.SUPERSEDED && f.status() != FactStatus.EXPIRED)
                .toList();
    }

    public synchronized List<DecisionConflict> decisionConflicts() {
        return List.copyOf(decisionConflicts);
    }

    public synchronized List<Fact> missingFrom(String text) {
        String value = text == null ? "" : text;
        return activeFacts().stream().filter(f -> !value.contains(f.value())).toList();
    }
}
