package com.devcli.agent;

import com.devcli.plan.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan 任务执行后的结构化产物和有界摘要。
 */
record PlanTaskExecutionResult(
        Task task,
        String result,
        boolean streamedOutput,
        List<String> modifiedFiles,
        String resultSummary,
        Exception error
) {
    PlanTaskExecutionResult {
        modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        resultSummary = resultSummary == null ? "" : resultSummary;
    }

    static PlanTaskExecutionResult success(Task task, String result, boolean streamedOutput,
                                           List<String> modifiedFiles) {
        return new PlanTaskExecutionResult(
                task, result, streamedOutput, modifiedFiles,
                summarize(result, modifiedFiles, null), null);
    }

    static PlanTaskExecutionResult failure(Task task, Exception error,
                                           List<String> modifiedFiles) {
        return new PlanTaskExecutionResult(
                task, null, false, modifiedFiles,
                summarize(null, modifiedFiles, error), error);
    }

    boolean failed() {
        return error != null;
    }

    private static String summarize(String result, List<String> modifiedFiles, Exception error) {
        List<String> files = modifiedFiles == null ? List.of() : modifiedFiles;
        if (error != null) {
            String errorMessage = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            if (!files.isEmpty()) {
                return compact("任务失败，已产生部分文件修改：" + joinLimitedFiles(files)
                        + "；错误：" + errorMessage, 300);
            }
            return compact("任务失败：" + errorMessage, 300);
        }

        String conclusion = extractConclusion(result);
        if (!conclusion.isBlank()) {
            return compact(conclusion, 300);
        }
        if (!files.isEmpty()) {
            return compact("任务已完成，修改文件：" + joinLimitedFiles(files), 300);
        }
        return "任务已完成，未返回文本结论";
    }

    private static String extractConclusion(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        String normalized = result.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.split("(?<=[。！？.!?])\\s+");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String sentence = part.trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            return normalized;
        }
        int start = Math.max(0, sentences.size() - 2);
        return String.join(" ", sentences.subList(start, sentences.size())).trim();
    }

    private static String joinLimitedFiles(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        int limit = Math.min(files.size(), 6);
        String joined = String.join(", ", files.subList(0, limit));
        if (files.size() > limit) {
            joined += " 等 " + files.size() + " 个文件";
        }
        return joined;
    }

    private static String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
