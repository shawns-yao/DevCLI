package com.devcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.devcli.browser.BrowserCheckResult;
import com.devcli.browser.BrowserGuard;
import com.devcli.policy.AuditLog;
import com.devcli.policy.PolicyException;

import java.util.*;
import java.util.concurrent.*;

import static com.devcli.tool.ToolContract.*;

/** 统一执行管线的阶段装配。 */
final class ToolPipelineAssembly {
    private final ToolRegistry registry;
    ToolPipelineAssembly(ToolRegistry registry) { this.registry = registry; }
    void configureExecutionPipeline() {
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.HITL, (context, chain) -> {
            ToolOutput denied = registry.executionCore.validateCommandRequest(context);
            if (denied != null) registry.recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                    context.name(), context.argumentsJson(), denied.text(), 0), context);
            return denied == null ? registry.reviewToolApproval(context, chain) : denied;
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.CANCELLATION, (context, chain) ->
                context.executionContext().isCancelled()
                        ? ToolOutput.cancelled("用户取消了此次工具调用")
                        : chain.proceed(context));
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.EXISTENCE, (context, chain) ->
                registry.activeTool(context.name()) != null
                        ? chain.proceed(context)
                        : ToolOutput.error(ToolErrorCode.UNKNOWN_TOOL,
                        ToolExecutionCore.unknownToolGuidance(context.name()), true));
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.CAPABILITY, (context, chain) -> {
            Tool tool = registry.activeTool(context.name());
            ToolAccessScope scope = registry.currentToolAccessScope();
            Set<String> allowlist = registry.access.allowedToolNames.get();
            if (tool != null && (!registry.allowsToolName(allowlist, context.name())
                    || !tool.permitsAccess(scope, registry.access.delegatedChild))) {
                if (registry.requiresAudit(context.name())) {
                    registry.recordToolAudit(AuditLog.AuditEntry.denyByPolicy(context.name(),
                            context.argumentsJson(), "CAPABILITY_DENIED", 0), context);
                }
                return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                        "工具能力被当前执行范围拒绝: " + context.name()
                                + " (scope=" + scope + ", effect=" + tool.effect() + ")"
                                + "；当前允许工具: " + registry.searchableTools().stream()
                                .map(Tool::name).sorted().limit(8).collect(java.util.stream.Collectors.joining(", "))
                                + "；可通过 search_tools 查询当前权限内的工具，不得换工具绕过限制");
            }
            if (tool != null && !registry.access.delegatedChild && !ToolAccessContext.isDelegationTool(context.name())
                    && ToolRegistry.isMaterialSideEffect(tool.effect())) {
                SynchronousWorkerGate.Lease lease = registry.synchronousWorkerGate.tryEnterMainMutation();
                if (lease == null) {
                    return ToolOutput.rejected(ToolErrorCode.RESOURCE_CONFLICT,
                            "同步 worker 正在执行，主 Agent 必须等待写入权交还后再修改", true);
                }
                try (lease) { return chain.proceed(context); }
            }
            return chain.proceed(context);
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.SKILL_PERMISSION, (context, chain) -> {
            ToolOutput error = registry.validateSkillToolAllowed(context.name());
            return error == null ? chain.proceed(context) : error;
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.ARGUMENT_VALIDATION, (context, chain) -> {
            ToolOutput error = registry.validateToolArguments(context.name(), context.argumentsJson());
            if (error != null) {
                return error;
            }
            context.putAttribute(ToolRegistry.PIPELINE_PARSED_ARGUMENTS, registry.executionCore.parseValidatedArguments(context.argumentsJson()));
            if ("delete_files".equals(context.name()) && registry.catalog.builtInSemanticToolNames.contains(context.name())) {
                try {
                    context.putAttribute(com.devcli.tool.provider.DeleteFilesTool.PLAN_ATTRIBUTE,
                            com.devcli.tool.provider.DeleteFilesTool.prepare(registry,
                                    registry.executionCore.parseValidatedArguments(context.argumentsJson())));
                } catch (Exception e) {
                    ToolOutput rejected = ToolOutput.rejected(e instanceof PolicyException
                                    ? ToolErrorCode.POLICY_DENIED : ToolErrorCode.INVALID_ARGUMENTS,
                            "删除预检失败: " + e.getMessage(), false);
                    registry.recordToolAudit(AuditLog.AuditEntry.denyByPolicy(context.name(),
                            context.argumentsJson(), rejected.text(), 0), context);
                    return rejected;
                }
            }
            return chain.proceed(context);
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.SEMANTIC_VALIDATION, (context, chain) -> {
            ToolOutput error = registry.validateToolSemantics(context.name(), context.argumentsJson());
            if (error != null && registry.requiresAudit(context.name())) {
                registry.recordToolAudit(AuditLog.AuditEntry.denyByPolicy(
                        context.name(), context.argumentsJson(), error.text(), 0), context);
            }
            return error == null ? chain.proceed(context) : error;
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.CONTENT_REVIEW, (context, chain) -> {
            String name = context.name();
            if ("save_memory".equals(name)) {
                ToolOutput denial = registry.reviewMemorySave(context);
                return denial == null ? chain.proceed(context) : denial;
            }
            boolean web = "web_fetch".equals(name) || "web_search".equals(name);
            boolean mcp = registry.activeMcpTool(name) != null;
            if (!web && !mcp) return chain.proceed(context);
            JsonNode arguments = registry.executionCore.parseValidatedArguments(context.argumentsJson());
            var inspection = com.devcli.policy.SensitiveContentPolicy.inspectArguments(arguments);
            boolean upload = com.devcli.browser.BrowserGuard.isChromeTool(name)
                    && name.endsWith("__upload_file");
            if (upload) {
                inspection = new com.devcli.policy.SensitiveContentPolicy.Inspection(
                        inspection.sanitized(), java.util.Set.of("file_upload"));
            }
            if (!inspection.sensitive()) return chain.proceed(context);
            boolean redactable = "web_search".equals(name)
                    || (com.devcli.browser.BrowserGuard.isChromeTool(name)
                    && (name.endsWith("__fill") || name.endsWith("__fill_form")));
            String purpose = web ? "发送网络请求参数"
                    : com.devcli.browser.BrowserGuard.isChromeTool(name)
                    ? "向浏览器页面输入或传递内容" : "向 MCP 服务发送工具参数";
            String target = "web_fetch".equals(name)
                    ? com.devcli.policy.SensitiveContentPolicy.hostOnly(arguments.path("url").asText())
                    : "web_search".equals(name) ? "当前配置的搜索服务"
                    : com.devcli.browser.BrowserGuard.isChromeTool(name)
                    ? (registry.browserGuard == null ? "浏览器目标页面未知"
                    : registry.browserGuard.contentTarget())
                    : "MCP 服务 " + McpToolCatalog.normalizeMcpServerName(
                            registry.activeMcpTool(name).descriptor().serverName());
            var decision = registry.reviewSensitiveContent(name, inspection, purpose, target, redactable);
            if (decision == com.devcli.policy.SensitiveContentPolicy.Decision.REJECT
                    || (decision == com.devcli.policy.SensitiveContentPolicy.Decision.REDACT && !redactable)) {
                return ToolOutput.rejected(ToolErrorCode.HITL_REJECTED,
                        "敏感内容未获单次批准，未调用网络或浏览器工具");
            }
            if (decision == com.devcli.policy.SensitiveContentPolicy.Decision.REDACT) {
                ToolOutput error = registry.validateToolArguments(name, inspection.sanitized());
                if (error != null) return ToolOutput.rejected(ToolErrorCode.INVALID_ARGUMENTS,
                        "脱敏后参数不满足工具契约，未执行");
                context.replaceArguments(inspection.sanitized());
            }
            context.putAttribute("content.reviewed.arguments", context.argumentsJson());
            return chain.proceed(context);
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.AUDIT, registry.executionCore::executeWithAudit);
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.POLICY, (context, chain) -> {
            if ((registry.access.delegatedChild || registry.currentToolAccessScope() != ToolAccessScope.FULL)
                    && "read_tool_result".equals(context.name())) {
                JsonNode arguments = context.attribute(ToolRegistry.PIPELINE_PARSED_ARGUMENTS, JsonNode.class);
                if (!ToolResultArtifactStore.belongsToCurrentRun(arguments.path("result_ref").asText())) {
                    return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED,
                            "子 Agent 只能读取本次子任务生成的工具结果；其他证据必须由父 Agent 显式提供");
                }
            }
            if (registry.activeMcpTool(context.name()) != null) {
                BrowserCheckResult browserCheck = registry.checkBrowserTool(
                        context.name(), context.argumentsJson(), false);
                context.putAttribute(ToolRegistry.PIPELINE_BROWSER_AUDIT, browserCheck.metadata());
                if (browserCheck.blocked()) {
                    throw new PolicyException(browserCheck.reason());
                }
            }
            return chain.proceed(context);
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.RESULT_CACHE, (context, chain) -> {
            Tool tool = registry.activeTool(context.name());
            if (tool == null) return chain.proceed(context);
            // 缓存资格由契约显式声明：只读但仍依赖外部可变状态的工具
            // （文件、网络、记忆、浏览器态）在 BuiltInToolPolicy 中声明 cacheable=false。
            if (tool.effect() != ToolEffect.READ_ONLY || !tool.cacheable()) {
                registry.toolResultCache.beginMutation();
                try {
                    return chain.proceed(context);
                } finally {
                    registry.toolResultCache.endMutation();
                }
            }
            if (ToolAccessContext.toolTaskIdentity.get() == null || registry.activeMcpTool(context.name()) != null) {
                return chain.proceed(context);
            }
            long generation = registry.toolResultCache.generation();
            String fingerprint = ToolAccessContext.toolTaskIdentity.get() + "|" + registry.currentResourceLeaseStep() + "|"
                    + registry.currentToolAccessScope().name() + "|"
                    + ToolInvocationFingerprint.of(context.name(), context.argumentsJson());
            ToolOutput cached = registry.toolResultCache.get(fingerprint);
            if (cached != null) return cached;
            ToolOutput output = chain.proceed(context);
            registry.toolResultCache.putIfGeneration(fingerprint, output, generation);
            return output;
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.RESULT_RECORDING, (context, chain) -> {
            ToolOutput output = chain.proceed(context);
            var sink = ToolAccessContext.resultEvents.get();
            if (sink != null) {
                ToolOutput original = context.attribute("journal.result", ToolOutput.class);
                sink.emit(com.devcli.event.RunEvent.ToolResultRecorded.from(
                        context.invocationId(), context.name(), context.argumentsJson(),
                        original == null ? output : original, context.executionContext().elapsedMillis(),
                        registry.toolPresentation(context.name())));
            }
            return output;
        });
        registry.executionPipeline.register(ToolExecutionPipeline.Stage.RESULT_GOVERNANCE, (context, chain) -> {
            long started = System.nanoTime();
            ToolOutput output = chain.proceed(context);
            context.putAttribute("journal.result", output);
            return registry.governToolOutput(context.name(), context.invocationId(), output,
                    registry.elapsedMillis(started));
        });
    }
}
