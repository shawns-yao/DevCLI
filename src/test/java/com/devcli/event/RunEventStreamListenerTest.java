package com.devcli.event;

import com.devcli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void salvagesStreamedContentSoInterruptedTurnsStayInHistory() {
        RunEventStreamListener listener = new RunEventStreamListener(RunEventSink.NO_OP);

        listener.onReasoningDelta("先分析");
        listener.onContentDelta("一半的");
        listener.onContentDelta("回答");

        LlmClient.Message salvaged = listener.salvagedAssistantMessage();
        assertNotNull(salvaged);
        assertEquals("assistant", salvaged.role());
        assertEquals("先分析", salvaged.reasoningContent());
        assertEquals("一半的回答", salvaged.content());
        // 取消后不会再执行工具：带 tool_call 的消息会让下一次请求缺少配对结果。
        assertTrue(salvaged.toolCalls() == null || salvaged.toolCalls().isEmpty());
    }

    @Test
    void returnsNullWhenNothingWasStreamed() {
        RunEventStreamListener listener = new RunEventStreamListener(RunEventSink.NO_OP);

        listener.onReasoningDelta(null);
        listener.onContentDelta("");

        assertNull(listener.salvagedAssistantMessage());
    }
}
