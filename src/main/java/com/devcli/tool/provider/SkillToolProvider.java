package com.devcli.tool.provider;

import com.devcli.skill.Skill;
import com.devcli.skill.SkillContextBuffer;
import com.devcli.skill.SkillRegistry;
import com.devcli.tool.ToolErrorCode;
import com.devcli.tool.ToolOutput;
import com.devcli.tool.ToolRegistry;

public final class SkillToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(ToolRegistry.Tool.structured(
                "load_skill",
                "Load full SKILL.md instructions for a skill the system has indexed (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description matches the current task. Pass the exact kebab-case skill name. The full body will appear at the start of your next user message under \"## 已加载 Skill：<name>\". Don't reload the same skill twice in one session.",
                context.createToolParameters(new ToolParameter("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                args -> loadSkill(context, args.get("name"))
        ));
    }

    private ToolOutput loadSkill(ToolContext context, String name) {
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
        String body = skill.body();
        int originalLen = body == null ? 0 : body.length();
        int max = 5 * 1024;
        String injected = body == null ? "" : body;
        if (injected.length() > max) {
            injected = injected.substring(0, max)
                    + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
        }
        SkillContextBuffer targetBuffer = context.activeSkillContextBuffer();
        if (targetBuffer != null) {
            targetBuffer.push(name, injected, skill.allowedTools(), skill.context());
        }
        skillRegistry.recordUsage(name);
        String allowedTools = skill.allowedTools().isEmpty()
                ? ""
                : "允许工具: " + String.join(", ", skill.allowedTools()) + "。";
        String contextText = skill.context() == Skill.Context.FORK
                ? "context: fork。建议在子任务/fork 上下文中使用，避免污染主上下文。"
                : "context: inline。";
        return ToolOutput.success("已加载 skill '" + name + "' 的完整指引（" + originalLen
                + " bytes），" + allowedTools
                + contextText
                + "将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。");
    }
}
