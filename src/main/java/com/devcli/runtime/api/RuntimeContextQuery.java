package com.devcli.runtime.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 从请求审计读取实际上下文；结构 trace 继续只保存诊断字段。 */
public final class RuntimeContextQuery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RuntimeThreadStore store;

    public RuntimeContextQuery(RuntimeThreadStore store) { this.store = store; }

    public String render(String runId) throws JsonProcessingException {
        List<Request> requests = new ArrayList<>();
        Map<String, Request> pending = new HashMap<>();
        for (var event : store.contextEventsForRun(runId)) {
            JsonNode body = JSON.readTree(event.data());
            String scope = "main";
            String type = event.type();
            if (type.equals("agent.event")) {
                scope = body.path("agent_id").asText();
                type = body.path("event_type").asText();
                body = body.path("event");
            }
            if (type.equals("model.context")) {
                Request request = new Request(event.id(), scope, body);
                requests.add(request);
                pending.put(scope, request);
            } else if (type.equals("model.usage")) {
                Request request = pending.remove(scope);
                // 父级转发的子任务用量不再绑定到已完成的父请求。
                if (request != null) request.usage = body;
            }
        }
        if (requests.isEmpty()) return "运行 " + runId + " 没有已记录的请求上下文（旧记录未关联运行身份）。";
        StringBuilder out = new StringBuilder("运行 ").append(runId).append("，请求 ").append(requests.size()).append('\n');
        for (Request request : requests) {
            out.append("\n请求 event=").append(request.id).append(" agent=").append(request.scope)
                    .append(" iteration=").append(request.context.path("iteration").asInt()).append('\n');
            if (request.usage == null) out.append("Token：未记录（请求未完成或响应没有用量）\n");
            else out.append("Token 输入/输出/缓存：").append(request.usage.path("input_tokens").asInt()).append('/')
                    .append(request.usage.path("output_tokens").asInt()).append('/')
                    .append(request.usage.path("cached_input_tokens").asInt()).append('\n');
            JsonNode display = request.context.deepCopy();
            hideImageBytes(display);
            out.append(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(display)).append('\n');
        }
        return out.toString();
    }

    private static void hideImageBytes(JsonNode node) {
        if (node instanceof ObjectNode object && object.hasNonNull("image_base64")) {
            String base64 = object.remove("image_base64").asText();
            object.put("image_payload", "base64 字符数 " + base64.length() + "；完整载荷保留在原事件");
        }
        node.forEach(RuntimeContextQuery::hideImageBytes);
    }

    private static final class Request {
        final long id;
        final String scope;
        final JsonNode context;
        JsonNode usage;
        Request(long id, String scope, JsonNode context) { this.id = id; this.scope = scope; this.context = context; }
    }
}
