package com.devcli.event;

import com.devcli.llm.LlmClient;

import java.util.Objects;

/** 将模型流式回调转换为共享运行事件，并保留已流出的内容供中断挽救。 */
public final class RunEventStreamListener implements LlmClient.StreamListener {
    private final RunEventSink sink;
    private final StringBuilder reasoning = new StringBuilder();
    private final StringBuilder content = new StringBuilder();

    public RunEventStreamListener(RunEventSink sink) {
        this.sink = Objects.requireNonNullElse(sink, RunEventSink.NO_OP);
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            reasoning.append(delta);
            sink.emit(new RunEvent.ReasoningDelta(delta));
        }
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta != null && !delta.isEmpty()) {
            content.append(delta);
            sink.emit(new RunEvent.MessageDelta(delta));
        }
    }

    /**
     * 交回已流出的部分内容，供取消与失败路径写回历史。
     *
     * 只包含 reasoning 与 content：工具调用可能只流出不完整 JSON，保留它会让历史
     * 出现没有配对结果的 tool_call，下一次请求直接违反消息协议。没有任何内容时
     * 返回 null，调用方不应写入空消息。
     */
    public LlmClient.Message salvagedAssistantMessage() {
        String visible = content.toString();
        String think = reasoning.toString();
        if (visible.isEmpty() && think.isEmpty()) {
            return null;
        }
        return LlmClient.Message.assistant(think, visible);
    }
}
