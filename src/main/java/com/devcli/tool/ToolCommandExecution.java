package com.devcli.tool;

import com.devcli.policy.PolicyException;
import com.devcli.tool.command.CommandExecutionService;
import com.devcli.workspace.PatchSet;
import com.devcli.workspace.WorkspaceExecutionSession;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static com.devcli.tool.ToolContract.*;

/** 命令请求、主 Agent 隔离工作区及变更回写。审批仍由工具管线统一执行。 */
final class ToolCommandExecution {
    private final ToolRegistry registry;

    ToolCommandExecution(ToolRegistry registry) { this.registry = registry; }

    boolean runsWithoutIsolation(ToolExecutionPipeline.Context context) {
        var args = registry.executionCore.parseValidatedArguments(context.argumentsJson());
        return !registry.executionEnvironment.commands().providesIsolation(!args.path("unsandboxed").asBoolean());
    }

    ToolOutput validate(ToolExecutionPipeline.Context context) {
        if (registry.toolEffect(context.name()) != ToolEffect.HOST_PROCESS) return null;
        var args = registry.executionCore.parseValidatedArguments(context.argumentsJson());
        boolean unsandboxed = args.path("unsandboxed").asBoolean();
        if (unsandboxed && (registry.currentToolAccessScope() != ToolAccessScope.FULL || registry.access.delegatedChild)) {
            return ToolOutput.rejected(ToolErrorCode.CAPABILITY_DENIED, "子任务不能请求脱离沙箱；请向主 Agent 报告所需依赖或网络访问");
        }
        long timeout = args.has("timeout") ? args.path("timeout").asLong() : registry.commandTimeoutSeconds;
        try {
            registry.executionEnvironment.commands().validateRequest(new CommandExecutionService.Request(
                    args.path("command").asText(), Path.of(registry.projectPath), timeout,
                    !unsandboxed, context.executionContext()));
            return null;
        } catch (PolicyException denied) {
            return ToolOutput.rejected(ToolErrorCode.POLICY_DENIED, denied.getMessage());
        }
    }

    ToolOutput execute(String command, long timeoutSeconds, boolean unsandboxed, ToolExecutionContext executionContext) {
        var timed = new ToolExecutionContext(executionContext.invocationId(),
                executionContext.cancellationToken(), executionContext.startedAtNanos(),
                Math.min(executionContext.deadlineNanos(), executionContext.startedAtNanos()
                        + TimeUnit.SECONDS.toNanos(timeoutSeconds)));
        if (timed.isCancelled()) return ToolOutput.cancelled("审批或排队期间已取消，未启动进程");
        if (timed.remainingNanos() == 0) return ToolOutput.timedOut("审批或排队期间命令期限已到，未启动进程");
        CommandExecutionService service = registry.executionEnvironment.commands();
        var request = new CommandExecutionService.Request(command, Path.of(registry.projectPath),
                timeoutSeconds, !unsandboxed, timed);
        boolean isolated = service.providesIsolation(!unsandboxed);
        if (registry.currentToolAccessScope() != ToolAccessScope.FULL
                || registry.access.delegatedChild || !isolated) {
            ToolOutput output = service.execute(request).toToolOutput();
            return isolated ? output : new ToolOutput(output.status(), output.errorCode(), output.retryable(),
                    "[执行环境：主机，未使用沙箱；文件与网络不受沙箱约束]\n" + output.text(),
                    output.imageParts(), output.modifiedResources(), output.sideChannels());
        }
        return executeInWorkspace(service, request);
    }

    private ToolOutput executeInWorkspace(CommandExecutionService service, CommandExecutionService.Request request) {
        ToolOutput output = ToolOutput.error(ToolErrorCode.EXECUTION_FAILED, "尚未启动沙箱命令", false);
        try (var workspace = WorkspaceExecutionSession.open(registry, request.executionContext().invocationId())) {
            request.executionContext().throwIfCancelled();
            var result = service.execute(new CommandExecutionService.Request(request.command(), workspace.workspacePath(),
                    request.timeoutSeconds(), true, request.executionContext()));
            output = result.toToolOutput();
            if (result.cancelled() || result.timedOut() || result.outputIncomplete()) return output;
            PatchSet patch = workspace.patchSet();
            if (patch.changes().stream().anyMatch(change -> change.type() == PatchSet.ChangeType.DELETE)) {
                return new ToolOutput(ToolStatus.REJECTED, ToolErrorCode.POLICY_DENIED, false,
                        output.text() + "\n沙箱产生了文件删除，本次变更未回写；请用 delete_files 提交明确文件清单和哈希审批。",
                        output.imageParts(), java.util.List.of(), output.sideChannels());
            }
            var applied = workspace.commit(patch, candidate -> {
                request.executionContext().throwIfCancelled();
                for (var change : candidate.changes()) {
                    if (!registry.isWritePathAllowed(change.relativePath())) {
                        throw new PolicyException("命令变更超出允许写入范围：" + change.relativePath());
                    }
                    Path safe = registry.resolveSafeWritePath(change.relativePath());
                    registry.acquireWriteLeaseChecked(registry.currentResourceLeaseStep(), safe, change.relativePath());
                }
            }, ignored -> { });
            if (!applied.applied()) {
                boolean conflict = !applied.conflicts().isEmpty();
                return new ToolOutput(ToolStatus.ERROR,
                        conflict ? ToolErrorCode.STALE_CONTEXT : ToolErrorCode.EXECUTION_FAILED, conflict,
                        output.text() + "\n沙箱变更回写失败：" + applied.failureDescription(),
                        output.imageParts(), applied.modifiedResources(), output.sideChannels());
            }
            output = output.withModifiedResources(applied.modifiedResources());
            for (String resource : applied.modifiedResources()) {
                registry.fileAccess.runPostEditLspHook(resource, registry.resolveSafePath(resource));
            }
            return output;
        } catch (CancellationException cancelled) {
            return new ToolOutput(ToolStatus.CANCELLED, ToolErrorCode.CANCELLED, false,
                    output.text() + "\n" + cancelled.getMessage(),
                    output.imageParts(), output.modifiedResources(), output.sideChannels());
        } catch (PolicyException denied) {
            return new ToolOutput(ToolStatus.REJECTED, ToolErrorCode.POLICY_DENIED, false,
                    output.text() + "\n沙箱变更被拒绝：" + denied.getMessage(),
                    output.imageParts(), output.modifiedResources(), output.sideChannels());
        } catch (Exception failure) {
            // 保留命令退出状态和已提交资源；工作区/提交失败不能冒充命令成功。
            return new ToolOutput(ToolStatus.ERROR, ToolErrorCode.EXECUTION_FAILED, false,
                    output.text() + "\n沙箱工作区失败：" + failure.getMessage(),
                    output.imageParts(), output.modifiedResources(), output.sideChannels());
        }
    }
}
