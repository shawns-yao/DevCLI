package com.devcli.prompt;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class PromptAssembler {
    /**
     * Turn Context 段头。说明「同一会话里可能存在多份快照，只有最后一份有效」——
     * 快照以 append-only 方式进入消息尾部（不改写旧消息，否则前缀缓存失配），
     * 因此更早的快照会留在历史里，必须显式标注取代关系，避免 LLM 把过期证据当现状。
     */
    private static final String TURN_CONTEXT_HEADER = """
            ## Turn Context

            以下是本轮开始时的状态快照。历史消息中若出现多份快照，只有最后一份有效，更早的已被取代。""";

    private final PromptRepository repository;

    public PromptAssembler(PromptRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public static PromptAssembler createDefault() {
        return new PromptAssembler(PromptRepository.createDefault());
    }

    /**
     * 组装 system prompt。<b>只包含会话级稳定内容</b>。
     *
     * <p>prompt cache（提示词缓存）契约：自动前缀缓存按请求 token 前缀命中，而 system prompt 是
     * 整个请求的前缀。它一旦在轮次之间变化，其后<b>全部对话历史</b>都会前缀失配——静态头通常
     * 只有几千 token，历史可以到几十万，缓存等于形同虚设。把易变段放在 system prompt 内部尾部
     * 并不能解决问题：失配点之后的一切都不可复用。
     *
     * <p>因此按轮次变化的内容（长期记忆检索结果 / skill 索引 / 工作记忆）不在这里，
     * 改由 {@link #assembleTurnContext(PromptContext)} 渲染并以 append-only 方式进入消息尾部。
     *
     * <p>留在这里的动态段仅限会话级稳定项：{@code ruleContext}（启动加载，会话内极少变化）与
     * {@code externalContext}（MCP resource 索引）。它们变化时前缀失配是正确且必要的。
     */
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
        append(prompt, dynamicSection("Rule Context", ctx.ruleContext()));
        append(prompt, dynamicSection("Project Context", ctx.externalContext()));
        append(prompt, repository.loadRequired("context/context-management.md"));
        append(prompt, repository.loadRequired("handoff.md"));

        String assembled = prompt.toString().trim();
        validateLanguageSection(assembled, "assembled prompt");
        return assembled;
    }

    /**
     * 组装按轮次变化的上下文快照，由调用方前置到当轮 user 消息里（append-only，不改写既有消息）。
     *
     * @return 快照文本；三段都为空时返回空串，调用方不应注入空块
     */
    public String assembleTurnContext(PromptContext context) {
        PromptContext ctx = context == null ? PromptContext.empty() : context;

        StringBuilder body = new StringBuilder();
        append(body, dynamicSection("Retrieved Memory", ctx.memoryContext()));
        append(body, dynamicSection("Skills", ctx.skillIndex()));
        append(body, dynamicSection("Session Memory", ctx.sessionMemory()));
        if (body.isEmpty()) {
            return "";
        }
        return TURN_CONTEXT_HEADER + "\n\n" + body;
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
