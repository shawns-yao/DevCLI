package com.devcli.tool.provider;

import com.devcli.policy.PolicyException;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolExecutionContext;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;
import com.devcli.workspace.FileModeSnapshot;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.ProjectCommitCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Explicit file deletion, bound to the exact versions inspected before approval. */
public final class DeleteFilesTool {
    public static final String NAME = "delete_files";
    public static final String PLAN_ATTRIBUTE = "delete_files.plan";
    private static final int MAX_FILES = 500;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    private DeleteFilesTool() { }

    public record Plan(Path root, List<PatchSet.FileChange> changes) {
        public Plan {
            changes = List.copyOf(changes);
        }

        public String notice() {
            return "删除文件数量: " + changes.size()
                    + "\n递归范围: 不递归，仅删除以下明确文件，保留目录"
                    + "\n恢复方式: 不进入回收站；应用失败时尝试整批回滚，成功后恢复需已有快照或备份"
                    + "\n目标文件:\n"
                    + String.join("\n", changes.stream().map(PatchSet.FileChange::relativePath).toList());
        }
    }

    public static void register(ToolProvider.ToolContext context) {
        ObjectNode schema = (ObjectNode) context.createToolParameters(
                new ToolParameter("paths", "array", "明确的项目相对文件路径列表，不支持目录或通配符", true));
        ObjectNode paths = (ObjectNode) schema.path("properties").path("paths");
        paths.put("minItems", 1).put("maxItems", MAX_FILES).put("uniqueItems", true);
        paths.putObject("items").put("type", "string").put("minLength", 1);
        context.registerTool(ToolRegistry.Tool.contextualStructured(NAME,
                "删除明确列出的项目文件（最多 500 个、合计 20MB、单文件 5MB）。"
                        + "不递归、不展开通配符、不删除目录或符号链接。优先于 Shell 删除；"
                        + "敏感路径拒绝，审批期间文件变化则整批拒绝，失败尝试回滚，不进入回收站。",
                schema, (args, execution) -> ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                        "删除必须经过注册表的清单预检与审批流程"), -1));
    }

    public static Plan prepare(ToolProvider.ToolContext context, JsonNode arguments) throws Exception {
        JsonNode paths = arguments.path("paths");
        if (!paths.isArray() || paths.isEmpty() || paths.size() > MAX_FILES) {
            throw new IllegalArgumentException("paths 必须包含 1 到 " + MAX_FILES + " 个明确文件路径");
        }
        Path root = Path.of(context.projectPath()).toRealPath();
        List<PatchSet.FileChange> changes = new ArrayList<>();
        HashSet<Path> seen = new HashSet<>();
        long bytes = 0;
        for (JsonNode entry : paths) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                throw new IllegalArgumentException("删除路径必须是非空字符串");
            }
            String path = entry.asText();
            Path relative = Path.of(path);
            if (relative.isAbsolute() || path.contains("*") || path.contains("?")) {
                throw new PolicyException("删除仅接受明确的项目相对路径，不允许通配符: " + path);
            }
            Path lexical = root.resolve(relative).normalize();
            if (!lexical.startsWith(root) || lexical.equals(root)) {
                throw new PolicyException("禁止删除项目根目录或项目外路径: " + path);
            }
            for (Path cursor = lexical; !cursor.equals(root); cursor = cursor.getParent()) {
                if (Files.isSymbolicLink(cursor)) {
                    throw new PolicyException("删除路径不能包含符号链接: " + path);
                }
            }
            Path safe = context.resolveSafeWritePath(path);
            if (!context.isWritePathAllowed(path)) {
                throw new PolicyException("删除路径不在委派写入范围内: " + path);
            }
            if (!Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("目标不存在或不是普通文件，不支持目录递归删除: " + path);
            }
            if (!seen.add(safe)) {
                throw new IllegalArgumentException("删除清单包含重复路径: " + path);
            }
            long size = Files.size(safe);
            bytes += size;
            if (size > context.maxWriteFileBytes() || bytes > MAX_TOTAL_BYTES) {
                throw new PolicyException("删除超出单文件 5MB 或整批 20MB 上限");
            }
            changes.add(new PatchSet.FileChange(root.relativize(safe).toString().replace('\\', '/'),
                    PatchSet.ChangeType.DELETE, PatchSet.hash(safe), null, null,
                    FileModeSnapshot.capture(safe), null));
        }
        return new Plan(root, changes);
    }

    public static ToolOutput execute(ToolProvider.ToolContext context, Plan plan,
                                     ToolExecutionContext execution) {
        try {
            return ProjectCommitCoordinator.withProjectLock(plan.root(), () -> {
                String step = context.currentResourceLeaseStep();
                List<String> before = new ArrayList<>();
                for (PatchSet.FileChange change : plan.changes()) {
                    execution.throwIfCancelled();
                    Path safe = context.resolveSafeWritePath(change.relativePath());
                    if (!safe.equals(plan.root().resolve(change.relativePath()).normalize())) {
                        throw new PolicyException("审批后删除路径目标发生变化");
                    }
                    if (!context.isWritePathAllowed(change.relativePath())) {
                        throw new PolicyException("删除路径不在委派写入范围内: " + change.relativePath());
                    }
                    context.acquireWriteLeaseChecked(step, safe, change.relativePath());
                    if (!change.beforeHash().equals(PatchSet.hash(safe))) {
                        return ToolOutput.rejected(ToolErrorCode.STALE_CONTEXT,
                                "审批后文件发生变化，整批未删除，请重新提交删除清单", true);
                    }
                    String text;
                    try {
                        text = Files.readString(safe, StandardCharsets.UTF_8);
                    } catch (java.nio.charset.CharacterCodingException binary) {
                        text = null;
                    }
                    var gate = context.validateWrite(step, safe, text);
                    if (!gate.isAllowed()) {
                        return ToolOutput.rejected(ToolErrorCode.STALE_CONTEXT, gate.reason(), true);
                    }
                    before.add(text);
                }
                execution.throwIfCancelled();
                PatchSet.ApplyResult result = new PatchSet(plan.changes()).apply(plan.root());
                if (!result.applied()) {
                    return ToolOutput.rejected(result.conflicts().isEmpty()
                                    ? ToolErrorCode.EXECUTION_FAILED : ToolErrorCode.STALE_CONTEXT,
                            result.failureDescription(), false);
                }
                for (int i = 0; i < plan.changes().size(); i++) {
                    String path = plan.changes().get(i).relativePath();
                    context.recordFileWrite(path, plan.root().resolve(path), before.get(i), null, step);
                }
                return ToolOutput.success("已删除 " + plan.changes().size() + " 个文件，目录保留，不进入回收站")
                        .withModifiedResources(result.modifiedResources());
            });
        } catch (java.util.concurrent.CancellationException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolOutput.cancelled("删除已取消");
        } catch (PolicyException e) {
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, e.getMessage());
        } catch (Exception e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "删除失败: " + e.getMessage(), false);
        }
    }
}
