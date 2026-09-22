package com.devcli.tool.patch;

import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.tool.provider.ToolParameter;
import com.devcli.tool.provider.ToolProvider;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WriteGateResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code apply_patch} 工具：一次调用表达跨文件、跨位置的批量修改。
 *
 * <p>与 {@code write_file} / {@code edit_file} 的分工：单点小改用 edit_file，整文件重写用
 * write_file，分散在多处的成组修改用 apply_patch——后者不需要逐处往返，也不会有"前几处已落盘、
 * 后面某处锚点没匹配上"的部分应用。</p>
 *
 * <p>写路径完全复用既有链路：路径策略 → 委派写白名单 → 资源租约 → 版本闸门 → PatchSet
 * 原子应用与回滚 → 版本账本登记。这里不新增任何绕过这些环节的写入口。</p>
 */
public final class ApplyPatchTool {

    public static final String NAME = "apply_patch";

    private static final String DESCRIPTION = """
            用结构化补丁一次修改多处代码，可跨多个文件；适合分散在多处的成组改动。
            单点小改仍用 edit_file，整文件重写仍用 write_file。修改前先用 read_file 核对原文。

            格式：
            *** Begin Patch
            *** Add File: src/New.java
            +新增的一行
            *** Delete File: src/Old.java
            *** Update File: src/App.java
            *** Move to: src/Main.java
            @@ 定位文字，通常是类名或方法名
             以空格开头的是上下文行，用于定位
            -要删除的行
            +要新增的行
            *** End of File
            *** End Patch

            规则：
            - 路径为项目相对路径，每个路径在同一补丁里只能出现一次
            - 修改行必须以 '+'、'-' 或 ' ' 开头；空行写成单独的 '+' 或 ' '
            - 不写行号，靠上下文行定位；匹配会忽略首尾空白差异
            - 同一文件的多个修改点各用一个 '@@' 分隔，必须按文件中出现的先后顺序排列
            - 定位失败或基线过期时整批不应用，不会留下半成品
            """;

    private ApplyPatchTool() {
    }

    public static void register(ToolProvider.ToolContext context) {
        context.registerTool(ToolRegistry.Tool.contextualStructured(
                NAME,
                DESCRIPTION,
                context.createToolParameters(
                        new ToolParameter("patch", "string",
                                "完整的 *** Begin Patch ... *** End Patch 补丁文本", true)
                ),
                (args, executionContext) -> apply(context, args, executionContext),
                -1
        ));
    }

    private static ToolOutput apply(ToolProvider.ToolContext context, Map<String, String> args,
                                    ToolExecutionContext executionContext) {
        String patch = args == null ? null : args.get("patch");
        if (patch == null || patch.isBlank()) {
            return invalid("patch 不能为空");
        }
        String projectPath = context.projectPath();
        if (projectPath == null || projectPath.isBlank()) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "项目根目录未设置，无法应用补丁", false);
        }

        List<PatchHunk> hunks;
        try {
            hunks = ApplyPatchParser.parse(patch);
        } catch (PatchException e) {
            return invalid(e.getMessage());
        }
        if (hunks.isEmpty()) {
            return invalid("补丁未包含任何文件变更（'" + ApplyPatchParser.BEGIN_MARKER + "' 与 '"
                    + ApplyPatchParser.END_MARKER + "' 之间为空）");
        }

        // 与 contextResourceKey 使用同一套根路径口径，保证相对路径与 PatchSet 一致。
        Path projectRoot = Path.of(projectPath).toAbsolutePath().normalize();
        ApplyPatchCompiler.Compiled compiled;
        try {
            executionContext.throwIfCancelled();
            compiled = ApplyPatchCompiler.compile(hunks, projectRoot,
                    context::resolveSafeWritePath, context.maxWriteFileBytes());
        } catch (PatchException e) {
            return invalid(e.getMessage());
        }

        if (compiled.isEmpty()) {
            return ToolOutput.success("补丁未产生实际变更：目标文件内容已与补丁结果一致");
        }
        for (PatchSet.FileChange change : compiled.changes()) {
            if (!context.isWritePathAllowed(change.relativePath())) {
                return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                        "委派 Worker 写路径不在允许范围内: " + change.relativePath(), false);
            }
        }

        String step = context.currentResourceLeaseStep();
        Map<String, String> beforeByPath = new LinkedHashMap<>();
        for (PatchSet.FileChange change : compiled.changes()) {
            Path safe = projectRoot.resolve(change.relativePath());
            context.acquireWriteLeaseChecked(step, safe, change.relativePath());
            String before = readExisting(safe);
            WriteGateResult gate = context.validateWrite(step, safe, before);
            if (!gate.isAllowed()) {
                return ToolOutput.rejected(ToolErrorCode.STALE_CONTEXT, gate.reason(), true);
            }
            beforeByPath.put(change.relativePath(), before);
        }

        executionContext.throwIfCancelled();
        PatchSet.ApplyResult result = new PatchSet(compiled.changes()).apply(projectRoot);
        if (!result.applied()) {
            if (!result.conflicts().isEmpty()) {
                return ToolOutput.rejected(ToolErrorCode.STALE_CONTEXT,
                        "补丁基线已过期，以下文件在补丁生成后被改动，整批未应用: "
                                + String.join(", ", result.conflicts())
                                + "。请重新 read_file 后重发补丁", true);
            }
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    result.failureDescription(), false);
        }

        List<String> summary = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        for (PatchSet.FileChange change : compiled.changes()) {
            Path safe = projectRoot.resolve(change.relativePath());
            String before = beforeByPath.get(change.relativePath());
            String after = change.type() == PatchSet.ChangeType.DELETE
                    ? null : new String(change.content(), StandardCharsets.UTF_8);
            context.recordFileWrite(change.relativePath(), safe, before, after, step);
            summary.add(switch (change.type()) {
                case ADD -> "A " + change.relativePath();
                case MODIFY -> "M " + change.relativePath();
                case DELETE -> "D " + change.relativePath();
            });
            modified.add(change.relativePath());
        }
        return ToolOutput.success("补丁已应用，" + summary.size() + " 个文件:\n"
                + String.join("\n", summary)).withModifiedResources(modified);
    }

    /** 写入前读取当前内容，供版本闸门与 diff 展示使用；不存在或不可读时按"无基线"处理。 */
    private static String readExisting(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ToolOutput invalid(String message) {
        return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                "工具参数无效: " + message, true);
    }
}
