package com.devcli.prompt;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class PromptAssembler {
    private final PromptRepository repository;

    public PromptAssembler(PromptRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    public String assemble(PromptMode mode, PromptContext context) {
        Objects.requireNonNull(mode, "mode");
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        String base = repository.loadRequired("base.md");
        validateLanguageSection(base, "base.md");

        StringBuilder prompt = new StringBuilder();
        append(prompt, base);
        append(prompt, repository.loadRequired("personalities/calm.md"));
        append(prompt, applyVariables(repository.loadRequired(mode.resourcePath()), ctx));
        append(prompt, repository.loadRequired("approvals/" + approvalMode(ctx) + ".md"));
        // Sticky 区紧跟 base/personality/mode/approval 之后注入：
        // 它是稳定层（启动加载，会话内极少变化），放前面让 KV cache 命中前缀更长。
        append(prompt, dynamicSection("Sticky Memory", ctx.stickyMemory()));
        append(prompt, dynamicSection("Project Context", ctx.memoryContext(), ctx.externalContext()));
        append(prompt, dynamicSection("Skills", ctx.skillIndex()));
        // Working Memory 是当前会话工作状态（最近工具证据 / 任务状态 / 临时事实），
        // 放在 Sticky / Project / Skills 之后、context-management 之前——
        // 易变层放后面，让前面的稳定段尽可能命中 KV cache。
        append(prompt, dynamicSection("Working Memory", ctx.workingMemory()));
        append(prompt, repository.loadRequired("context/context-management.md"));
        append(prompt, repository.loadRequired("handoff.md"));

        String assembled = prompt.toString().trim();
        validateLanguageSection(assembled, "assembled prompt");
        return assembled;
    }

    private String approvalMode(PromptContext context) {
        String mode = context.approvalMode();
        if (mode == null || mode.isBlank()) {
            return "suggest";
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "auto", "never" -> normalized;
            default -> "suggest";
        };
    }

    private static String applyVariables(String template, PromptContext context) {
        String result = template;
        for (Map.Entry<String, String> entry : context.variables().entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        result = result.replace("{{taskType}}", context.variable("taskType"));
        result = result.replace("{{taskDescription}}", context.variable("taskDescription"));
        return result;
    }

    private static String dynamicSection(String title, String... values) {
        StringBuilder body = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!body.isEmpty()) {
                    body.append("\n\n");
                }
                body.append(value.trim());
            }
        }
        if (body.isEmpty()) {
            return "";
        }
        return "## " + title + "\n\n" + body;
    }

    private static void append(StringBuilder sb, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(section.trim());
    }

    private static void validateLanguageSection(String prompt, String source) {
        if (prompt == null || !prompt.contains("## Language")) {
            throw new IllegalStateException("Prompt " + source + " must contain a '## Language' section");
        }
    }
}
