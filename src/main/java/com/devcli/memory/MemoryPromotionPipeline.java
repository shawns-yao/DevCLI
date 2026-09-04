package com.devcli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcli.policy.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
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
            IsolatedMemoryCurator.Decision original = curator.curate(job.snapshot());
            IsolatedMemoryCurator.Decision decision = sanitizeDecision(original);
            boolean redacted = decisionContentChanged(original, decision);
            switch (decision.action()) {
                case SKIP -> queue.markSkipped(job.id(), decision.reason());
                case CONFIRM -> queue.markAwaitingConfirmation(job.id(), JSON.writeValueAsString(decision));
                case SAVE -> {
                    if (!redacted && autoSaveEligible(job.snapshot(), decision)) {
                        save(job, decision, MemoryEvidence.ReviewState.CURATED, false);
                    } else {
                        IsolatedMemoryCurator.Decision pending = new IsolatedMemoryCurator.Decision(
                                IsolatedMemoryCurator.Action.CONFIRM, decision.kind(), decision.content(),
                                decision.scopeType(), decision.scopeKey(), decision.confidence(),
                                decision.sourceRefs(), redacted
                                        ? "curator_output_redacted_requires_confirmation"
                                        : "auto_save_requires_high_confidence_and_evidence");
                        queue.markAwaitingConfirmation(job.id(), JSON.writeValueAsString(pending));
                    }
                }
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
            return save(job, confirmed, MemoryEvidence.ReviewState.REVIEWED, true);
        } catch (Exception error) {
            queue.markFailedRetryable(jobId, truncate(error.getMessage(), 500));
            return false;
        }
    }

    private boolean save(MemoryPromotionQueue.Job job, IsolatedMemoryCurator.Decision decision,
                         MemoryEvidence.ReviewState reviewState, boolean validated) {
        return queue.commitIfState(job.id(), java.util.Set.of(job.state()),
                () -> persist(job, decision, reviewState, validated));
    }

    private String persist(MemoryPromotionQueue.Job job, IsolatedMemoryCurator.Decision decision,
                           MemoryEvidence.ReviewState reviewState, boolean validated) {
        IsolatedMemoryCurator.Decision safeDecision = sanitizeDecision(decision);
        if (safeDecision.content().isBlank()) {
            throw new IllegalStateException("curated memory content is empty after redaction");
        }
        String memoryId = "memory-" + job.id().replace("promotion-", "");
        String scopeType = normalizedScopeType(safeDecision.scopeType());
        String scopeKey = trustedScopeKey(job.snapshot(), safeDecision, scopeType);
        String sourceRef = safeDecision.sourceRefs().stream()
                .filter(ref -> job.snapshot().sourceExcerpt(ref).isPresent())
                .findFirst().orElse("");
        String sourceQuote = sourceRef.isBlank() ? ""
                : job.snapshot().sourceExcerpt(sourceRef).orElse("");
        MemoryEntry equivalent = longTermMemory.getAll().stream()
                .filter(MemoryEntry::isRecallable)
                .filter(entry -> entry.getContent().equals(safeDecision.content()))
                .filter(entry -> sameScope(entry, scopeType, scopeKey))
                .findFirst().orElse(null);
        if (equivalent != null) {
            if (validated) longTermMemory.recordValidated(equivalent.getId(), Instant.now());
            return equivalent.getId();
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "task_curator");
        metadata.put("memory_kind", normalizedKind(safeDecision.kind()));
        metadata.put("scope_type", scopeType);
        metadata.put("scope_key", scopeKey);
        metadata.put(MemoryWriteProtocol.META_SCOPE,
                "GLOBAL".equals(metadata.get("scope_type")) ? "global" : scopeKey);
        metadata.put("source_refs", String.join(",", safeDecision.sourceRefs()));
        metadata.put("source_ref", sourceRef);
        metadata.put("source_task_id", job.snapshot().taskId());
        metadata.put("source_captured_at", job.snapshot().capturedAt().toString());
        metadata.put("source_availability", "SNAPSHOT");
        metadata.put("source_quote_sha256", sha256(sourceQuote));
        metadata.put("curator_confidence", safeDecision.confidence());
        SensitiveDataRedactor.RedactionResult redaction =
                SensitiveDataRedactor.inspect(safeDecision.content());
        if (redaction.changed()) {
            metadata.put("redacted", "true");
            metadata.put("redacted_types", redaction.removedTypesCsv());
        }
        MemoryEvidence evidence = new MemoryEvidence(confidence(safeDecision.confidence()),
                sourceQuote, safeDecision.reason(), reviewState, List.of());
        MemoryEntry entry = new MemoryEntry(memoryId, safeDecision.content(), MemoryEntry.MemoryType.FACT,
                Instant.now(), Map.copyOf(metadata), MemoryEntry.estimateTokens(safeDecision.content()),
                MemorySubjectExtractor.extract(safeDecision.content(), metadata), true, "",
                MemoryEntry.CURRENT_SCHEMA_VERSION, 1, null, evidence);
        longTermMemory.storeManaged(entry);
        if (longTermMemory.retrieve(memoryId).isEmpty()) {
            throw new IllegalStateException("curated memory was not persisted");
        }
        if (validated && !longTermMemory.recordValidated(memoryId, Instant.now())) {
            throw new IllegalStateException("confirmed memory validation was not persisted");
        }
        MemoryEntry committed = longTermMemory.retrieve(memoryId)
                .orElseThrow(() -> new IllegalStateException("curated memory disappeared after persistence"));
        committedListener.accept(committed);
        return memoryId;
    }

    private static IsolatedMemoryCurator.Decision sanitizeDecision(
            IsolatedMemoryCurator.Decision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("curator returned null decision");
        }
        SensitiveDataRedactor.RedactionResult content =
                SensitiveDataRedactor.inspect(decision.content());
        SensitiveDataRedactor.RedactionResult reason =
                SensitiveDataRedactor.inspect(decision.reason());
        return new IsolatedMemoryCurator.Decision(
                decision.action(), decision.kind(), content.sanitizedText(),
                decision.scopeType(), decision.scopeKey(), decision.confidence(),
                decision.sourceRefs(), reason.sanitizedText());
    }

    private static boolean decisionContentChanged(
            IsolatedMemoryCurator.Decision original,
            IsolatedMemoryCurator.Decision sanitized) {
        return !Objects.equals(original.content(), sanitized.content())
                || !Objects.equals(original.reason(), sanitized.reason());
    }

    private static boolean autoSaveEligible(TaskMemorySnapshot snapshot,
                                            IsolatedMemoryCurator.Decision decision) {
        return "HIGH".equals(decision.confidence())
                && decision.sourceRefs().stream().anyMatch(ref -> snapshot.sourceExcerpt(ref).isPresent());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
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
