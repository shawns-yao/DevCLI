package com.devcli.agent;

import com.devcli.llm.LlmClient;
import com.devcli.runtime.CancellationContext;
import com.devcli.runtime.RunContext;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.ToolStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把预算裁剪产生的文件引用转换为执行期证据义务。
 */
final class ContextReferenceGuard {
    private static final int MAX_READ_FAILURES = 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FILE_REFERENCE = Pattern.compile(
            "<file_reference\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FILE_REFERENCE_BLOCK = Pattern.compile(
            "<file_reference\\b[^>]*>.*?</file_reference>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern METADATA_ONLY_TARGET = Pattern.compile(
            "文件名|文件名称|附件名|名字|路径|大小|尺寸|字节数|文件类型|格式|扩展名|后缀|"
                    + "哈希|sha-?256|当前问题|附件数量|几个附件|"
                    + "file\\s*name|path|size|bytes?|file\\s*type|extension|hash|current\\s+(?:question|issue)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_SCOPE = Pattern.compile(
            "文件内容|附件内容|文档内容|正文|里面|其中|文中|代码|日志|错误|报错|异常|堆栈|"
                    + "错误信息|行号|原文|细节|具体信息|文件.{0,12}问题|问题.{0,12}文件|"
                    + "contents?|inside|source\\s+code|logs?|errors?|exceptions?|stack\\s*trace|"
                    + "error\\s*message|line\\s*number|details?|exact\\s+message",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSTANTIVE_CONTENT_SCOPE = Pattern.compile(
            "正文|里面|其中|文中|代码|日志|错误|报错|异常|堆栈|错误信息|行号|原文|细节|"
                    + "具体信息|文件.{0,12}问题|问题.{0,12}文件|"
                    + "inside|source\\s+code|logs?|errors?|exceptions?|stack\\s*trace|"
                    + "error\\s*message|line\\s*number|details?|exact\\s+message",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_ACTION = Pattern.compile(
            "分析|总结|概括|摘要|解读|解释|审查|评审|比较|提取|诊断|修复|"
                    + "analy[sz]e|summari[sz]e|review|inspect|explain|compare|extract|diagnose|fix",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_LOOKUP = Pattern.compile(
            "定位|查找|搜索|找出|确认|告诉|是什么|有哪些|在哪里|哪一行|"
                    + "locate|find|search|identify|show|what|where",
            Pattern.CASE_INSENSITIVE);

    private final LinkedHashSet<String> pendingStoredPaths;
    private final Map<String, String> expectedHashes;
    private final Map<String, Integer> readFailures = new LinkedHashMap<>();
    private boolean forceRead;

    private ContextReferenceGuard(Map<String, String> references) {
        this.pendingStoredPaths = new LinkedHashSet<>(references.keySet());
        this.expectedHashes = new LinkedHashMap<>(references);
    }

    static ContextReferenceGuard fromHistory(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) {
            return new ContextReferenceGuard(Map.of());
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            LlmClient.Message message = history.get(i);
            if (!"user".equals(message.role())
                    || message.source() == LlmClient.MessageSource.SYSTEM_INTERNAL) {
                continue;
            }
            Map<String, String> references = requiredReferences(message.content());
            if (references.isEmpty() || !requiresContentEvidence(message.content())) {
                return new ContextReferenceGuard(Map.of());
            }
            return new ContextReferenceGuard(references);
        }
        return new ContextReferenceGuard(Map.of());
    }

    LlmClient.ToolChoice toolChoice(LlmClient.ToolChoice requested) {
        if (forceRead && !pendingStoredPaths.isEmpty()) {
            return LlmClient.ToolChoice.required("read_file");
        }
        return requested == null ? LlmClient.ToolChoice.AUTO : requested;
    }

    void observe(List<ToolRegistry.ToolExecutionResult> results) {
        if (pendingStoredPaths.isEmpty() || results == null) {
            return;
        }
        for (ToolRegistry.ToolExecutionResult result : results) {
            if (result == null
                    || !"read_file".equals(result.name())) {
                continue;
            }
            String path = argumentPath(result.argumentsJson());
            if (path.isBlank()) {
                continue;
            }
            String pendingPath = pendingStoredPaths.stream()
                    .filter(pending -> samePath(pending, path))
                    .findFirst()
                    .orElse("");
            if (pendingPath.isBlank()) {
                continue;
            }
            if (result.status() == ToolStatus.SUCCESS && snapshotMatches(pendingPath)) {
                pendingStoredPaths.remove(pendingPath);
                readFailures.remove(pendingPath);
            } else {
                readFailures.merge(pendingPath, 1, Integer::sum);
            }
        }
        forceRead = !pendingStoredPaths.isEmpty();
    }

    String terminalFailure() {
        List<String> exhaustedPaths = pendingStoredPaths.stream()
                .filter(path -> readFailures.getOrDefault(path, 0) >= MAX_READ_FAILURES)
                .toList();
        if (exhaustedPaths.isEmpty()) {
            return "";
        }
        return "附件证据不可用：以下 stored_path 连续读取失败 " + MAX_READ_FAILURES
                + " 次，禁止在缺少文件证据时继续推理：\n- "
                + String.join("\n- ", exhaustedPaths);
    }

    String retryInstruction() {
        if (pendingStoredPaths.isEmpty()) {
            return "";
        }
        forceRead = true;
        return "检测到附件内容因上下文预算被替换为引用。"
                + "在给出文件内容相关结论前，必须先读取以下 stored_path；请调用 read_file，"
                + "并以成功工具结果作为证据：\n- "
                + String.join("\n- ", pendingStoredPaths);
    }

    boolean isSatisfied() {
        return pendingStoredPaths.isEmpty();
    }

    private static boolean requiresContentEvidence(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String request = FILE_REFERENCE_BLOCK.matcher(content).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        if (request.isBlank()) {
            return false;
        }
        boolean contentScoped = CONTENT_SCOPE.matcher(request).find();
        boolean contentAction = CONTENT_ACTION.matcher(request).find();
        boolean substantiveContent = SUBSTANTIVE_CONTENT_SCOPE.matcher(request).find();
        if (METADATA_ONLY_TARGET.matcher(request).find()
                && !substantiveContent
                && !(contentScoped && contentAction)) {
            return false;
        }
        return contentAction
                || (contentScoped && CONTENT_LOOKUP.matcher(request).find())
                || contentScoped;
    }

    private static Map<String, String> requiredReferences(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Map<String, String> referencesByPath = new LinkedHashMap<>();
        Matcher references = FILE_REFERENCE.matcher(content);
        while (references.find()) {
            String attributes = references.group(1);
            String storedPath = "";
            String sha256 = "";
            boolean required = false;
            Matcher matcher = ATTRIBUTE.matcher(attributes);
            while (matcher.find()) {
                String name = matcher.group(1).toLowerCase(Locale.ROOT);
                String value = unescapeXml(matcher.group(2));
                if ("stored_path".equals(name)) {
                    storedPath = value;
                } else if ("sha256".equals(name)) {
                    sha256 = value;
                } else if ("evidence_required".equals(name)) {
                    required = Boolean.parseBoolean(value);
                }
            }
            if (required && !storedPath.isBlank()) {
                referencesByPath.put(storedPath, sha256);
            }
        }
        return referencesByPath;
    }

    private boolean snapshotMatches(String storedPath) {
        String expected = expectedHashes.getOrDefault(storedPath, "");
        if (!SHA256.matcher(expected).matches()) {
            return true;
        }
        try {
            RunContext runContext = CancellationContext.currentRun();
            Path projectRoot = (runContext == null ? Path.of(".") : runContext.projectPath())
                    .toRealPath();
            Path snapshot = projectRoot.resolve(storedPath).normalize().toRealPath();
            if (!snapshot.startsWith(projectRoot) || !Files.isRegularFile(snapshot)) {
                return false;
            }
            return expected.equalsIgnoreCase(sha256(snapshot));
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String argumentPath(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(argumentsJson);
            JsonNode path = root.get("path");
            return path == null || !path.isTextual() ? "" : path.asText();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean samePath(String expected, String actual) {
        return normalizePath(expected).equalsIgnoreCase(normalizePath(actual));
    }

    private static String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String unescapeXml(String value) {
        return value == null ? "" : value
                .replace("&quot;", "\"")
                .replace("&gt;", ">")
                .replace("&lt;", "<")
                .replace("&amp;", "&");
    }
}
