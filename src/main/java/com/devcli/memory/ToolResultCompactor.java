package com.devcli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.devcli.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.devcli.memory.ConversationHistoryCompactor.*;

/** 按 Token 保留预算回收旧工具结果，原文成功落盘后才替换引用。 */
final class ToolResultCompactor {
    private final Path microcompactOutputRoot;
    private final int retainRecentTokens;

    ToolResultCompactor(Path outputRoot, int retainTokens) {
        microcompactOutputRoot = outputRoot;
        retainRecentTokens = retainTokens;
    }

    private static final Logger log = LoggerFactory.getLogger(ToolResultCompactor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final DateTimeFormatter MICROCOMPACT_SESSION_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    static final String MICROCOMPACT_SESSION_ID =
            MICROCOMPACT_SESSION_ID_FMT.format(Instant.now());

    static final Object MICROCOMPACT_WRITE_LOCK = new Object();

    static final Pattern TOOL_RESULT_SHA256 = Pattern.compile(
            "\\[tool_result metadata:[^\\]]*\\bsha256=([0-9a-fA-F]{64})\\b");

    /**
     * 触发阶段按历史顺序回收重复、已被后续写入覆盖或超出近期 Token 保留预算的工具结果。
     * 不修改 user / assistant 消息；只有原文落盘成功且替换后 Token 减少时才写入引用。
     */
    MicrocompactStats compact(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            return MicrocompactStats.empty();
        }
        int beforeTokens = TokenBudget.estimateMessagesTokens(history);
        Map<String, Integer> roleBefore = roleTokens(history);
        Map<String, ToolInvocation> invocations = toolInvocations(history);
        Map<String, Integer> firstByContent = new LinkedHashMap<>();
        List<Integer> toolResultIndexes = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message message = history.get(i);
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                toolResultIndexes.add(i);
            }
        }

        Map<String, Integer> removedByTool = new LinkedHashMap<>();
        Set<Integer> recentResults = new HashSet<>();
        int recentTokens = 0;
        for (int i = toolResultIndexes.size() - 1; i >= 0 && recentTokens < retainRecentTokens; i--) {
            int index = toolResultIndexes.get(i);
            recentResults.add(index);
            recentTokens += TokenBudget.estimateMessagesTokens(List.of(history.get(index)));
        }
        int cleared = 0;
        if (microcompactOutputRoot != null) {
            for (int index : toolResultIndexes) {
                LlmClient.Message message = history.get(index);
                String content = message.content();
                ToolInvocation invocation = invocations.get(message.toolCallId());
                String toolName = invocation == null ? "unknown" : invocation.name();
                if (content == null || content.isBlank()
                        || MicrocompactBoundary.isBoundary(content)) {
                    continue;
                }
                String fingerprint = contentFingerprint(content);
                Integer firstIndex = firstByContent.putIfAbsent(fingerprint, index);
                boolean duplicate = firstIndex != null && firstIndex != index;
                boolean dead = invocation != null && isProvablyDead(invocation, index, invocations);
                boolean old = !recentResults.contains(index);
                if (!duplicate && !dead && !old) continue;
                String sourceToolCallId = duplicate
                        ? history.get(firstIndex).toolCallId() : message.toolCallId();
                int before = TokenBudget.estimateMessagesTokens(List.of(message));
                String compacted = collapseOldToolResultContent(
                        message.toolCallId(), toolName, content, sourceToolCallId,
                        duplicate ? "duplicate" : dead ? "superseded" : "outside_recent_budget");
                if (compacted.equals(content)) continue;
                LlmClient.Message replacement = new LlmClient.Message(
                        message.role(), compacted, message.reasoningContent(), message.toolCalls(),
                        message.toolCallId(), message.contentParts(), message.source());
                history.set(index, replacement);
                int after = TokenBudget.estimateMessagesTokens(List.of(replacement));
                removedByTool.merge(toolName, Math.max(0, before - after), Integer::sum);
                cleared++;
                log.info("microcompact cleared old tool_result[{}] tool={} toolCallId={}: {} -> {} chars",
                        index, toolName, message.toolCallId(), content.length(), compacted.length());
            }
        }

        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        MicrocompactStats stats = new MicrocompactStats(
                beforeTokens, afterTokens, cleared, removedByTool, roleBefore, roleTokens(history));
        if (Boolean.parseBoolean(System.getProperty(COMPACTION_METRICS_PROPERTY, "false"))) {
            System.err.printf(Locale.ROOT,
                    "[context-compaction] kind=micro beforeTokens=%d afterTokens=%d clearedToolResults=%d "
                            + "removedByTool=%s roleBefore=%s roleAfter=%s%n",
                    beforeTokens, afterTokens, cleared, removedByTool,
                    stats.roleTokensBefore(), stats.roleTokensAfter());
        }
        return stats;
    }

    static Map<String, ToolInvocation> toolInvocations(List<LlmClient.Message> history) {
        Map<String, ToolInvocation> invocations = new LinkedHashMap<>();
        int messageIndex = 0;
        for (LlmClient.Message message : history) {
            if (message.toolCalls() != null) {
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    if (call.id() != null && call.function() != null && call.function().name() != null) {
                        invocations.put(call.id(), new ToolInvocation(
                                call.id(), call.function().name(), call.function().arguments(), messageIndex));
                    }
                }
            }
            messageIndex++;
        }
        return invocations;
    }

    static Map<String, Integer> roleTokens(List<LlmClient.Message> history) {
        Map<String, Integer> tokens = new LinkedHashMap<>();
        for (LlmClient.Message message : history) {
            String role = message.role() == null ? "unknown" : message.role();
            tokens.merge(role, TokenBudget.estimateMessagesTokens(List.of(message)), Integer::sum);
        }
        return tokens;
    }

    static boolean isProvablyDead(ToolInvocation invocation, int resultIndex,
                                          Map<String, ToolInvocation> invocations) {
        if (!"read_file".equals(invocation.name())) return false;
        String path = jsonText(invocation.arguments(), "path");
        if (path == null || path.isBlank()) return false;
        return invocations.values().stream()
                .anyMatch(next -> next.messageIndex() > resultIndex
                        && ("write_file".equals(next.name()) || "edit_file".equals(next.name()))
                        && path.equals(jsonText(next.arguments(), "path")));
    }

    static String jsonText(String arguments, String field) {
        if (arguments == null || arguments.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(arguments).get(field);
            return node == null || !node.isTextual() ? null : node.asText();
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** ToolResultSizeManager 的 result_ref 每次调用都不同，重复判断应使用原文哈希。 */
    static String contentFingerprint(String content) {
        if (content == null) return sha256("");
        Matcher matcher = TOOL_RESULT_SHA256.matcher(content);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : sha256(content);
    }

    String collapseOldToolResultContent(String toolCallId, String toolName, String content,
                                                String sourceToolCallId, String reason) {
        Path candidate = microcompactOutputPath(toolCallId);
        String compacted = renderMicrocompactBoundary(toolCallId, toolName, content.length(), candidate,
                sourceToolCallId, reason)
                + "[旧工具结果已回收；请在当前工作区调用 read_file 读取 storedPath。]";
        // 短结果原地保留，避免恢复引用更长，也避免创建没有节省收益的落盘文件。
        if (MemoryEntry.estimateTokens(compacted) >= MemoryEntry.estimateTokens(content)) {
            return content;
        }
        Path stored = persistMicrocompactToolOutput(toolCallId, content);
        if (stored == null) return content;
        return renderMicrocompactBoundary(toolCallId, toolName, content.length(), stored,
                sourceToolCallId, reason)
                + "[旧工具结果已回收；请在当前工作区调用 read_file 读取 storedPath。]";
    }

    Path microcompactOutputPath(String toolCallId) {
        return microcompactOutputRoot.resolve(MICROCOMPACT_OUTPUTS_DIR)
                .resolve(MICROCOMPACT_SESSION_ID)
                .resolve(sanitizeFileName(toolCallId) + ".txt")
                .toAbsolutePath().normalize();
    }

    Path persistMicrocompactToolOutput(String toolCallId, String content) {
        if (microcompactOutputRoot == null) {
            return null;
        }
        try {
            Path outputDir = microcompactOutputRoot
                    .resolve(MICROCOMPACT_OUTPUTS_DIR)
                    .resolve(MICROCOMPACT_SESSION_ID);
            Files.createDirectories(outputDir);
            Path root = microcompactOutputRoot.toAbsolutePath().normalize();
            Path realRoot = root.toRealPath();
            Path realOutputDir = outputDir.toRealPath();
            if (!realOutputDir.startsWith(realRoot)) return null;
            synchronized (MICROCOMPACT_WRITE_LOCK) {
                String safeName = sanitizeFileName(toolCallId);
                Path outputFile = outputDir.resolve(safeName + ".txt")
                        .toAbsolutePath().normalize();
                if (!outputFile.startsWith(root)) return null;
                if (Files.isRegularFile(outputFile)
                        && content.equals(Files.readString(outputFile, StandardCharsets.UTF_8))) {
                    ensureMicrocompactHash(outputFile, content);
                    return outputFile;
                }
                if (Files.exists(outputFile)) {
                    outputFile = outputDir.resolve(safeName + "-"
                            + UUID.randomUUID().toString().replace("-", "") + ".txt")
                            .toAbsolutePath().normalize();
                }
                atomicWrite(outputFile, content.getBytes(StandardCharsets.UTF_8));
                ensureMicrocompactHash(outputFile, content);
                return outputFile;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("failed to persist microcompact tool output for {}; fallback to inline content",
                    toolCallId, e);
            return null;
        }
    }

    static void ensureMicrocompactHash(Path outputFile, String content) throws IOException {
        Path sidecar = outputFile.resolveSibling(outputFile.getFileName() + ".sha256");
        atomicWrite(sidecar, sha256(content).getBytes(StandardCharsets.US_ASCII));
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent == null) throw new IOException("microcompact output parent is missing");
        Path temporary = Files.createTempFile(parent, ".microcompact-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    String renderMicrocompactBoundary(String toolCallId,
                                              String toolName,
                                              int originalChars,
                                              Path outputFile,
                                              String sourceToolCallId,
                                              String reason) {
        String storedPath = microcompactOutputRoot.relativize(outputFile)
                .toString().replace('\\', '/');
        return MicrocompactBoundary.OPEN + "\n"
                + MicrocompactBoundary.TYPE_TOOL_RESULT + "\n"
                + "toolCallId=" + toolCallId + "\n"
                + "toolName=" + toolName + "\n"
                + "originalChars=" + originalChars + "\n"
                + "reason=" + reason + "\n"
                + "sourceToolCallId=" + sourceToolCallId + "\n"
                + "storedPath=" + storedPath + "\n"
                + MicrocompactBoundary.CLOSE + "\n";
    }

    record ToolInvocation(String id, String name, String arguments, int messageIndex) {
    }

    static String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "tool-result";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank()) return "tool-result";
        return sanitized.length() <= 96 ? sanitized : sanitized.substring(0, 96);
    }
}
