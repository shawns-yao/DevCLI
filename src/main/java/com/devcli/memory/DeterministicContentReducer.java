package com.devcli.memory;

import com.devcli.tool.ToolResultArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.util.regex.Matcher;

/** Classifies oversized context before semantic summarization. */
public final class DeterministicContentReducer {
    public enum Kind { TOOL_OUTPUT, CODE, IMAGE, TEXT }
    public record Reduced(Kind kind, String preview, int originalChars, String reference,
                          Map<String, String> metadata) {
        public Reduced(Kind kind, String preview, int originalChars, String reference) {
            this(kind, preview, originalChars, reference, Map.of());
        }
    }

    private static final Pattern CODE = Pattern.compile("(?s)(?:```|\\b(class|interface|enum|public\\s+static)\\b)");
    private static final Pattern IMAGE = Pattern.compile("(?i)(?:\\.(png|jpg|jpeg|gif|webp)|<image|data:image/)");
    private static final Pattern TOOL = Pattern.compile("(?i)(?:toolCallId=|tool_result|exit code|stdout|stderr)");

    private DeterministicContentReducer() {}

    public static Reduced reduce(String content, int previewChars, String reference) {
        String value = content == null ? "" : content;
        int limit = Math.max(200, previewChars);
        Kind kind = classify(value);
        String preview = value.length() <= limit ? value : diagnosticPreview(value, limit, kind);
        return new Reduced(kind, preview, value.length(), reference == null ? "" : reference,
                metadata(value, kind, reference));
    }

    public static Reduced reduceAndStore(String content, int previewChars, String toolCallId)
            throws IOException {
        Reduced reduced = reduce(content, previewChars, toolCallId);
        if (reduced.kind() != Kind.TOOL_OUTPUT || reduced.originalChars() <= previewChars) return reduced;
        ToolResultArtifactStore.StoredArtifact artifact =
                ToolResultArtifactStore.store(toolCallId, content);
        Map<String, String> metadata = new LinkedHashMap<>(reduced.metadata());
        metadata.put("artifact_sha256", artifact.sha256());
        return new Reduced(reduced.kind(), reduced.preview(), reduced.originalChars(),
                artifact.ref() + "|sha256=" + artifact.sha256(), metadata);
    }

    public static Kind classify(String content) {
        String value = content == null ? "" : content;
        if (IMAGE.matcher(value).find()) return Kind.IMAGE;
        if (TOOL.matcher(value).find()) return Kind.TOOL_OUTPUT;
        if (CODE.matcher(value).find()) return Kind.CODE;
        return Kind.TEXT;
    }

    private static Map<String, String> metadata(String value, Kind kind, String reference) {
        Map<String, String> result = new LinkedHashMap<>();
        if (reference != null && !reference.isBlank()) result.put("source", reference);
        if (kind == Kind.CODE) {
            var path = Pattern.compile("(?im)(?:file|path|source)\\s*[:=]\\s*([^\\s]+)").matcher(value);
            if (path.find()) result.put("file_path", path.group(1));
            int added = 0;
            int removed = 0;
            StringBuilder diff = new StringBuilder();
            for (String line : value.replace("\r", "").split("\n", -1)) {
                if (line.startsWith("+++") || line.startsWith("---")) continue;
                if (line.startsWith("+")) { added++; diff.append(line).append('\n'); }
                if (line.startsWith("-")) { removed++; diff.append(line).append('\n'); }
            }
            if (added > 0 || removed > 0) {
                result.put("diff_added_lines", Integer.toString(added));
                result.put("diff_removed_lines", Integer.toString(removed));
                result.put("diff_sha256", sha256(diff.toString()));
            }
            var symbol = Pattern.compile("\\b(?:class|interface|enum|record|void|int|String|boolean|long|private|public|protected)\\s+([A-Za-z_$][\\w$]*)").matcher(value);
            LinkedHashSet<String> symbols = new LinkedHashSet<>();
            while (symbol.find() && symbols.size() < 64) symbols.add(symbol.group(1));
            if (!symbols.isEmpty()) result.put("symbols", String.join(",", symbols));
            var location = Pattern.compile("(?m)\\b([^\\s:]+\\.java):(\\d+)(?::(\\d+))?").matcher(value);
            if (location.find()) result.put("compiler_location", location.group());
            addVersionMetadata(result, value);
        } else if (kind == Kind.IMAGE) {
            var path = Pattern.compile("(?i)(?:path|src|url)\\s*[:=]\\s*([^\\s]+)").matcher(value);
            if (path.find()) result.put("image_source", path.group(1));
            var mime = Pattern.compile("(?i)data:(image/[a-z0-9.+-]+)").matcher(value);
            if (mime.find()) result.put("mime_type", mime.group(1));
            String imageHash = imageBytesHash(value, result.get("image_source"));
            if (!imageHash.isBlank()) result.put("image_sha256", imageHash);
        }
        return Map.copyOf(result);
    }

    private static void addVersionMetadata(Map<String, String> result, String value) {
        for (String key : List.of("rag_epoch", "index_epoch", "symbol_version", "classpath_epoch")) {
            Matcher matcher = Pattern.compile("(?i)\\b" + key + "\\s*[:=]\\s*([^\\s,;]+)").matcher(value);
            if (matcher.find()) result.put(key, matcher.group(1));
        }
    }

    private static String imageBytesHash(String value, String source) {
        try {
            if (source != null && !source.isBlank()) {
                Path path = Path.of(source);
                if (Files.isRegularFile(path)) return sha256(Files.readAllBytes(path));
            }
            int comma = value.indexOf("base64,");
            if (comma >= 0) {
                byte[] bytes = Base64.getDecoder().decode(value.substring(comma + 7).trim());
                return sha256(bytes);
            }
        } catch (Exception ignored) {
            // Do not fall back to hashing the description text.
        }
        return "";
    }

    private static String sha256(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String diagnosticPreview(String value, int limit, Kind kind) {
        if (kind == Kind.IMAGE) return value.substring(0, Math.min(limit, value.length()));
        String[] lines = value.replace("\r", "").split("\n", -1);
        List<String> selected = new ArrayList<>();
        int headLimit = Math.max(1, limit / 2);
        int chars = 0;
        for (String line : lines) {
            if (chars + line.length() + 1 > headLimit) break;
            selected.add(line);
            chars += line.length() + 1;
        }
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (!(lower.contains("error") || lower.contains("exception")
                    || lower.contains("failed") || lower.contains("exit code"))) continue;
            if (chars + line.length() + 1 > limit * 3 / 4) break;
            if (!selected.contains(line)) {
                selected.add(line);
                chars += line.length() + 1;
            }
        }
        StringBuilder result = new StringBuilder(String.join("\n", selected));
        if (result.length() < value.length()) result.append("\n…\n");
        int remaining = Math.max(0, limit - result.length());
        if (remaining > 0) {
            String tail = value.substring(Math.max(0, value.length() - remaining));
            result.append(tail);
        }
        return result.length() > limit ? result.substring(0, limit) : result.toString();
    }
}
