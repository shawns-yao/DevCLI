package com.devcli.memory;

import com.devcli.tool.CommandResultMetadata;
import com.devcli.tool.ToolStatus;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.devcli.memory.SessionMemory.*;

/** 工具证据的分类、摘要和实体引用，不持有会话状态。 */
final class SessionEvidenceText {
    private SessionEvidenceText() {}
    static final Pattern PATH_ARG = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    static EvidenceKind classifyEvidence(String toolName, String result, ToolStatus status,
                                                 CommandResultMetadata commandMetadata) {
        if (status != null && status != ToolStatus.SUCCESS) {
            return EvidenceKind.FAILURE;
        }
        if (commandMetadata != null
                && (commandMetadata.timedOut() || commandMetadata.cancelled()
                || commandMetadata.exitCode() != 0)) {
            return EvidenceKind.FAILURE;
        }
        String normalized = result == null ? "" : result.toLowerCase(Locale.ROOT);
        if (normalized.contains("negativefact") || "write_file".equals(toolName)
                || "edit_file".equals(toolName)
                || "delete_files".equals(toolName)
                || "create_project".equals(toolName) || isPassingTestResult(toolName, normalized)) {
            return EvidenceKind.CRITICAL;
        }
        if (looksLikeFailure(normalized)) return EvidenceKind.FAILURE;
        if ("execute_command".equals(toolName)) return EvidenceKind.MILESTONE;
        if ("read_file".equals(toolName) || "list_dir".equals(toolName)
                || "search_code".equals(toolName) || "grep_code".equals(toolName)) {
            return EvidenceKind.REGENERABLE;
        }
        return EvidenceKind.ORDINARY;
    }

    static int baselineImportance(EvidenceKind kind, String toolName, String result) {
        return switch (kind) {
            case CRITICAL -> 95;
            case FAILURE -> 82;
            case MILESTONE -> 65;
            case ORDINARY -> 40;
            case REGENERABLE -> 18;
        };
    }

    static boolean looksLikeFailure(String normalized) {
        return normalized.contains("toolstatus=error") || normalized.contains("execution_failed")
                || normalized.contains("build failure") || normalized.contains("失败")
                || normalized.contains("exception") || normalized.contains("error:");
    }

    static boolean isPassingTestResult(String toolName, String normalized) {
        return "execute_command".equals(toolName)
                && (normalized.contains("build success") || normalized.contains("tests run:")
                && !normalized.contains("failures: 1") && !normalized.contains("errors: 1"));
    }

    static String evidenceReference(String toolName, String argsJson, String result) {
        if (MicrocompactBoundary.isBoundary(result)) {
            String storedPath = MicrocompactBoundary.storedPath(result);
            if (!storedPath.isBlank()) return storedPath;
        }
        String path = extractPath(argsJson);
        if (!path.isBlank()) return path;
        return toolName + ":" + Integer.toHexString((argsJson + "\n" + result).hashCode());
    }

    static String normalizeEvidenceResult(EvidenceKind kind, String toolName, String argsJson,
                                                  String result, String reference) {
        return switch (kind) {
            case FAILURE -> "尝试内容: " + toolName + " " + truncate(argsJson, 180)
                    + "\n失败原因: " + truncate(result.replaceAll("\\s+", " "), 900)
                    + "\n避免重复: 未改变前置条件前不要重复同一调用"
                    + "\n引用: " + reference;
            case REGENERABLE -> "可再生成证据，摘要: "
                    + truncate(result.replaceAll("\\s+", " "), 260) + "\n引用: " + reference;
            case ORDINARY -> truncate(result, 700) + "\n引用: " + reference;
            case MILESTONE -> truncate(result, 1_000) + "\n引用: " + reference;
            case CRITICAL -> truncate(result, TOOL_RESULT_RENDER_CHARS) + "\n引用: " + reference;
        };
    }

    static String extractPath(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        Matcher matcher = PATH_ARG.matcher(argsJson);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String truncate(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "...(truncated)";
    }
}
