package com.devcli.cli;

import com.devcli.agent.ExecutionReviewPolicy;

/** `/run --review=plan|team [任务]` 的稳定解析结果。 */
record StructuredRunCommand(ExecutionReviewPolicy policy, String task,
                            boolean resume, String checkpointId) {
    StructuredRunCommand {
        if (policy == null) throw new IllegalArgumentException("review policy is required");
        task = task == null ? "" : task.trim();
        checkpointId = checkpointId == null ? "" : checkpointId.trim();
        if (resume && policy != ExecutionReviewPolicy.TEAM_REVIEW) {
            throw new IllegalArgumentException("只有 team review 支持 resume");
        }
    }

    static StructuredRunCommand parse(String payload) {
        String input = payload == null ? "" : payload.trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException(
                    "用法: /run --review=plan|team <任务>，恢复使用 /run --review=team resume [id]");
        }
        String[] parts = input.split("\\s+");
        ExecutionReviewPolicy policy = null;
        int index = 0;
        while (index < parts.length) {
            String part = parts[index];
            String value = null;
            if (part.regionMatches(true, 0, "--review=", 0, 9)) {
                value = part.substring(9);
            } else if (part.equalsIgnoreCase("--review")) {
                if (++index >= parts.length) {
                    throw new IllegalArgumentException("--review 需要 plan 或 team");
                }
                value = parts[index];
            } else {
                break;
            }
            policy = ExecutionReviewPolicy.parse(value);
            if (policy == null) {
                throw new IllegalArgumentException("review 只支持 plan 或 team");
            }
            index++;
        }
        if (policy == null) {
            throw new IllegalArgumentException("必须指定 --review=plan 或 --review=team");
        }
        String remainder = String.join(" ", java.util.Arrays.copyOfRange(parts, index, parts.length)).trim();
        if (remainder.equalsIgnoreCase("resume")) {
            return new StructuredRunCommand(policy, "", true, "");
        }
        if (remainder.regionMatches(true, 0, "resume ", 0, 7)) {
            return new StructuredRunCommand(policy, "", true, remainder.substring(7).trim());
        }
        if (remainder.isBlank()) {
            return new StructuredRunCommand(policy, "", false, "");
        }
        return new StructuredRunCommand(policy, remainder, false, "");
    }
}
