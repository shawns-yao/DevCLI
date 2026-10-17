package com.devcli.tool;

import com.devcli.tool.command.CommandExecutionService;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.devcli.browser.BrowserAuditMetadata;
import com.devcli.mcp.protocol.McpSchemaValidator;
import com.devcli.policy.AuditLog;
import com.devcli.policy.TaskGrant;
import com.devcli.policy.PolicyException;
import com.devcli.skill.SkillContextBuffer;

import java.util.*;
import java.util.concurrent.*;

import static com.devcli.tool.ToolContract.*;

/** 工具执行、参数校验、内容审查和审计实现；注册表保留公共与可覆写入口。 */
final class ToolExecutionCore {
    private final ToolRegistry registry;
    ToolExecutionCore(ToolRegistry registry) { this.registry = registry; }
    com.devcli.policy.SensitiveContentPolicy.Decision reviewSensitiveContent(
            String tool, com.devcli.policy.SensitiveContentPolicy.Inspection inspection,
            String purpose, String target, boolean redactionAllowed) {
        return com.devcli.policy.SensitiveContentPolicy.Decision.REJECT;
    }

    ToolOutput reviewMemorySave(ToolExecutionPipeline.Context context) {
        return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                "长期记忆写入需要单次确认；当前没有审批处理器，未保存");
    }

    void recordContentDecision(String tool, String types, String purpose, String target,
                                         String decision) {
        String metadata = "content_review; types=" + types + "; purpose=" + purpose
                + "; target=" + target + "; decision=" + decision;
        registry.auditLog.record("reject".equals(decision)
                ? AuditLog.AuditEntry.denyByHitl(tool, "{}", metadata, 0)
                : AuditLog.AuditEntry.allow(tool, metadata, 0));
    }

    String reviewFileContent(String content) {
        return registry.reviewFileContent(new com.devcli.policy.FileContentReview("", content, 0, content.length()));
    }

    String reviewFileContent(com.devcli.policy.FileContentReview review) {
        var inspection = review.inspect();
        if (!inspection.sensitive()) return review.content();
        var decision = registry.reviewSensitiveContent("read_file", inspection,
                "将文件内容作为工具结果提供给模型", "当前会话模型提供方", true);
        return switch (decision) {
            case ALLOW_ONCE -> review.content();
            case REDACT -> inspection.sanitized();
            case REJECT -> throw new PolicyException("敏感文件内容未获批准");
        };
    }

    ToolOutput executeWithAudit(ToolExecutionPipeline.Context context,
                                        ToolExecutionPipeline.Chain chain) {
        boolean audit = registry.requiresAudit(context.name());
        if (audit) context.putAttribute("audit.identity", toolAuditIdentity(context));
        long start = System.nanoTime();
        try {
            ToolOutput output = chain.proceed(context);
            if (audit) {
                BrowserAuditMetadata metadata =
                        context.attribute(ToolRegistry.PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class);
                long duration = registry.elapsedMillis(start);
                if (output == null || output.isSuccess()) {
                    registry.recordToolAudit(AuditLog.AuditEntry.allow(
                            context.name(), context.argumentsJson(), duration, metadata), context);
                } else {
                    String reason = output.status().name() + "/" + output.errorCode().name();
                    registry.recordToolAudit(output.status() == ToolStatus.REJECTED
                            ? AuditLog.AuditEntry.denyByPolicy(
                                    context.name(), context.argumentsJson(), reason, duration, metadata)
                            : AuditLog.AuditEntry.error(
                                    context.name(), context.argumentsJson(), reason, duration, metadata), context);
                }
            }
            return output;
        } catch (com.devcli.event.RunEventSink.PersistenceFailure failure) {
            throw failure;
        } catch (ResourceLeaseException e) {
            if (audit) {
                registry.recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                        context.name(), context.argumentsJson(), e.getMessage(), registry.elapsedMillis(start),
                        context.attribute(ToolRegistry.PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)), context);
            }
            return ToolOutput.rejected(ToolErrorCode.RESOURCE_CONFLICT,
                    "策略拒绝: " + e.getMessage(), true);
        } catch (PolicyException e) {
            if (audit) {
                registry.recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                        context.name(), context.argumentsJson(), e.getMessage(), registry.elapsedMillis(start),
                        context.attribute(ToolRegistry.PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)), context);
            }
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED,
                    "策略拒绝: " + e.getMessage());
        } catch (Exception e) {
            if (audit) {
                registry.recordToolAudit(AuditLog.AuditEntry.error(
                        context.name(), context.argumentsJson(), e.getMessage(), registry.elapsedMillis(start),
                        context.attribute(ToolRegistry.PIPELINE_BROWSER_AUDIT, BrowserAuditMetadata.class)), context);
            }
            return ToolOutput.error(ToolErrorCode.EXECUTION_FAILED,
                    "工具执行失败: " + e.getMessage(), true);
        }
    }

    AuditLog.ExecutionIdentity toolAuditIdentity(ToolExecutionPipeline.Context context) {
        ToolAccessContext.GrantScope scope = registry.access.currentGrantScope();
        TaskGrant grant = registry.currentTaskGrant();
        return new AuditLog.ExecutionIdentity(
                ToolAccessContext.toolTaskIdentity.get(), registry.currentResourceLeaseStep(),
                scope != null && !grant.isEmpty() ? scope.id : null,
                context.invocationId(), context.attribute(ToolRegistry.PIPELINE_APPROVAL_ID, String.class),
                grant.isEmpty() ? null : grant.summary());
    }

    ToolOutput executeResolvedTool(ToolExecutionPipeline.Context context) {
        String approvalProject = context.attribute("approval.project", String.class);
        if (approvalProject != null && !approvalProject.equals(registry.projectPath)) {
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, "审批期间项目路径已改变，请在新项目重新发起调用");
        }
        var deletion = context.attribute(com.devcli.tool.provider.DeleteFilesTool.PLAN_ATTRIBUTE,
                com.devcli.tool.provider.DeleteFilesTool.Plan.class);
        if (deletion != null) {
            return com.devcli.tool.provider.DeleteFilesTool.execute(registry, deletion, context.executionContext());
        }
        McpRegisteredTool mcpTool = registry.activeMcpTool(context.name());
        if (mcpTool != null) {
            ToolOutput output = mcpTool.invoker().invoke(
                    context.argumentsJson(), context.executionContext());
            if (registry.browserGuard != null) {
                registry.browserGuard.applyAfterExecution(context.name(), context.argumentsJson(), output == null ? "" : output.text());
            }
            return registry.toolEffect(context.name()) == ToolEffect.READ_ONLY
                    ? reviewToolResult(context.name(), output) : output;
        }

        Tool tool = registry.activeTool(context.name());
        JsonNode parsedArgs = context.attribute(ToolRegistry.PIPELINE_PARSED_ARGUMENTS, JsonNode.class);
        if (parsedArgs == null) {
            parsedArgs = parseValidatedArguments(context.argumentsJson());
        }
        Map<String, String> argMap = new HashMap<>();
        parsedArgs.fields().forEachRemaining(entry ->
                argMap.put(entry.getKey(), entry.getValue().isTextual()
                        ? entry.getValue().asText() : entry.getValue().toString()));
        ToolOutput output = tool.executor().executeOutput(argMap, context.executionContext());
        if (output != null && java.util.Set.of("grep_code", "search_code", "read_tool_result")
                .contains(context.name())) {
            return reviewToolResult(context.name(), output);
        }
        return output;
    }

    ToolOutput reviewToolResult(String name, ToolOutput output) {
        if (output != null) {
            var inspection = com.devcli.policy.SensitiveContentPolicy.inspect(output.text());
            if (inspection.sensitive()) {
                var decision = registry.reviewSensitiveContent(name, inspection,
                        "将工具结果提供给模型", "当前会话模型提供方", true);
                if (decision == com.devcli.policy.SensitiveContentPolicy.Decision.REJECT) {
                    return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED, "敏感工具结果未获批准，未返回原文");
                }
                if (decision == com.devcli.policy.SensitiveContentPolicy.Decision.REDACT) {
                    // Side channels may contain raw evidence; do not retain them after redaction.
                    return new ToolOutput(output.status(), output.errorCode(), output.retryable(),
                            inspection.sanitized(), List.of(), output.modifiedResources(), List.of());
                }
            }
        }
        return output;
    }

    static String unknownToolGuidance(String name) {
        String toolName = name == null || name.isBlank() ? "(empty)" : name;
        String query = toolName
                .replace("mcp__", "")
                .replace("__", " ")
                .replace('_', ' ')
                .trim();
        if (query.isBlank()) {
            query = toolName;
        }
        return "未知工具: " + toolName
                + "\n可先调用 search_tools 查找可用工具，例如: {\"query\":\""
                + escapeJson(query) + "\"}";
    }

    static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    ToolOutput validateSkillToolAllowed(String name) {
        SkillContextBuffer buffer = registry.activeSkillContextBuffer();
        if (buffer == null) {
            return null;
        }
        Set<String> allowedTools = buffer.activeAllowedTools();
        if (allowedTools.isEmpty() || registry.allowsToolName(allowedTools, name)) {
            return null;
        }
        return ToolOutput.rejected(ToolErrorCode.SKILL_PERMISSION_DENIED,
                "Skill 工具权限拒绝: 当前已加载 Skill 只允许使用 "
                        + String.join(", ", allowedTools)
                        + "；被拒绝工具: " + name);
    }

    ToolOutput validateToolArguments(String name, String argumentsJson) {
        Tool tool = registry.activeTool(name);
        if (tool == null) {
            return null;
        }
        JsonNode parsedArgs;
        try {
            parsedArgs = parseArguments(argumentsJson);
        } catch (JsonProcessingException e) {
            return validationFailed("不是合法 JSON: " + e.getOriginalMessage());
        }
        JsonNode schema = tool.parameters();
        McpRegisteredTool mcpTool = registry.activeMcpTool(name);
        if (mcpTool != null) {
            schema = mcpTool.descriptor().inputSchema();
        }
        McpSchemaValidator.ValidationResult validation = McpSchemaValidator.validate(schema, parsedArgs);
        if (!validation.valid()) {
            return validationFailed(validation.message());
        }
        return null;
    }

    /** 在 Schema 校验后执行参数组合、资源状态和扩展业务规则校验。 */
    ToolOutput validateToolSemantics(String name, String argumentsJson) {
        JsonNode parsedArgs;
        try {
            parsedArgs = parseArguments(argumentsJson);
        } catch (JsonProcessingException e) {
            return validationFailed("不是合法 JSON: " + e.getOriginalMessage());
        }
        ToolSemanticValidator.ValidationResult result = ToolSemanticValidator.validate(
                name, parsedArgs,
                new ToolSemanticValidator.Context(
                        registry.contextProjectRoot,
                        registry::resolveSafePath,
                        registry::isWritePathAllowed,
                        registry.catalog.semanticValidators.get(name),
                        registry.catalog.builtInSemanticToolNames.contains(name)));
        if (result.valid()) {
            return null;
        }
        if (result.errorCode() == ToolErrorCode.POLICY_DENIED
                || result.errorCode() == ToolErrorCode.CAPABILITY_DENIED) {
            String prefix = result.errorCode() == ToolErrorCode.POLICY_DENIED ? "策略拒绝: " : "";
            return ToolOutput.rejected(result.errorCode(), prefix + result.message(), result.retryable());
        }
        return ToolOutput.rejected(result.errorCode(),
                "业务语义校验失败: " + result.message(), result.retryable());
    }

    JsonNode parseValidatedArguments(String argumentsJson) {
        try {
            return parseArguments(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }

    JsonNode parseArguments(String argumentsJson) throws JsonProcessingException {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return ToolRegistry.mapper.createObjectNode();
        }
        return ToolRegistry.mapper.readTree(argumentsJson);
    }

    ToolOutput validationFailed(String message) {
        return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                "工具参数校验失败: "
                        + (message == null || message.isBlank() ? "参数不符合工具 schema" : message)
                        + "。请根据工具 JSON Schema 修正参数后重试。",
                true);
    }

    ToolOutput executeCommandOutput(String command, long timeoutSeconds, ToolExecutionContext executionContext) {
        ToolAccessScope scope = registry.currentToolAccessScope();
        boolean sandboxRequired = scope == ToolAccessScope.ISOLATED_PROJECT;
        CommandExecutionService service = registry.executionEnvironment.commands();
        ToolExecutionContext timed = new ToolExecutionContext(executionContext.invocationId(),
                executionContext.cancellationToken(), executionContext.startedAtNanos(),
                Math.min(executionContext.deadlineNanos(), executionContext.startedAtNanos()
                        + TimeUnit.SECONDS.toNanos(timeoutSeconds)));
        CommandExecutionService.Request request = new CommandExecutionService.Request(
                command, Path.of(registry.projectPath), timeoutSeconds, sandboxRequired, timed);
        if (request.executionContext().isCancelled()) {
            return ToolOutput.cancelled("主机执行审批或排队期间已取消，未启动进程");
        }
        if (request.executionContext().remainingNanos() == 0) {
            return ToolOutput.timedOut("审批或排队期间命令期限已到，未启动进程");
        }
        return service.execute(request).toToolOutput();
    }

    ToolOutput validateCommandRequest(ToolExecutionPipeline.Context context) {
        if (registry.toolEffect(context.name()) != ToolEffect.HOST_PROCESS) return null;
        JsonNode args = parseValidatedArguments(context.argumentsJson());
        long timeout = args.has("timeout") ? args.path("timeout").asLong() : registry.commandTimeoutSeconds;
        try {
            registry.executionEnvironment.commands().validateRequest(new CommandExecutionService.Request(
                    args.path("command").asText(), Path.of(registry.projectPath), timeout,
                    registry.currentToolAccessScope() == ToolAccessScope.ISOLATED_PROJECT, context.executionContext()));
            return null;
        } catch (PolicyException denied) {
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, denied.getMessage());
        }
    }
    /** 返回工具声明的副作用等级；未知工具按外部副作用保守处理。 */
    ToolEffect toolEffect(String name) {
        Tool tool = registry.activeTool(name);
        return tool == null ? ToolEffect.EXTERNAL_MUTATION : tool.effect();
    }

    /** 返回工具声明的破坏性等级；未知工具按实质性外部变更保守处理。 */
    Destructiveness toolDestructiveness(String name) {
        Tool tool = registry.activeTool(name);
        return tool == null
                ? Destructiveness.STRUCTURAL
                : tool.destructiveness();
    }

    /** 返回工具声明的幂等性；未知工具按不可重试保守处理。 */
    Idempotency toolIdempotency(String name) {
        Tool tool = registry.activeTool(name);
        return tool == null
                ? Idempotency.NON_IDEMPOTENT
                : tool.idempotency();
    }

    /** 返回工具是否声明可进入短期结果缓存；未知工具不缓存。 */
    boolean toolCacheable(String name) {
        Tool tool = registry.activeTool(name);
        return tool != null && tool.cacheable();
    }

    ToolPresentation toolPresentation(String name) {
        Tool tool = registry.activeTool(name);
        return tool == null ? ToolPresentation.generic(name) : tool.presentation();
    }

    boolean requiresApproval(String name) {
        if (name != null && name.startsWith("mcp__")) {
            Tool tool = registry.activeTool(name);
            return tool == null || ToolRegistry.isMaterialSideEffect(tool.effect());
        }
        Optional<BuiltInToolPolicy.Policy> policy = BuiltInToolPolicy.find(name);
        if (policy.isPresent()) {
            return policy.get().requiresApproval();
        }
        Tool tool = registry.activeTool(name);
        return tool != null && ToolRegistry.isMaterialSideEffect(tool.effect());
    }

    boolean requiresAudit(String name) {
        if (name != null && name.startsWith("mcp__")) {
            return true;
        }
        Optional<BuiltInToolPolicy.Policy> policy = BuiltInToolPolicy.find(name);
        if (policy.isPresent()) {
            return policy.get().audited();
        }
        Tool tool = registry.activeTool(name);
        return tool != null && ToolRegistry.isMaterialSideEffect(tool.effect());
    }

    ToolCancellationCapability toolCancellationCapability(String toolName) {
        Tool tool = toolName == null ? null : registry.activeTool(toolName);
        return tool == null
                ? ToolCancellationCapability.INTERRUPT_ONLY
                : tool.cancellationCapability();
    }

}
