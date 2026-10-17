package com.devcli.llm;

import com.devcli.util.UntrustedContent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只渲染模型请求；原始消息、工具协议和事件日志保持原样。 */
public final class ToolResultContext {
    private ToolResultContext() { }

    public static List<LlmClient.Message> render(List<LlmClient.Message> messages) {
        Map<String, String> sources = new HashMap<>();
        List<LlmClient.Message> rendered = new ArrayList<>();
        for (var message : messages) {
            if (message.toolCalls() != null) {
                for (var call : message.toolCalls()) sources.put(call.id(), call.function().name());
            }
            if (!"tool".equals(message.role())) {
                rendered.add(message);
                continue;
            }
            String source = sources.get(message.toolCallId());
            String content = UntrustedContent.toolResult(source, message.toolCallId(), message.content());
            List<LlmClient.ContentPart> parts = message.contentParts();
            if (message.hasContentParts()) {
                parts = new ArrayList<>();
                for (var part : message.contentParts()) {
                    parts.add(part.isText() ? LlmClient.ContentPart.text(UntrustedContent.toolResult(
                            source, message.toolCallId(), part.text())) : part);
                }
                if (parts.stream().noneMatch(LlmClient.ContentPart::isText)) {
                    parts.add(0, LlmClient.ContentPart.text(content));
                }
            }
            rendered.add(new LlmClient.Message(message.role(), content, message.reasoningContent(),
                    message.toolCalls(), message.toolCallId(), parts, message.source()));
        }
        return List.copyOf(rendered);
    }
}
