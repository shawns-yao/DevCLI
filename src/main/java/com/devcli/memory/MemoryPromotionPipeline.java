package com.devcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 任务快照的耐久晋升工作流；冲突判定统一落在 LongTermMemory.storeManaged seam。 */
public final class MemoryPromotionPipeline {
    private static final Logger log = LoggerFactory.getLogger(MemoryPromotionPipeline.class);
    private static final ObjectMapper JSON = MemoryJson.mapper();
    private final MemoryPromotionQueue queue;
    private final MemoryCurator curator;
    private final LongTermMemory longTermMemory;
    private final java.util.function.Consumer<MemoryEntry> committedListener;

    public MemoryPromotionPipeline(MemoryPromotionQueue queue, MemoryCurator curator,
                                   LongTermMemory longTermMemory) {
        this(queue, curator, longTermMemory, entry -> {});
    }

    public MemoryPromotionPipeline(MemoryPromotionQueue queue, MemoryCurator curator,
                                   LongTermMemory longTermMemory,
                                   java.util.function.Consumer<MemoryEntry> committedListener) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.curator = Objects.requireNonNull(curator, "curator");
        this.longTermMemory = Objects.requireNonNull(longTermMemory, "longTermMemory");
        this.committedListener = committedListener == null ? entry -> {} : committedListener;
    }

    public String enqueue(TaskMemorySnapshot snapshot) {
        return queue.enqueue(snapshot);
    }

    public boolean processNext() {
        MemoryPromotionQueue.Job job = queue.claimNext().orElse(null);
        if (job == null) return false;
        try {
            IsolatedMemoryCurator.Decision decision = curator.curate(job.snapshot());
            switch (decision.action()) {
                case SKIP -> queue.markSkipped(job.id(), decision.reason());
                case CONFIRM -> queue.markAwaitingConfirmation(job.id(), JSON.writeValueAsString(decision));
                case SAVE -> save(job, decision);
            }
            return true;
        } catch (Exception error) {
            queue.markFailedRetryable(job.id(), truncate(error.getMessage(), 500));
            log.warn("memory promotion failed for {}: {}", job.id(), error.getMessage());
            return false;
        }
    }

    public boolean confirm(String jobId, boolean approved, String editedContent) {
        MemoryPromotionQueue.Job job = queue.find(jobId).orElse(null);
        if (job == null || job.state() != MemoryPromotionQueue.State.AWAITING_CONFIRMATION) {
            return false;
        }
        if (!approved) {
            queue.markSkipped(jobId, "user_rejected");
            return true;
        }
        try {
            IsolatedMemoryCurator.Decision original = JSON.readValue(
                    job.detail(), IsolatedMemoryCurator.Decision.class);
            String content = editedContent == null || editedContent.isBlank()
                    ? original.content() : editedContent.trim();
            IsolatedMemoryCurator.Decision confirmed = new IsolatedMemoryCurator.Decision(
                    IsolatedMemoryCurator.Action.SAVE, original.kind(), content,
                    original.scopeType(), original.scopeKey(), "HIGH",
                    original.sourceRefs(), "user_confirmed");
            return save(job, confirmed);
        } catch (Exception error) {
            queue.markFailedRetryable(jobId, truncate(error.getMessage(), 500));
            return false;
        }
    }

    private boolean save(MemoryPromotionQueue.Job job, IsolatedMemoryCurator.Decision decision) {
        return queue.commitIfState(job.id(), java.util.Set.of(job.state()),
                () -> persist(job, decision));
    }

    private String persist(MemoryPromotionQueue.Job job, IsolatedMemoryCurator.Decision decision) {
        String memoryId = "memory-" + job.id().replace("promotion-", "");
        String scopeType = normalizedScopeType(decision.scopeType());
        String scopeKey = trustedScopeKey(job.snapshot(), decision, scopeType);
        MemoryEntry equivalent = longTermMemory.getAll().stream()
                .filter(MemoryEntry::isRecallable)
                .filter(entry -> entry.getContent().equals(decision.content()))
                .filter(entry -> sameScope(entry, scopeType, scopeKey))
                .findFirst().orElse(null);
        if (equivalent != null) {
            return equivalent.getId();
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "task_curator");
        metadata.put("memory_kind", normalizedKind(decision.kind()));
        metadata.put("scope_type", scopeType);
        metadata.put("scope_key", scopeKey);
        metadata.put(MemoryWriteProtocol.META_SCOPE,
                "GLOBAL".equals(metadata.get("scope_type")) ? "global" : scopeKey);
        metadata.put("source_refs", String.join(",", decision.sourceRefs()));
        metadata.put("curator_confidence", decision.confidence());
        MemoryEvidence evidence = new MemoryEvidence(confidence(decision.confidence()),
                decision.sourceRefs().isEmpty() ? "" : decision.sourceRefs().getFirst(),
                decision.reason(), MemoryEvidence.ReviewState.REVIEWED, List.of());
        MemoryEntry entry = new MemoryEntry(memoryId, decision.content(), MemoryEntry.MemoryType.FACT,
                Instant.now(), Map.copyOf(metadata), MemoryEntry.estimateTokens(decision.content()),
                MemorySubjectExtractor.extract(decision.content(), metadata), true, "",
                MemoryEntry.CURRENT_SCHEMA_VERSION, 1, null, evidence);
        longTermMemory.storeManaged(entry);
        if (longTermMemory.retrieve(memoryId).isEmpty()) {
            throw new IllegalStateException("curated memory was not persisted");
        }
        committedListener.accept(entry);
        return memoryId;
    }

    private static String trustedScopeKey(TaskMemorySnapshot snapshot,
                                          IsolatedMemoryCurator.Decision decision,
                                          String scopeType) {
        String taskScope = snapshot.scopeKey() == null ? "" : snapshot.scopeKey();
        String proposed = decision.scopeKey() == null ? "" : decision.scopeKey();
        return switch (scopeType) {
            case "PROJECT", "REPOSITORY" -> taskScope;
            case "SYMBOL" -> proposed.startsWith(taskScope + "#") ? proposed : taskScope;
            case "USER" -> proposed.isBlank() ? "local-user" : proposed;
            default -> "global";
        };
    }

    private static boolean sameScope(MemoryEntry entry, String scopeType, String scopeKey) {
        String existingType = normalizedScopeType(entry.getMetadata().get("scope_type"));
        String existingKey = entry.getMetadata().getOrDefault("scope_key",
                entry.getMetadata().getOrDefault(MemoryWriteProtocol.META_SCOPE, "global"));
        return existingType.equals(scopeType) && existingKey.equals(scopeKey);
    }

    private static MemoryEvidence.Confidence confidence(String value) {
        try {
            return MemoryEvidence.Confidence.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MemoryEvidence.Confidence.MEDIUM;
        }
    }

    private static String normalizedKind(String value) {
        return switch (value == null ? "" : value.toUpperCase(Locale.ROOT)) {
            case "PREFERENCE", "PROCEDURE", "LESSON", "DECISION" -> value.toUpperCase(Locale.ROOT);
            default -> "FACT";
        };
    }

    private static String normalizedScopeType(String value) {
        return switch (value == null ? "" : value.toUpperCase(Locale.ROOT)) {
            case "USER", "PROJECT", "REPOSITORY", "SYMBOL" -> value.toUpperCase(Locale.ROOT);
            default -> "GLOBAL";
        };
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
