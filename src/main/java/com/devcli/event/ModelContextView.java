package com.devcli.event;

import com.devcli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

/** 运行事件的模型视图。快照替换只影响投影，不修改已经发出的消息事实。 */
public final class ModelContextView implements RunEventSink {
    private final List<LlmClient.Message> messages = new ArrayList<>();

    @Override
    public synchronized void emit(RunEvent event) {
        if (event instanceof RunEvent.ModelContext context) {
            messages.clear();
            messages.addAll(context.toLlmMessages());
        } else if (event instanceof RunEvent.ModelMessage message) {
            messages.add(message.message().toLlmMessage());
        }
    }

    public synchronized List<LlmClient.Message> messages() {
        return List.copyOf(messages);
    }
}
