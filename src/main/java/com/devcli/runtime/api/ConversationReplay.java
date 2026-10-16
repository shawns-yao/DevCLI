package com.devcli.runtime.api;

import com.devcli.event.RunEvent;
import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 从可见分支事件重建上下文或原始消息；不执行工具，也不修改日志。 */
final class ConversationReplay {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConversationReplay() { }

    static List<LlmClient.Message> context(List<RuntimeEvent> events,
                                          List<LlmClient.Message> baseline, long after) {
        var journal = journalEvents(events);
        if (!journal.isEmpty()) {
            Map<String, LlmClient.Message> results = new HashMap<>();
            for (var event : events) {
                if (event.type().equals("turn.started")) results.clear();
                if (event.type().equals("tool.result.recorded")) {
                    var root = data(event);
                    var result = root.path("result");
                    String id = result.path("id").asText();
                    results.put(id, new LlmClient.Message("tool", result.path("result").asText(), null,
                            null, id, RunEventJsonCodec.contentParts(root.path("images"))));
                }
            }
            return completeToolPairs(com.devcli.event.ConversationJournal.restore(journal).messages(), results, false);
        }
        return replay(events, baseline, after, false);
    }

    static List<RunEvent> journalEvents(List<RuntimeEvent> events) {
        List<RunEvent> journal = new ArrayList<>();
        boolean initialized = false;
        for (var event : events) {
            if (event.type().equals("context.seed")) initialized = true;
            if (initialized) RunEventJsonCodec.decodeConversationEvent(event.type(), event.data()).ifPresent(journal::add);
        }
        return List.copyOf(journal);
    }

    static List<LlmClient.Message> original(List<RuntimeEvent> events) {
        return replay(events, List.of(), 0, true);
    }

    static List<String> userInputs(List<RuntimeEvent> events) {
        List<String> inputs = new ArrayList<>();
        for (var event : events) {
            if (event.type().equals("turn.started")) {
                inputs.add(data(event).path("input").asText());
            } else if (event.type().equals("model.message")) {
                JsonNode message = data(event).path("message");
                if (List.of("STEERING", "FOLLOW_UP").contains(message.path("source").asText())) {
                    inputs.add(message.path("content").asText());
                }
            }
        }
        return List.copyOf(inputs);
    }

    private static List<LlmClient.Message> replay(List<RuntimeEvent> events,
                                                 List<LlmClient.Message> baseline, long after,
                                                 boolean original) {
        List<LlmClient.Message> messages = new ArrayList<>(baseline);
        Map<String, LlmClient.Message> results = new HashMap<>();
        String input = "";
        StringBuilder output = new StringBuilder();
        boolean recorded = false;
        for (var event : events) {
            if (event.id() <= after) continue;
            switch (event.type()) {
                case "context.seed" -> {
                    if (original && messages.isEmpty()) {
                        var seed = (RunEvent.ContextSeed) RunEventJsonCodec.decodeConversationEvent(event.type(), event.data()).orElseThrow();
                        for (var value : seed.entries()) messages.add(value.message().toLlmMessage());
                    }
                }
                case "turn.started" -> {
                    appendTextTurn(messages, input, output, recorded);
                    messages = new ArrayList<>(completeToolPairs(messages, results, original));
                    results.clear();
                    input = data(event).path("input").asText("");
                    output.setLength(0);
                    recorded = false;
                }
                case "model.context" -> {
                    var context = RunEventJsonCodec.decodeModelContext(event.data()).orElseThrow(
                            () -> new IllegalStateException("无法重放上下文事件: " + event.id()));
                    if (!original || messages.isEmpty()) {
                        messages.clear();
                        messages.addAll(context.toLlmMessages());
                        recorded = true;
                    }
                }
                case "model.message" -> {
                    var message = RunEventJsonCodec.decodeModelMessage(event.data()).orElseThrow(
                            () -> new IllegalStateException("无法重放消息事件: " + event.id())).message().toLlmMessage();
                    if (original && !recorded && !input.isBlank() && !"user".equals(message.role())) {
                        messages.add(LlmClient.Message.user(input));
                    }
                    messages.add(original && "tool".equals(message.role())
                            ? results.getOrDefault(message.toolCallId(), message) : message);
                    recorded = true;
                }
                case "tool.result.recorded" -> {
                    JsonNode root = data(event);
                    JsonNode result = root.path("result");
                    String id = result.path("id").asText();
                    results.put(id, new LlmClient.Message("tool", result.path("result").asText(),
                            null, null, id, RunEventJsonCodec.contentParts(root.path("images"))));
                }
                case "message.delta" -> output.append(data(event).path("content").asText(""));
                case "tool.results" -> {
                    for (JsonNode result : data(event).path("results")) {
                        String id = result.path("id").asText();
                        results.putIfAbsent(id, LlmClient.Message.tool(id, result.path("result").asText()));
                    }
                }
                case "turn.completed", "turn.failed" -> {
                    appendTextTurn(messages, input, output, recorded);
                    input = "";
                    output.setLength(0);
                }
                default -> { /* 子 Agent 和诊断事件不进入父上下文。 */ }
            }
        }
        appendTextTurn(messages, input, output, recorded);
        return completeToolPairs(messages, results, original);
    }

    private static void appendTextTurn(List<LlmClient.Message> messages, String input,
                                       StringBuilder output, boolean recorded) {
        if (recorded || input.isBlank()) return;
        messages.add(LlmClient.Message.user(input));
        if (!output.isEmpty()) messages.add(LlmClient.Message.assistant(output.toString()));
    }

    private static List<LlmClient.Message> completeToolPairs(List<LlmClient.Message> messages,
                                                            Map<String, LlmClient.Message> results, boolean original) {
        List<LlmClient.Message> paired = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            LlmClient.Message message = messages.get(i);
            paired.add(message);
            if (message.toolCalls() == null || message.toolCalls().isEmpty()) continue;
            var present = new HashSet<String>();
            while (i + 1 < messages.size() && "tool".equals(messages.get(i + 1).role())) {
                LlmClient.Message tool = messages.get(++i);
                paired.add(tool);
                present.add(tool.toolCallId());
            }
            for (var call : message.toolCalls()) {
                if (!present.contains(call.id())) {
                    LlmClient.Message result = results.get(call.id());
                    if (result != null) {
                        int preview = com.devcli.tool.ToolResultSizeManager.PERSIST_PREVIEW_CHARS;
                        paired.add(original || result.content().length() <= preview ? result
                                : LlmClient.Message.tool(call.id(), result.content().substring(0, preview)
                                + "\n[恢复结果预览；完整正文保留在会话事件日志中。请收窄查询或重新读取相关文件。]"));
                    } else if (!original) {
                        paired.add(LlmClient.Message.tool(call.id(),
                                "执行在结果记录前中断，状态未知。先核对实际状态，再决定是否重新执行；不要直接重复副作用操作。"));
                    }
                }
            }
        }
        return List.copyOf(paired);
    }

    private static JsonNode data(RuntimeEvent event) {
        try {
            return JSON.readTree(event.data());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("无法重放事件: " + event.id(), error);
        }
    }
}
