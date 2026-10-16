package com.devcli.agent;

import com.devcli.tool.ToolContract;
import com.devcli.tool.ToolStatus;
import com.devcli.tool.ToolInvocationFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/** Task-local repeated-observation detection and actionable tool errors. */
final class ToolCallGovernance {
    private final Deque<String> observations = new ArrayDeque<>();


    boolean observe(List<ToolContract.ToolExecutionResult> results, ToolContract.ToolSnapshot snapshot) {
        // Unknown effects, mutations and rich evidence are not proof of an unchanged observation.
        if (snapshot == null || results.isEmpty() || results.stream().anyMatch(result ->
                snapshot.effect(result.name()) != ToolContract.ToolEffect.READ_ONLY
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


    static String modelResult(ToolContract.ToolExecutionResult result) {
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
