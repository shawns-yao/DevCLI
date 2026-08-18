package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolPresentation;
import com.devcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Ensures every model tool call receives exactly one ordered protocol result. */
final class ToolResultReconciler {

    record Issue(String code, String toolCallId, String detail) {
    }

    record Reconciliation(List<ToolRegistry.ToolExecutionResult> results, List<Issue> issues) {
        Reconciliation {
            results = results == null ? List.of() : List.copyOf(results);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    private ToolResultReconciler() {
    }

    static Reconciliation reconcile(
            List<LlmClient.ToolCall> toolCalls,
            List<ToolRegistry.ToolExecutionResult> returnedResults,
            Function<String, ToolPresentation> presentationResolver) {
        List<LlmClient.ToolCall> calls = toolCalls == null ? List.of() : toolCalls;
        List<Issue> issues = new ArrayList<>();
        Set<String> expectedIds = new HashSet<>();
        for (LlmClient.ToolCall call : calls) {
            if (call != null) {
                expectedIds.add(normalizeId(call.id()));
            }
        }

        Map<String, ToolRegistry.ToolExecutionResult> byId = new LinkedHashMap<>();
        if (returnedResults != null) {
            for (ToolRegistry.ToolExecutionResult result : returnedResults) {
                if (result == null) {
                    issues.add(new Issue("NULL_RESULT", "", "工具执行器返回了 null 结果"));
                    continue;
                }
                String id = normalizeId(result.id());
                if (!expectedIds.contains(id)) {
                    issues.add(new Issue("UNKNOWN_RESULT_ID", id,
                            "忽略未在本轮工具调用中声明的结果"));
                    continue;
                }
                if (byId.putIfAbsent(id, result) != null) {
                    issues.add(new Issue("DUPLICATE_RESULT_ID", id,
                            "同一 tool_call_id 返回了多个结果，仅保留第一个"));
                }
            }
        }

        List<ToolRegistry.ToolExecutionResult> reconciled = new ArrayList<>(calls.size());
        Set<String> consumedIds = new HashSet<>();
        for (LlmClient.ToolCall call : calls) {
            if (call == null || call.function() == null) {
                issues.add(new Issue("INVALID_TOOL_CALL", "", "模型返回了不完整的工具调用"));
                continue;
            }
            String id = normalizeId(call.id());
            String name = call.function().name();
            String arguments = call.function().arguments();
            ToolRegistry.ToolInvocation invocation = new ToolRegistry.ToolInvocation(id, name, arguments);
            ToolPresentation presentation = resolvePresentation(presentationResolver, name);

            if (!consumedIds.add(id)) {
                issues.add(new Issue("DUPLICATE_TOOL_CALL_ID", id,
                        "模型在同一轮重复使用了 tool_call_id"));
                reconciled.add(ToolRegistry.ToolExecutionResult.failed(
                        invocation, "模型返回重复 tool_call_id，无法安全复用工具结果", 0, presentation));
                continue;
            }

            ToolRegistry.ToolExecutionResult result = byId.get(id);
            if (result == null) {
                issues.add(new Issue("MISSING_RESULT", id, "工具执行器未返回结果"));
                reconciled.add(ToolRegistry.ToolExecutionResult.failed(
                        invocation, "工具执行器未返回结果，已生成协议配对结果", 0, presentation));
                continue;
            }
            if (!same(name, result.name()) || !same(arguments, result.argumentsJson())) {
                issues.add(new Issue("RESULT_IDENTITY_MISMATCH", id,
                        "工具结果名称或参数与原始调用不一致，已按原始调用纠正"));
            }
            reconciled.add(new ToolRegistry.ToolExecutionResult(
                    id,
                    name,
                    arguments,
                    result.result(),
                    result.elapsedMillis(),
                    result.status(),
                    result.errorCode(),
                    result.retryable(),
                    result.imageParts(),
                    result.sideChannels(),
                    result.presentation() == null ? presentation : result.presentation()));
        }
        return new Reconciliation(reconciled, issues);
    }

    private static ToolPresentation resolvePresentation(
            Function<String, ToolPresentation> resolver, String toolName) {
        ToolPresentation presentation = resolver == null ? null : resolver.apply(toolName);
        return presentation == null ? ToolPresentation.defaultFor(toolName) : presentation;
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim();
    }

    private static boolean same(String left, String right) {
        return java.util.Objects.equals(left, right);
    }
}
