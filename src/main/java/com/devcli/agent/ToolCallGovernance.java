package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.devcli.tool.ToolInvocationFingerprint;
import com.devcli.tool.provider.ToolSearchProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Task-local visibility and observation tracking for the shared execution loop. */
final class ToolCallGovernance {
    private static final Set<String> CONTROL_TOOLS = Set.of(
            "search_tools", "read_tool_result", "delegate_task", "delegate_control");
    private static final int MAX_RETAINED_TOOLS = 8;
    private final Deque<String> observations = new ArrayDeque<>();
    private final Set<String> discovered = new LinkedHashSet<>();

    List<LlmClient.Tool> route(List<LlmClient.Tool> available, List<LlmClient.Message> history,
                               LlmClient.ToolChoice choice, String routingInput) {
        if (available == null) {
            discovered.clear();
            return null;
        }
        Set<String> names = available.stream().map(LlmClient.Tool::name)
                .collect(java.util.stream.Collectors.toSet());
        discovered.retainAll(names);
        if (available.size() <= 12
                || available.stream().noneMatch(tool -> "search_tools".equals(tool.name()))) {
            return available;
        }
        String query = routingInput == null ? "" : routingInput;
        if (routingInput == null) {
            for (LlmClient.Message message : history) {
                if (message.source() == LlmClient.MessageSource.USER
                        || message.source() == LlmClient.MessageSource.STEERING) {
                    query = message.content();
                }
            }
        }
        Set<String> selected = new LinkedHashSet<>();
        available.stream().filter(tool -> CONTROL_TOOLS.contains(tool.name()))
                .forEach(tool -> selected.add(tool.name()));
        if (choice != null && choice.hasSpecificTool()) selected.add(choice.toolName());
        selected.addAll(discovered);
        ToolSearchProvider.rankCandidates(available, query).stream()
                .filter(tool -> !CONTROL_TOOLS.contains(tool.name())).limit(5)
                .map(LlmClient.Tool::name).forEach(selected::add);
        return available.stream().filter(tool -> selected.contains(tool.name())).toList();
    }

    boolean observe(List<ToolRegistry.ToolExecutionResult> results, ToolRegistry.ToolSnapshot snapshot) {
        for (var result : results) {
            if ("search_tools".equals(result.name()) && result.status() == ToolStatus.SUCCESS) {
                result.sideChannels().stream().filter(ToolSearchProvider.DiscoveredTools.class::isInstance)
                        .map(ToolSearchProvider.DiscoveredTools.class::cast)
                        .forEach(match -> {
                            for (int index = match.names().size() - 1; index >= 0; index--) {
                                retainTool(match.names().get(index));
                            }
                        });
            } else if (result.status() == ToolStatus.SUCCESS && snapshot != null
                    && snapshot.definitions().stream().anyMatch(tool -> tool.name().equals(result.name()))) {
                retainTool(result.name());
            }
        }
        // Unknown effects, mutations and rich evidence are not proof of an unchanged observation.
        if (snapshot == null || results.isEmpty() || results.stream().anyMatch(result ->
                snapshot.effect(result.name()) != ToolRegistry.ToolEffect.READ_ONLY
                        || result.status() != ToolStatus.SUCCESS || !result.imageParts().isEmpty()
                        || result.sideChannels().stream().anyMatch(channel ->
                        !(channel instanceof com.devcli.tool.FileReadPage)))) {
            observations.clear();
            return false;
        }
        StringBuilder value = new StringBuilder();
        for (var result : results) {
            value.append(digest(ToolInvocationFingerprint.of(result.name(), result.argumentsJson())))
                    .append(digest(result.result())).append(digest(result.sideChannels().toString()));
        }
        observations.addLast(digest(value.toString()));
        while (observations.size() > 12) observations.removeFirst();
        List<String> recent = new ArrayList<>(observations);
        for (int period = 2; period <= 4; period++) {
            int start = recent.size() - 3 * period;
            if (start < 0) continue;
            boolean repeated = true;
            for (int index = start + period; index < recent.size(); index++) {
                if (!recent.get(index).equals(recent.get(index - period))) {
                    repeated = false;
                    break;
                }
            }
            if (repeated) return true;
        }
        return false;
    }

    private void retainTool(String name) {
        if (CONTROL_TOOLS.contains(name)) return;
        discovered.remove(name);
        discovered.add(name);
        while (discovered.size() > MAX_RETAINED_TOOLS) discovered.remove(discovered.iterator().next());
    }

    static String modelResult(ToolRegistry.ToolExecutionResult result) {
        if (result.status() == ToolStatus.SUCCESS) return result.result();
        String action = switch (result.errorCode()) {
            case UNKNOWN_TOOL -> "Call search_tools to discover an available tool; do not repeat the unknown name.";
            case INVALID_ARGUMENTS, SEMANTIC_VALIDATION_FAILED ->
                    "Correct the arguments using the schema and authoritative evidence; do not repeat unchanged arguments.";
            case CAPABILITY_DENIED, SKILL_PERMISSION_DENIED, HITL_REJECTED, POLICY_DENIED ->
                    "Do not retry this denied operation or use another tool to bypass it. Choose an allowed action or request human intervention.";
            case STALE_CONTEXT, STALE_TOOL_SNAPSHOT ->
                    "Refresh the affected context or tool definitions before regenerating the call.";
            case TIMEOUT, MCP_ERROR, EXECUTION_FAILED ->
                    result.retryable()
                            ? "A bounded retry may be considered after checking execution state; never blindly replay a side effect."
                            : "Do not automatically retry; resolve the reported failure first.";
            default -> "Resolve the reported state before choosing the next action.";
        };
        return result.result() + "\n[tool_feedback status=" + result.status()
                + " code=" + result.errorCode() + " retryable=" + result.retryable() + "]\n" + action;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
