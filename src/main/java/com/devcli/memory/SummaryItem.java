package com.devcli.memory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 六段摘要中的可演进事实。生命周期是条目元数据，不新增摘要分段。 */
public record SummaryItem(
        String id, String section, String subject, String content, Lifecycle lifecycle,
        int importance, int revision, int compactionCount, String supersededBy,
        List<String> evidenceRefs) {

    public enum Lifecycle { STABLE, ACTIVE, UNRESOLVED, RESOLVED, SUPERSEDED, EXPIRED }

    public SummaryItem {
        id = Objects.requireNonNullElse(id, "");
        section = Objects.requireNonNullElse(section, "");
        subject = Objects.requireNonNullElse(subject, "");
        content = Objects.requireNonNullElse(content, "");
        lifecycle = lifecycle == null ? Lifecycle.ACTIVE : lifecycle;
        importance = Math.max(0, Math.min(100, importance));
        revision = Math.max(1, revision);
        compactionCount = Math.max(0, compactionCount);
        supersededBy = Objects.requireNonNullElse(supersededBy, "");
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }

    public static SummaryItem create(String section, String subject, String content,
                                     Lifecycle lifecycle, int importance, List<String> evidenceRefs) {
        String seed = section + "\0" + subject + "\0" + content;
        String id = "summary-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        return new SummaryItem(id, section, subject, content, lifecycle, importance,
                1, 0, "", evidenceRefs);
    }

    public SummaryItem supersede(String replacementId) {
        return new SummaryItem(id, section, subject, content, Lifecycle.SUPERSEDED,
                importance, revision, compactionCount, replacementId, evidenceRefs);
    }

    public SummaryItem withLifecycle(Lifecycle newLifecycle) {
        return new SummaryItem(id, section, subject, content, newLifecycle,
                importance, revision, compactionCount, supersededBy, evidenceRefs);
    }

    public SummaryItem withCompactionCount(int count) {
        return new SummaryItem(id, section, subject, content, lifecycle,
                importance, revision, count, supersededBy, evidenceRefs);
    }

    public SummaryItem withRevision(int newRevision) {
        return new SummaryItem(id, section, subject, content, lifecycle,
                importance, newRevision, compactionCount, supersededBy, evidenceRefs);
    }

    public SummaryItem withContent(String newContent) {
        return new SummaryItem(id, section, subject, newContent, lifecycle,
                importance, revision, compactionCount, supersededBy, evidenceRefs);
    }

    public boolean isVisible() {
        return lifecycle != Lifecycle.SUPERSEDED && lifecycle != Lifecycle.EXPIRED;
    }
}
