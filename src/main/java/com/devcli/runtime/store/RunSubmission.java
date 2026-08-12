package com.devcli.runtime.store;

import java.nio.file.Path;

/** 创建 Run 所需的稳定输入。 */
public record RunSubmission(
        String runId,
        SubmissionSource source,
        String threadId,
        Path projectPath,
        String prompt,
        String idempotencyKey,
        String budgetStateJson
) {
    public RunSubmission {
        source = source == null ? SubmissionSource.CLI : source;
        threadId = text(threadId);
        prompt = requireText(prompt, "prompt");
        idempotencyKey = text(idempotencyKey);
        budgetStateJson = text(budgetStateJson);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
