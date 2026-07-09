package com.devcli.tool.provider;

import com.devcli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GrepToolProvider implements ToolProvider {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".devcli", ".codegraph", ".idea", ".venv", "__pycache__",
            "node_modules", "target", "build", "dist", "out"
    );

    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "grep_code",
                "实时精确搜索当前工作区文件内容；适合类名、方法名、配置键、错误文本等精确定位，不依赖 RAG 索引。",
                context.createToolParameters(
                        new ToolParameter("pattern", "string", "要搜索的文本或正则表达式", true),
                        new ToolParameter("path", "string", "可选搜索路径，默认当前项目根", false),
                        new ToolParameter("regex", "boolean", "是否按正则表达式搜索，默认 true", false),
                        new ToolParameter("case_sensitive", "boolean", "是否区分大小写，默认 true", false),
                        new ToolParameter("limit", "integer", "最大返回匹配行数，默认 100，上限 500", false)
                ),
                args -> grep(args, context)
        ));
    }

    private String grep(Map<String, String> args, ToolContext context) {
        String pattern = args.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "grep_code 失败: pattern 不能为空";
        }
        Path start = context.resolveSafePath(args.getOrDefault("path", "."));
        if (!Files.exists(start)) {
            return "grep_code 未找到路径: " + args.getOrDefault("path", ".");
        }

        boolean regex = parseBoolean(args.get("regex"), true);
        boolean caseSensitive = parseBoolean(args.get("case_sensitive"), true);
        int limit = parseLimit(args.get("limit"));
        Pattern compiled = null;
        String plainNeedle = pattern;
        if (regex) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                compiled = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return "grep_code 失败: 正则表达式无效: " + e.getMessage();
            }
        } else if (!caseSensitive) {
            plainNeedle = pattern.toLowerCase(Locale.ROOT);
        }

        Path root = projectRoot(context.projectPath());
        List<Path> files = new ArrayList<>();
        collectFiles(start, files);
        files.sort(Comparator.comparing(path -> path.toString().replace('\\', '/')));

        List<String> matches = new ArrayList<>();
        for (Path file : files) {
            if (skipFile(root, file)) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean found = compiled == null
                        ? plainMatch(line, plainNeedle, caseSensitive)
                        : compiled.matcher(line).find();
                if (!found) {
                    continue;
                }
                matches.add(relative(root, file) + ":" + (i + 1) + ": " + line.strip());
                if (matches.size() >= limit) {
                    return String.join("\n", matches);
                }
            }
        }
        return matches.isEmpty() ? "(no matches)" : String.join("\n", matches);
    }

    private void collectFiles(Path start, List<Path> files) {
        if (Files.isRegularFile(start, LinkOption.NOFOLLOW_LINKS)) {
            files.add(start);
            return;
        }
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS) || skipDir(start)) {
            return;
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(start)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException ignored) {
            return;
        }
        children.sort(Comparator.comparing(path -> path.toString().replace('\\', '/')));
        for (Path child : children) {
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                collectFiles(child, files);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                files.add(child);
            }
        }
    }

    private boolean skipDir(Path path) {
        Path name = path.getFileName();
        return name != null && SKIP_DIRS.contains(name.toString());
    }

    private boolean skipFile(Path root, Path path) {
        if (hasSkippedParent(root, path)) {
            return true;
        }
        try {
            return Files.size(path) > MAX_FILE_BYTES;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean hasSkippedParent(Path root, Path path) {
        Path relative = path;
        try {
            relative = root.relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ignored) {
        }
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean plainMatch(String line, String pattern, boolean caseSensitive) {
        if (caseSensitive) {
            return line.contains(pattern);
        }
        return line.toLowerCase(Locale.ROOT).contains(pattern);
    }

    private boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw);
    }

    private int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), MAX_LIMIT));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private Path projectRoot(String projectPath) {
        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        try {
            return root.toRealPath();
        } catch (IOException ignored) {
            return root;
        }
    }

    private String relative(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            normalized = normalized.toRealPath();
        } catch (IOException ignored) {
        }
        try {
            return root.relativize(normalized).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return normalized.toString().replace('\\', '/');
        }
    }
}
