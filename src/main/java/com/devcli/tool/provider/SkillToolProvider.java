package com.devcli.tool.provider;

import com.devcli.skill.Skill;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillDocumentPager;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

public final class SkillToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "load_skill",
                "Load a budgeted page of SKILL.md guidance or one file below its references directory. Use page=2,3... to continue. Skill guidance enters the next user message; reference content is returned directly. Reloading after context compression is allowed.",
                context.createToolParameters(
                        new ToolParameter("name", "string", "exact kebab-case skill name", true),
                        new ToolParameter("page", "integer", "1-based page number, defaults to 1", false),
                        new ToolParameter("reference", "string", "optional relative path below references/", false)),
                args -> loadSkill(context, args.get("name"), args.get("page"), args.get("reference"))
        ));
    }

    private ToolOutput loadSkill(ToolContext context, String name, String rawPage, String reference) {
        if (name == null || name.isBlank()) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "load_skill 失败: name 不能为空", false);
        }
        SkillRegistry skillRegistry = context.skillRegistry();
        if (skillRegistry == null) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "load_skill 失败: Skill 系统未初始化", false);
        }
        Skill skill = skillRegistry.findSkill(name);
        if (skill == null) {
            Skill any = skillRegistry.findAnySkill(name);
            if (any == null) {
                return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                        "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill", false);
            }
            return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                    "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用");
        }
        int pageNumber;
        try {
            pageNumber = rawPage == null || rawPage.isBlank() ? 1 : Integer.parseInt(rawPage);
            if (pageNumber < 1) throw new NumberFormatException("page < 1");
        } catch (NumberFormatException e) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "load_skill 失败: page 必须是从 1 开始的整数", false);
        }
        if (reference != null && !reference.isBlank()) {
            return loadReference(context, skill, reference, pageNumber);
        }
        String body = skill.body();
        SkillDocumentPager.Page page;
        try {
            page = SkillDocumentPager.page(body, pageNumber, context.contextProfile().skillBodyTokens());
        } catch (IllegalArgumentException e) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "load_skill 失败: " + e.getMessage(), false);
        }
        SkillContextBuffer targetBuffer = context.activeSkillContextBuffer();
        if (targetBuffer != null) {
            targetBuffer.push(name, page.content(), skill.allowedTools(), skill.context(), !skill.paths().isEmpty());
        }
        skillRegistry.recordUsage(name);
        String allowedTools = skill.allowedTools().isEmpty()
                ? ""
                : "允许工具: " + String.join(", ", skill.allowedTools()) + "。";
        String contextText = skill.context() == Skill.Context.FORK
                ? "context: fork。建议在子任务/fork 上下文中使用，避免污染主上下文。"
                : "context: inline。";
        String continuation = page.hasNext()
                ? "继续读取请调用 load_skill(name=\"" + name + "\", page=" + (page.number() + 1) + ")。"
                : "已到最后一页。";
        return ToolOutput.success("已加载 skill '" + name + "' 第 " + page.number() + "/" + page.total()
                + " 页，" + allowedTools
                + contextText
                + "将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。"
                + continuation);
    }

    private ToolOutput loadReference(ToolContext context, Skill skill, String relativePath, int pageNumber) {
        if (skill.referencesDir() == null) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "Skill '" + skill.name() + "' 没有 references 目录", false);
        }
        try {
            java.nio.file.Path root = skill.referencesDir().toRealPath();
            java.nio.file.Path candidate = root.resolve(relativePath).normalize();
            if (!candidate.startsWith(root) || !java.nio.file.Files.isRegularFile(candidate)) {
                return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                        "reference 路径越界或文件不存在");
            }
            java.nio.file.Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, "reference 符号链接越界");
            }
            String content = java.nio.file.Files.readString(real);
            SkillDocumentPager.Page page = SkillDocumentPager.page(
                    content, pageNumber, context.contextProfile().skillBodyTokens());
            String next = page.hasNext()
                    ? "\n\n继续读取: load_skill(name=\"" + skill.name() + "\", reference=\""
                    + relativePath + "\", page=" + (page.number() + 1) + ")"
                    : "";
            return ToolOutput.success("Skill reference " + relativePath + " 第 " + page.number()
                    + "/" + page.total() + " 页\n\n" + page.content() + next);
        } catch (java.io.IOException e) {
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "读取 Skill reference 失败: " + e.getMessage(), false);
        } catch (IllegalArgumentException e) {
            return ToolOutput.error(ToolErrorCode.INVALID_ARGUMENTS,
                    "load_skill 失败: " + e.getMessage(), false);
        }
    }
}
