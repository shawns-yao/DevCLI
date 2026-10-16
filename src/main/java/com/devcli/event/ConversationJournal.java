package com.devcli.event;

import com.devcli.llm.LlmClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** 追加事件是上下文来源；所有读取重新渲染，变换只在临时副本上执行。 */
public final class ConversationJournal implements Iterable<LlmClient.Message> {
    private final List<RunEvent> events = new ArrayList<>();
    private RunEventSink output = RunEventSink.NO_OP;
    private int published;

    private ConversationJournal() { }

    public ConversationJournal(List<LlmClient.Message> initial) {
        append(new RunEvent.ContextSeed(initial.stream().map(ConversationJournal::entry).toList()));
    }

    public static ConversationJournal restore(List<RunEvent> saved) {
        var journal = new ConversationJournal();
        journal.events.addAll(saved);
        journal.published = saved.size();
        return journal;
    }

    public final class Recording implements AutoCloseable {
        private final RunEventSink previous;
        private Recording(RunEventSink previous) { this.previous = previous; }
        @Override public void close() { output = previous; }
    }

    public Recording recordTo(RunEventSink sink) {
        var scope = new Recording(output);
        output = sink;
        if (sink != RunEventSink.NO_OP) {
            while (published < events.size()) {
                sink.emit(events.get(published));
                published++;
            }
        }
        return scope;
    }

    public void add(LlmClient.Message message) { append(RunEvent.ModelMessage.from(message)); }

    public void addAll(List<LlmClient.Message> messages) { messages.forEach(this::add); }

    public List<RunEvent> events() { return List.copyOf(events); }

    public List<LlmClient.Message> messages() {
        return renderEntries(events).stream().map(value -> value.message().toLlmMessage()).toList();
    }

    public LlmClient.Message get(int index) { return messages().get(index); }
    public int size() { return messages().size(); }
    public boolean isEmpty() { return messages().isEmpty(); }
    public java.util.stream.Stream<LlmClient.Message> stream() { return messages().stream(); }
    @Override public java.util.Iterator<LlmClient.Message> iterator() { return messages().iterator(); }

    public void reset(List<LlmClient.Message> initial) {
        append(new RunEvent.ContextSeed(initial.stream().map(ConversationJournal::entry).toList()));
    }

    public <T> T revise(String reason, Function<List<LlmClient.Message>, T> transform) {
        var before = renderEntries(events);
        List<LlmClient.Message> copy = new ArrayList<>(before.stream()
                .map(value -> value.message().toLlmMessage()).toList());
        T result = transform.apply(copy);
        var after = copy.stream().map(RunEvent.ModelMessageData::from).toList();
        if (!before.stream().map(RunEvent.ContextEntry::message).toList().equals(after)) {
            Map<RunEvent.ModelMessageData, ArrayDeque<String>> reusable = new HashMap<>();
            before.forEach(value -> reusable.computeIfAbsent(value.message(), ignored -> new ArrayDeque<>()).add(value.id()));
            var entries = new ArrayList<RunEvent.ContextEntry>();
            for (var message : after) {
                var ids = reusable.get(message);
                entries.add(ids == null || ids.isEmpty()
                        ? new RunEvent.ContextEntry(UUID.randomUUID().toString(), message)
                        : new RunEvent.ContextEntry(ids.removeFirst(), null));
            }
            append(new RunEvent.ContextProjection(reason, entries));
        }
        return result;
    }

    private void append(RunEvent event) {
        output.emit(event);
        events.add(event);
        if (output != RunEventSink.NO_OP) published = events.size();
    }

    private static RunEvent.ContextEntry entry(LlmClient.Message message) {
        return new RunEvent.ContextEntry(UUID.randomUUID().toString(), RunEvent.ModelMessageData.from(message));
    }

    private static List<RunEvent.ContextEntry> renderEntries(List<RunEvent> events) {
        Map<String, RunEvent.ModelMessageData> values = new HashMap<>();
        List<String> selected = new ArrayList<>();
        for (RunEvent event : events) {
            if (event instanceof RunEvent.ContextSeed seed) {
                selected.clear();
                values.clear();
                seed.entries().forEach(value -> { values.put(value.id(), value.message()); selected.add(value.id()); });
            } else if (event instanceof RunEvent.ModelMessage message) {
                values.put(message.messageId(), message.message());
                selected.add(message.messageId());
            } else if (event instanceof RunEvent.ContextProjection projection) {
                selected.clear();
                for (var value : projection.entries()) {
                    if (value.message() != null) values.put(value.id(), value.message());
                    if (!values.containsKey(value.id())) throw new IllegalArgumentException("投影引用了未记录的消息: " + value.id());
                    selected.add(value.id());
                }
            }
        }
        return selected.stream().map(id -> new RunEvent.ContextEntry(id, values.get(id))).toList();
    }
}
