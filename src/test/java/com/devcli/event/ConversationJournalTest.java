package com.devcli.event;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationJournalTest {
    @Test
    void compressionRecordsReferencesAndReplaysWithoutRequestSnapshots() {
        var journal = new ConversationJournal(List.of(LlmClient.Message.system("stable"),
                LlmClient.Message.user("old"), LlmClient.Message.assistant("answer"), LlmClient.Message.user("current")));
        var original = journal.events();
        journal.revise("compaction", messages -> {
            messages.subList(1, 3).clear();
            messages.add(1, LlmClient.Message.internalUser("summary"));
            return null;
        });
        var projection = (RunEvent.ContextProjection) journal.events().getLast();
        assertNull(projection.entries().getFirst().message(), "保留的 system 只存引用");
        assertNull(projection.entries().getLast().message(), "保留的最近消息只存引用");
        assertEquals("summary", projection.entries().get(1).message().content());
        assertEquals(journal.messages(), ConversationJournal.restore(journal.events()).messages());
        assertEquals(List.of("stable", "old", "answer", "current"),
                ConversationJournal.restore(original).messages().stream().map(LlmClient.Message::content).toList());
    }

    @Test
    void failedTransformAndUnchangedTransformAppendNothing() {
        var journal = new ConversationJournal(List.of(LlmClient.Message.user("question")));
        var facts = journal.events();
        journal.revise("unchanged", messages -> null);
        assertThrows(IllegalArgumentException.class, () -> journal.revise("failed", messages -> {
            messages.clear();
            throw new IllegalArgumentException("failed");
        }));
        assertEquals(facts, journal.events());
        assertEquals("question", journal.messages().getFirst().content());
    }

    @Test
    void recordingPublishesBeforeContextChangesAndFlushesInitializationOnce() {
        var journal = new ConversationJournal(List.of(LlmClient.Message.system("system")));
        List<RunEvent> persisted = new ArrayList<>();
        try (var recording = journal.recordTo(event -> {
            if (event instanceof RunEvent.ModelMessage) assertEquals(1, journal.size());
            persisted.add(event);
        })) {
            journal.add(LlmClient.Message.user("input"));
        }
        try (var recording = journal.recordTo(persisted::add)) { }
        assertEquals(2, persisted.size());
        assertEquals(journal.messages(), ConversationJournal.restore(persisted).messages());
    }
}
