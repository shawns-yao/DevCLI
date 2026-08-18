package com.devcli.hook;

import com.devcli.hitl.ApprovalPolicy;
import com.devcli.hitl.HitlToolRegistry;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook 执行器。Hook 只能调用已注册工具，所有调用继续经过 ToolRegistry 管线。
 */
public final class HookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry;
    private final Map<HookEvent, List<HookDefinition>> hooksByEvent;
    private final AtomicLong invocationSequence = new AtomicLong();
    private final CopyOnWriteArrayList<CompletableFuture<DispatchResult>> pending =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Throwable> backgroundFailures =
            new CopyOnWriteArrayList<>();
    private volatile RunEventSink eventSink = RunEventSink.NO_OP;

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

    public void setEventSink(RunEventSink eventSink) {
        this.eventSink = eventSink == null ? RunEventSink.NO_OP : eventSink;
    }

    public DispatchResult dispatch(HookEvent event, HookContext context) {
        List<HookDefinition> hooks = hooksByEvent.get(event);
        if (hooks == null || hooks.isEmpty()) return DispatchResult.continueExecution();
        HookContext effectiveContext = context == null ? HookContext.empty() : context;
        List<InvocationResult> results = new ArrayList<>(hooks.size());
        Decision merged = Decision.CONTINUE;
        for (HookDefinition hook : hooks) {
            InvocationResult result = execute(hook, event, effectiveContext);
            results.add(result);
            merged = Decision.merge(merged, result.decision());
        }
        DispatchResult dispatchResult = new DispatchResult(merged, results);
        if (merged == Decision.BLOCK) {
            String errors = results.stream()
                    .filter(result -> result.decision() == Decision.BLOCK)
                    .map(result -> result.hookId() + ": " + result.error())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("required hook failed");
            throw new HookExecutionException("Hook 执行失败并阻断: event="
                    + event.wireName() + ", " + errors, dispatchResult);
        }
        return dispatchResult;
    }

    public CompletableFuture<DispatchResult> dispatchAsync(
            HookEvent event, HookContext context, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        CompletableFuture<DispatchResult> future = CompletableFuture.supplyAsync(
                () -> dispatch(event, context), executor);
        pending.add(future);
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                backgroundFailures.add(error instanceof CompletionException && error.getCause() != null
                        ? error.getCause()
                        : error);
            }
            pending.remove(future);
        });
        return future;
    }

    public void awaitPending() {
        while (!pending.isEmpty()) {
            for (CompletableFuture<DispatchResult> future : List.copyOf(pending)) {
                future.handle((result, error) -> null).join();
            }
        }
        if (!backgroundFailures.isEmpty()) {
            Throwable failure = backgroundFailures.remove(0);
            backgroundFailures.clear();
            if (failure instanceof HookExecutionException hookFailure) {
                throw hookFailure;
            }
            throw new HookExecutionException(Objects.requireNonNullElse(
                    failure.getMessage(), failure.getClass().getSimpleName()));
        }
    }

    private InvocationResult execute(HookDefinition hook, HookEvent event, HookContext context) {
        String invocationId = hookInvocationId(hook, event, context);
        long startedAt = System.nanoTime();
        eventSink.emit(new RunEvent.HookInvocationStarted(
                invocationId, hook.id(), event.wireName(), hook.tool()));
        String error = "";
        Decision decision = Decision.CONTINUE;
        String status = "SUCCESS";
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
                error = output == null
                        ? "工具没有返回结果"
                        : output.errorCode() + ": " + output.text();
                decision = failureDecision(hook);
                status = "FAILED";
            }
        } catch (Exception e) {
            error = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            decision = failureDecision(hook);
            status = "FAILED";
        }
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt);
        InvocationResult result = new InvocationResult(
                invocationId, hook.id(), event.wireName(), hook.tool(),
                status, decision, elapsedMillis, error);
        eventSink.emit(new RunEvent.HookInvocationCompleted(
                result.invocationId(), result.hookId(), result.hookEvent(), result.toolName(),
                result.status(), result.decision().name(), result.elapsedMillis(), result.error()));
        if (decision == Decision.WARN) {
            log.warn("Hook 执行警告: id={}, event={}, tool={}, error={}",
                    hook.id(), event.wireName(), hook.tool(), error);
        }
        return result;
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

    private static Decision failureDecision(HookDefinition hook) {
        return hook.failureMode() == HookDefinition.FailureMode.REQUIRED
                ? Decision.BLOCK
                : Decision.WARN;
    }

    private String hookInvocationId(HookDefinition hook, HookEvent event, HookContext context) {
        String runId = context.runId().isBlank() ? "local" : context.runId();
        return runId + ":" + hook.id() + ":" + event.wireName()
                + ":" + invocationSequence.incrementAndGet();
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

    public enum Decision {
        CONTINUE,
        WARN,
        BLOCK;

        static Decision merge(Decision left, Decision right) {
            Decision first = left == null ? CONTINUE : left;
            Decision second = right == null ? CONTINUE : right;
            return first.ordinal() >= second.ordinal() ? first : second;
        }
    }

    public record InvocationResult(
            String invocationId,
            String hookId,
            String hookEvent,
            String toolName,
            String status,
            Decision decision,
            long elapsedMillis,
            String error) {
        public InvocationResult {
            invocationId = Objects.requireNonNullElse(invocationId, "");
            hookId = Objects.requireNonNullElse(hookId, "");
            hookEvent = Objects.requireNonNullElse(hookEvent, "");
            toolName = Objects.requireNonNullElse(toolName, "");
            status = Objects.requireNonNullElse(status, "");
            decision = decision == null ? Decision.CONTINUE : decision;
            elapsedMillis = Math.max(0, elapsedMillis);
            error = Objects.requireNonNullElse(error, "");
        }
    }

    public record DispatchResult(Decision decision, List<InvocationResult> invocations) {
        public DispatchResult {
            decision = decision == null ? Decision.CONTINUE : decision;
            invocations = invocations == null ? List.of() : List.copyOf(invocations);
        }

        static DispatchResult continueExecution() {
            return new DispatchResult(Decision.CONTINUE, List.of());
        }
    }

    public static final class HookExecutionException extends RuntimeException {
        private final DispatchResult result;

        public HookExecutionException(String message) {
            this(message, DispatchResult.continueExecution());
        }

        public HookExecutionException(String message, DispatchResult result) {
            super(message);
            this.result = result == null ? DispatchResult.continueExecution() : result;
        }

        public DispatchResult result() {
            return result;
        }
    }
}
