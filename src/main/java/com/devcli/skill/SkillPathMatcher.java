package com.devcli.skill;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从当前任务文本中提取可用于 Skill paths 条件激活的项目内相对路径。
 */
public final class SkillPathMatcher {

    private static final Pattern XML_PATH = Pattern.compile("\\bpath=\"([^\"]+)\"");
    private static final Pattern AT_PATH = Pattern.compile("(^|\\s)@<?([^\\s<>]+)>?");
    private static final Pattern INLINE_PATH = Pattern.compile(
            "(?<![\\w.-])([\\w.@() -]+(?:[/\\\\][\\w.@() -]+)+)(?=$|[\\s,;，。)\\]])");

    private SkillPathMatcher() {
    }

    public static List<String> extractPaths(String text, String projectRoot) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        collect(XML_PATH.matcher(text), 1, projectRoot, paths);
        collect(AT_PATH.matcher(text), 2, projectRoot, paths);
        collect(INLINE_PATH.matcher(text), 1, projectRoot, paths);
        return List.copyOf(paths);
    }

    private static void collect(Matcher matcher, int group, String projectRoot, Set<String> paths) {
        while (matcher.find()) {
            addPath(matcher.group(group), projectRoot, paths);
        }
    }

    private static void addPath(String raw, String projectRoot, Set<String> paths) {
        String normalized = normalize(raw, projectRoot);
        if (!normalized.isBlank()) {
            paths.add(normalized);
        }
    }

    static String normalize(String raw, String projectRoot) {
        if (raw == null) {
            return "";
        }
        String value = raw
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .trim();
        value = stripWrapping(value);
        if (value.isBlank()
                || value.startsWith("image:")
                || value.equals("clipboard")
                || value.startsWith("http://")
                || value.startsWith("https://")) {
            return "";
        }

        String slashNormalized = value.replace('\\', '/');
        String project = projectRoot == null ? "" : projectRoot.trim().replace('\\', '/');
        if (!project.isBlank() && slashNormalized.startsWith(project + "/")) {
            slashNormalized = slashNormalized.substring(project.length() + 1);
        } else {
            try {
                Path path = Path.of(value);
                if (path.isAbsolute() && !project.isBlank()) {
                    Path root = Path.of(projectRoot).toAbsolutePath().normalize();
                    Path absolute = path.toAbsolutePath().normalize();
                    if (absolute.startsWith(root)) {
                        slashNormalized = root.relativize(absolute).toString().replace('\\', '/');
                    }
                }
            } catch (Exception ignored) {
                // 不是当前平台可解析的路径时，继续按文本路径处理。
            }
        }
        return slashNormalized.replaceAll("^/+", "").trim();
    }

    private static String stripWrapping(String value) {
        String result = value;
        while (!result.isBlank() && isTrailingPunctuation(result.charAt(result.length() - 1))) {
            result = result.substring(0, result.length() - 1).trim();
        }
        if ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'"))
                || (result.startsWith("<") && result.endsWith(">"))) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean isTrailingPunctuation(char c) {
        return c == ',' || c == ';' || c == '，' || c == '。' || c == ')' || c == ']';
    }
}
