package com.devcli.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具结果面向界面的展示契约，不进入模型可见文本。 */
public record ToolPresentation(
        Kind kind,
        String title,
        String primaryArgument,
        Map<String, String> metadata) {

    public enum Kind {
        GENERIC,
        TERMINAL,
        DIFF,
        LOCATIONS
    }

    public ToolPresentation {
        kind = kind == null ? Kind.GENERIC : kind;
        title = title == null ? "" : title;
        primaryArgument = primaryArgument == null ? "" : primaryArgument;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public ToolPresentation(Kind kind, String title, String primaryArgument) {
        this(kind, title, primaryArgument, Map.of());
    }

    public static ToolPresentation generic(String toolName) {
        return new ToolPresentation(Kind.GENERIC, toolName == null ? "工具调用" : toolName, "");
    }

    public static ToolPresentation defaultFor(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "execute_command" -> new ToolPresentation(Kind.TERMINAL, "执行命令", "command");
            case "write_file" -> new ToolPresentation(Kind.DIFF, "写入文件", "path");
            case "edit_file" -> new ToolPresentation(Kind.DIFF, "编辑文件", "path");
            case "read_file" -> new ToolPresentation(Kind.LOCATIONS, "读取文件", "path");
            case "list_dir" -> new ToolPresentation(Kind.LOCATIONS, "列出目录", "path");
            case "grep_code" -> new ToolPresentation(Kind.LOCATIONS, "精确搜索代码", "pattern");
            case "search_code" -> new ToolPresentation(Kind.LOCATIONS, "搜索代码", "query");
            case "web_search" -> new ToolPresentation(Kind.LOCATIONS, "联网搜索", "query");
            case "web_fetch" -> new ToolPresentation(Kind.LOCATIONS, "抓取网页", "url");
            case "create_project" -> new ToolPresentation(Kind.DIFF, "创建项目", "name");
            case "save_memory" -> new ToolPresentation(Kind.GENERIC, "保存长期记忆", "fact");
            case "confirm_memory" -> new ToolPresentation(Kind.GENERIC, "确认敏感记忆", "confirmation_id");
            case "list_memory" -> new ToolPresentation(Kind.GENERIC, "查看长期记忆", "limit");
            default -> generic(toolName);
        };
    }
}
