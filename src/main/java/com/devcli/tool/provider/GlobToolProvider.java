package com.devcli.tool.provider;

import com.devcli.tool.ToolContract;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Map;

/** 与 grep_code 共用候选文件和 Git 忽略规则，仅按相对文件名匹配。 */
public final class GlobToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolContract.Tool.structured("glob",
                "按项目相对 glob 模式查找文件。支持 *、?、**；**/ 可匹配零层目录。遵循 Git 忽略规则，显式 path 可收窄到被忽略目录。",
                context.createToolParameters(
                        new ToolParameter("pattern", "string", "项目相对模式，例如 **/*Test.java 或 src/**/*.java", true),
                        new ToolParameter("path", "string", "搜索范围，默认项目根", false),
                        new ToolParameter("limit", "integer", "最多返回文件数，默认 200，上限 1000", false)),
                args -> find(args, context)));
    }

    private ToolOutput find(Map<String, String> args, ToolContext context) {
        Path root = Path.of(context.projectPath()).toAbsolutePath().normalize();
        Path scope = context.resolveSafePath(args.getOrDefault("path", "."));
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + args.get("pattern").replace("**/", "{**/,}"));
        } catch (IllegalArgumentException invalid) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "glob 模式无效，请使用 *、?、** 和 / 分隔路径: " + invalid.getMessage(), true);
        }
        int limit = Integer.parseInt(args.getOrDefault("limit", "200"));
        var matches = context.executionEnvironment().findFiles(root, scope).stream()
                .map(root::relativize).filter(matcher::matches).limit(limit + 1L).toList();
        String text = matches.stream().limit(limit).map(path -> path.toString().replace('\\', '/'))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (matches.size() > limit) text += "\n(结果已截断；请收窄 pattern 或 path)";
        return ToolOutput.success(text.isEmpty() ? "(no matches)" : text);
    }
}
