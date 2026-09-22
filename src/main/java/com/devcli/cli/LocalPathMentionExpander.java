package com.devcli.cli;

import com.devcli.context.ContextInputSnapshotStore;
import com.devcli.memory.MemoryEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocalPathMentionExpander {
    private static final int MAX_FILE_BYTES = 120_000;
    private static final int MAX_DIR_ENTRIES = 80;
    private static final int MAX_REFERENCE_PREVIEW_CHARS = 1_600;
    private static final int REFERENCE_METADATA_TOKENS = 180;
    private static final Pattern LOCAL_PATH_MENTION = Pattern.compile("(^|\\s)@(<[^>]+>|[^\\s<>:]+)");

    private final Path projectRoot;
    private final Path homeDir;
    private final ContextInputSnapshotStore snapshotStore;

    LocalPathMentionExpander(Path projectRoot) {
        this.projectRoot = realPathOrNormalize(projectRoot == null ? Path.of(".") : projectRoot);
        this.homeDir = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        this.snapshotStore = new ContextInputSnapshotStore(this.projectRoot);
    }

    String expand(String input) {
        return expand(input, Integer.MAX_VALUE);
    }

    String expand(String input, int remainingTokens) {
        if (input == null || input.isBlank() || input.indexOf('@') < 0) {
            return input;
        }
        Matcher matcher = LOCAL_PATH_MENTION.matcher(input);
        StringBuilder expanded = new StringBuilder();
        int remaining = Math.max(0, remainingTokens);
        while (matcher.find()) {
            String leading = matcher.group(1);
            String raw = matcher.group(2);
            String replacement = expandToken(raw, remaining);
            remaining = Math.max(0, remaining - MemoryEntry.estimateTokens(replacement));
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(leading + replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private String expandToken(String raw, int remainingTokens) {
        if (raw == null || raw.isBlank()) {
            return "@" + raw;
        }
        String value = stripAngles(raw);
        if (value.startsWith("image:") || value.equals("clipboard") || value.contains(":")) {
            return "@" + raw;
        }
        Path candidate = resolve(value);
        if (candidate == null || !Files.exists(candidate)) {
            return "@" + raw;
        }
        Path realCandidate = realPathOrNormalize(candidate);
        if (!realCandidate.startsWith(projectRoot)) {
            return "@" + raw;
        }
        try {
            if (Files.isDirectory(realCandidate)) {
                return renderDirectory(realCandidate);
            }
            if (Files.isRegularFile(realCandidate)) {
                return renderFile(realCandidate, remainingTokens);
            }
        } catch (IOException ignored) {
            return "@" + raw;
        }
        return "@" + raw;
    }

    private Path resolve(String value) {
        if (value.isBlank()) {
            return null;
        }
        if (value.startsWith("~/")) {
            return homeDir.resolve(value.substring(2)).normalize();
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return projectRoot.resolve(path).normalize();
    }

    private String renderFile(Path path, int remainingTokens) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int sampleLength = Math.min(bytes.length, MAX_FILE_BYTES);
        if (looksBinary(bytes, sampleLength)) {
            return "@<" + displayPath(path) + ">\n<file path=\"" + escapeXml(displayPath(path)) +
                    "\" binary=\"true\">binary content omitted</file>";
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String inline = "@<" + displayPath(path) + ">\n<file path=\"" + escapeXml(displayPath(path))
                + "\">\n" + content + "\n</file>";
        if (bytes.length <= MAX_FILE_BYTES
                && MemoryEntry.estimateTokens(inline) <= Math.max(0, remainingTokens)) {
            return inline;
        }
        return renderFileReference(path, bytes, content, remainingTokens);
    }

    private String renderFileReference(Path path, byte[] bytes, String content,
                                       int remainingTokens) throws IOException {
        ContextInputSnapshotStore.Snapshot snapshot = snapshotStore.store(
                path.getFileName() == null ? "attachment.txt" : path.getFileName().toString(), bytes);
        int previewBudget = Math.max(0, remainingTokens - REFERENCE_METADATA_TOKENS);
        int previewChars = Math.min(MAX_REFERENCE_PREVIEW_CHARS, previewBudget * 4);
        String preview = preview(content, previewChars);
        long lineCount = content.isEmpty() ? 0 : content.lines().count();
        StringBuilder reference = new StringBuilder("@<").append(displayPath(path)).append(">\n")
                .append("<file_reference original_path=\"").append(escapeXml(displayPath(path)))
                .append("\" stored_path=\"").append(escapeXml(snapshot.storedPath()))
                .append("\" sha256=\"").append(snapshot.sha256())
                .append("\" size_bytes=\"").append(snapshot.sizeBytes())
                .append("\" evidence_required=\"true\">\n")
                .append("<summary>文本文件，共 ").append(bytes.length)
                .append(" 字节、约 ").append(lineCount).append(" 行；因上下文预算不足，仅注入引用")
                .append("</summary>\n");
        if (!preview.isBlank()) {
            reference.append("<preview>\n").append(preview).append("\n</preview>\n");
        }
        return reference.append("</file_reference>").toString();
    }

    private static String preview(String content, int maxChars) {
        if (content == null || content.isBlank() || maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        int head = maxChars / 2;
        int tail = maxChars - head;
        return content.substring(0, head)
                + "\n[中间内容已省略，可读取 stored_path 获取完整证据]\n"
                + content.substring(content.length() - tail);
    }

    private String renderDirectory(Path path) throws IOException {
        List<Path> children;
        try (var stream = Files.list(path)) {
            children = stream
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(MAX_DIR_ENTRIES + 1L)
                    .toList();
        }
        StringBuilder out = new StringBuilder("@<").append(displayPath(path)).append(">\n")
                .append("<directory path=\"").append(escapeXml(displayPath(path))).append("\">\n");
        int count = Math.min(children.size(), MAX_DIR_ENTRIES);
        for (int i = 0; i < count; i++) {
            Path child = children.get(i);
            out.append("- ").append(child.getFileName());
            if (Files.isDirectory(child)) {
                out.append('/');
            }
            out.append('\n');
        }
        if (children.size() > MAX_DIR_ENTRIES) {
            out.append("[directory truncated by DevCLI at ").append(MAX_DIR_ENTRIES).append(" entries]\n");
        }
        return out.append("</directory>").toString();
    }

    private String displayPath(Path path) {
        Path relative = projectRoot.relativize(path);
        String value = relative.toString();
        return value.isBlank() ? "." : value;
    }

    private static Path realPathOrNormalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String stripAngles(String raw) {
        if (raw.startsWith("<") && raw.endsWith(">") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static boolean looksBinary(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String escapeXml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
