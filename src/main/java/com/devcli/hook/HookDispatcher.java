package com.devcli.hook;

import com.devcli.hitl.ApprovalPolicy;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hook 执行器。Hook 只能调用已注册工具，所有调用继续经过 ToolRegistry 管线。
 */
public final class HookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry;
    private final Map<HookEvent, List<HookDefinition>> hooksByEvent;

    private HookDispatcher(ToolRegistry registry, List<HookDefinition> hooks) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Map<HookEvent, List<HookDefinition>> grouped = new EnumMap<>(HookEvent.class);
        for (HookEvent event : HookEvent.values()) {
            List<HookDefinition> matching = hooks == null ? List.of() : hooks.stream()
                    .filter(HookDefinition::enabled)
                    .filter(hook -> hook.event() == event)
                    .toList();
            if (!matching.isEmpty()) grouped.put(event, matching);
        }
        this.hooksByEvent = Map.copyOf(grouped);
    }

    public static HookDispatcher load(ToolRegistry registry) {
        Path projectRoot = Path.of(registry.getProjectPath());
        return new HookDispatcher(registry, HookConfigLoader.load(projectRoot));
    }

    public static HookDispatcher create(ToolRegistry registry, List<HookDefinition> hooks) {
        return new HookDispatcher(registry, hooks);
    }

    public boolean isEmpty() {
        return hooksByEvent.isEmpty();
    }

    public void dispatch(HookEvent event, HookContext context) {
        List<HookDefinition> hooks = hooksByEvent.get(event);
        if (hooks == null || hooks.isEmpty()) return;
        HookContext effectiveContext = context == null ? HookContext.empty() : context;
        for (HookDefinition hook : hooks) {
            execute(hook, event, effectiveContext);
        }
    }

    private void execute(HookDefinition hook, HookEvent event, HookContext context) {
        try {
            ToolRegistry.ToolEffect effect = registry.toolEffect(hook.tool());
            enforceEffectPolicy(hook, effect);
            String argumentsJson = MAPPER.writeValueAsString(
                    substitute(hook.arguments(), placeholders(event, context)));
            ToolOutput output = effect == ToolRegistry.ToolEffect.READ_ONLY
                    || effect == ToolRegistry.ToolEffect.LOCAL_CONTEXT
                    ? registry.runWithToolAccess(
                            ToolRegistry.ToolAccessScope.READ_ONLY,
                            () -> registry.executeToolOutput(hook.tool(), argumentsJson))
                    : registry.executeToolOutput(hook.tool(), argumentsJson);
            if (output == null || !output.isSuccess()) {
                fail(hook, event, output == null
                        ? "工具没有返回结果"
                        : output.errorCode() + ": " + output.text());
            }
        } catch (HookExecutionException e) {
            throw e;
        } catch (Exception e) {
            fail(hook, event, e.getMessage());
        }
    }

    private void enforceEffectPolicy(HookDefinition hook, ToolRegistry.ToolEffect effect) {
        if (effect == ToolRegistry.ToolEffect.READ_ONLY
                || effect == ToolRegistry.ToolEffect.LOCAL_CONTEXT) {
            return;
        }
        if (!hook.allowSideEffects()) {
            throw new IllegalStateException("Hook 未显式允许副作用");
        }
        if (!(registry instanceof HitlToolRegistry hitlRegistry)
                || !hitlRegistry.getHitlHandler().isEnabled()) {
            throw new IllegalStateException("副作用 Hook 需要启用 HITL");
        }
        if (!ApprovalPolicy.requiresApproval(hook.tool())) {
            throw new IllegalStateException("副作用 Hook 工具没有逐次审批策略");
        }
    }

    private void fail(HookDefinition hook, HookEvent event, String message) {
        String detail = "Hook 执行失败: id=" + hook.id()
                + ", event=" + event.wireName()
                + ", tool=" + hook.tool()
                + ", error=" + Objects.requireNonNullElse(message, "unknown");
        if (hook.failureMode() == HookDefinition.FailureMode.REQUIRED) {
            throw new HookExecutionException(detail);
        }
        log.warn(detail);
    }

    private static Map<String, String> placeholders(HookEvent event, HookContext context) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("event", event.wireName());
        values.put("project", context.projectPath());
        values.put("run_id", context.runId());
        values.put("iteration", Integer.toString(context.iteration()));
        values.put("tool_name", context.toolName());
        values.put("tool_call_id", context.toolCallId());
        values.put("status", context.status());
        return values;
    }

    private static JsonNode substitute(JsonNode node, Map<String, String> values) {
        if (node == null || node.isNull()) return MAPPER.createObjectNode();
        if (node.isTextual()) {
            String value = node.asText();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                value = value.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            return TextNode.valueOf(value);
        }
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    result.set(entry.getKey(), substitute(entry.getValue(), values)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(item -> result.add(substitute(item, values)));
            return result;
        }
        return node.deepCopy();
    }

    public record HookContext(
            String projectPath,
            String runId,
            int iteration,
            String toolName,
            String toolCallId,
            String status) {
        public HookContext {
            projectPath = Objects.requireNonNullElse(projectPath, "");
            runId = Objects.requireNonNullElse(runId, "");
            toolName = Objects.requireNonNullElse(toolName, "");
            toolCallId = Objects.requireNonNullElse(toolCallId, "");
            status = Objects.requireNonNullElse(status, "");
        }

        public static HookContext empty() {
            return new HookContext("", "", 0, "", "", "");
        }

        public HookContext withIteration(int value) {
            return new HookContext(projectPath, runId, value, toolName, toolCallId, status);
        }

        public HookContext withTool(String name, String callId, String resultStatus) {
            return new HookContext(projectPath, runId, iteration, name, callId, resultStatus);
        }
    }

    public static final class HookExecutionException extends RuntimeException {
        public HookExecutionException(String message) {
            super(message);
        }
    }
}
