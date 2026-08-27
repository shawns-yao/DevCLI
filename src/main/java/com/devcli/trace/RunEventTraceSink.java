package com.devcli.trace;

import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.runtime.event.RunEvent;
import com.devcli.runtime.event.RunEventSink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把结构化 {@link RunEvent} 自动桥接到 {@link TraceRecorder}。
 *
 * <p>设计边界：</p>
 * <ul>
 *   <li>trace 是结构诊断记录，不是对话回放：流式 delta 与全量 ModelContext 不落盘；</li>
 *   <li>runId 统一取自当前 {@link RunContext}，与取消树共用同一标识，不再生成第二套 traceId；</li>
 *   <li>只提取定位字段，工具结果正文不写入 trace，避免 trace 文件膨胀为第二份会话存储。</li>
 * </ul>
 */
public final class RunEventTraceSink implements RunEventSink {

    private static final String PHASE_RUN = "run";
    private static final String STANDALONE_RUN = "standalone";
    private static final int MAX_INPUT_CHARS = 200;

    private final TraceRecorder recorder;

    public RunEventTraceSink() {
        this(new TraceRecorder());
    }

    public RunEventTraceSink(TraceRecorder recorder) {
        this.recorder = recorder == null ? new TraceRecorder() : recorder;
    }

    @Override
    public void emit(RunEvent event) {
        if (event == null || isStreamNoise(event)) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", event.type());
        extractFields(event, fields);
        recorder.record(new TraceContext(currentRunId(), PHASE_RUN), "run.event", fields);
    }

    /**
     * 流式增量与全量上下文属于内容回放，量极大且不含结构诊断价值，不进入 trace。
     */
    private static boolean isStreamNoise(RunEvent event) {
        return event instanceof RunEvent.ReasoningDelta
                || event instanceof RunEvent.MessageDelta
                || event instanceof RunEvent.ModelContext
                || event instanceof RunEvent.ModelMessage;
    }

    private static void extractFields(RunEvent event, Map<String, Object> fields) {
        if (event instanceof RunEvent.TurnStarted started) {
            fields.put("input", abbreviate(started.input()));
        } else if (event instanceof RunEvent.ModelUsage usage) {
            fields.put("input_tokens", usage.inputTokens());
            fields.put("output_tokens", usage.outputTokens());
            fields.put("cached_input_tokens", usage.cachedInputTokens());
            fields.put("cost_cny", usage.estimatedCostCny());
        } else if (event instanceof RunEvent.ExecutionStateChanged state) {
            fields.put("iteration", state.iteration());
            fields.put("state", state.state().name());
            fields.put("reason", abbreviate(state.reason()));
        } else if (event instanceof RunEvent.ToolCalls calls) {
            fields.put("tools", calls.calls().stream()
                    .map(RunEvent.ToolCallData::name)
                    .collect(Collectors.joining(",")));
        } else if (event instanceof RunEvent.ToolResults results) {
            results.results().forEach(result -> {
                // 同批工具可能多个，用 tool_<name> 记录状态与耗时，不写结果正文。
                fields.put("tool_" + result.name(),
                        result.status() + "/" + result.errorCode()
                                + "/" + result.elapsedMillis() + "ms");
            });
        } else if (event instanceof RunEvent.FailureGuidance guidance) {
            fields.put("category", guidance.category());
            fields.put("reason", abbreviate(guidance.reason()));
        } else if (event instanceof RunEvent.TurnCompleted completed) {
            fields.put("status", completed.status());
        } else if (event instanceof RunEvent.TurnFailed failed) {
            fields.put("error", abbreviate(failed.error()));
        } else if (event instanceof RunEvent.TurnRejected rejected) {
            fields.put("error", abbreviate(rejected.error()));
        } else if (event instanceof RunEvent.HookInvocationStarted hook) {
            fields.put("hook", hook.hookId());
            fields.put("hook_event", hook.hookEvent());
            fields.put("tool", hook.toolName());
        } else if (event instanceof RunEvent.HookInvocationCompleted hook) {
            fields.put("hook", hook.hookId());
            fields.put("decision", hook.decision());
            fields.put("elapsed_millis", hook.elapsedMillis());
        } else if (event instanceof RunEvent.CheckpointCreated checkpoint) {
            fields.put("pre_tokens", checkpoint.preTokens());
            fields.put("post_tokens", checkpoint.postTokens());
        } else if (event instanceof RunEvent.ContextRefresh refresh) {
            fields.put("scope", refresh.scope());
            fields.put("state", refresh.state().name());
        }
        // QueueUpdated / SessionStateChanged / CustomMessage / CheckpointFailed 等事件仅保留 type，
        // 不额外展开，避免为低频事件维护重复字段映射。
    }

    private static String currentRunId() {
        RunContext context = CancellationContext.currentRun();
        return context == null ? STANDALONE_RUN : context.runId();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= MAX_INPUT_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_INPUT_CHARS) + "...";
    }
}
