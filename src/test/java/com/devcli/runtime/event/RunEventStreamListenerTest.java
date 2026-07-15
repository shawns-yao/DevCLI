package com.devcli.runtime.event;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunEventStreamListenerTest {

    @Test
    void convertsModelDeltasAndCanProjectBackToLegacyListener() {
        List<RunEvent> events = new ArrayList<>();
        List<String> projected = new ArrayList<>();
        LlmClient.StreamListener legacy = new LlmClient.StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                projected.add("reasoning:" + delta);
            }

            @Override
            public void onContentDelta(String delta) {
                projected.add("content:" + delta);
            }
        };
        RunEventSink sink = RunEventSink.composite(events::add, RunEventSink.fromStreamListener(legacy));
        RunEventStreamListener listener = new RunEventStreamListener(sink);

        listener.onReasoningDelta("分析");
        listener.onContentDelta("答案");
        listener.onContentDelta("");

        assertEquals(List.of(RunEvent.ReasoningDelta.class, RunEvent.MessageDelta.class),
                events.stream().map(Object::getClass).toList());
        assertEquals(List.of("reasoning:分析", "content:答案"), projected);
    }
}
