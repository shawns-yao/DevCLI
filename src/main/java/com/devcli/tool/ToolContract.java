package com.devcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.devcli.llm.LlmClient;
import com.devcli.mcp.config.McpToolTrustPolicy;
import com.devcli.mcp.protocol.McpToolDescriptor;

import java.util.*;
import java.util.concurrent.*;

import static com.devcli.tool.ToolContract.*;

/** 工具定义、执行结果和能力范围的共享值类型。 */
public final class ToolContract {
    private ToolContract() { }
    public enum ToolEffect {
        READ_ONLY,
        LOCAL_CONTEXT,
        PROJECT_MUTATION,
        HOST_PROCESS,
        EXTERNAL_MUTATION;

        static ToolEffect fromMcp(McpToolDescriptor descriptor,
                                  McpToolTrustPolicy trustPolicy) {
            McpToolTrustPolicy policy = trustPolicy == null
                    ? McpToolTrustPolicy.untrusted()
                    : trustPolicy;
            return policy.isReadOnly(descriptor) ? READ_ONLY : EXTERNAL_MUTATION;
        }

        static ToolEffect builtIn(String name) {
            return BuiltInToolPolicy.effectOrDefault(name);
        }
    }

    /**
     * 工具底层物理影响的破坏性等级，与资源域（{@link ToolEffect}）正交。
     *
     * <p>资源域回答"这次变更落在哪里"，破坏性等级回答"变更多严重"。
     * 只读工具不一定没有状态变更：已读标记、游标推进、访问计数这类
     * 不可逆但无资损的流转属于 {@link #BENIGN}，不能按名字或 annotations 推断。</p>
     */
    public enum Destructiveness {
        /** 绝对只读，执行前后环境一致。 */
        NONE,
        /** 良性状态流转：不可逆但没有资损，例如已读标记、游标推进。 */
        BENIGN,
        /** 实质性业务写入：创建、更新、删除业务主体。 */
        STRUCTURAL
    }

    /**
     * 工具是否具备可重试性，决定能否并发、能否进入短期结果缓存。
     *
     * <p>只读但会推进状态（收件箱已读、消息出队、消费位点提交）属于
     * {@link #NON_IDEMPOTENT}：重复调用结果不同，并发调用会互相竞态。</p>
     */
    public enum Idempotency {
        IDEMPOTENT,
        NON_IDEMPOTENT
    }

    public enum ToolAccessScope {
        FULL(2) {
            @Override
            public boolean permits(ToolEffect effect) {
                return true;
            }
        },
        READ_ONLY(0) {
            @Override
            public boolean permits(ToolEffect effect) {
                return effect == ToolEffect.READ_ONLY || effect == ToolEffect.LOCAL_CONTEXT;
            }
        },
        ISOLATED_PROJECT(1) {
            @Override
            public boolean permits(ToolEffect effect) {
                return effect != ToolEffect.EXTERNAL_MUTATION;
            }
        };

        /** 允许集合的包含关系：READ_ONLY ⊂ ISOLATED_PROJECT ⊂ FULL。 */
        final int permissiveness;

        ToolAccessScope(int permissiveness) {
            this.permissiveness = permissiveness;
        }

        public abstract boolean permits(ToolEffect effect);

        /**
         * 取两个范围的交集。
         *
         * <p>嵌套使用能力范围时只能收窄、不能放宽：内层调用不得把外层的只读或隔离约束悄悄放开。</p>
         */
        public ToolAccessScope narrow(ToolAccessScope other) {
            if (other == null || other == this) {
                return this;
            }
            return permissiveness <= other.permissiveness ? this : other;
        }
    }

    public enum ToolCancellationCapability {
        COOPERATIVE,
        INTERRUPT_ONLY
    }

    /** 一次模型回复所对应的不可变工具定义与执行器绑定。 */
    public static final class ToolSnapshot {
        final ToolRegistry owner;
        final long catalogVersion;
        final List<LlmClient.Tool> definitions;
        final Map<String, ToolBinding> bindings;

        ToolSnapshot(ToolRegistry owner, long catalogVersion,
                             List<LlmClient.Tool> definitions,
                             Map<String, ToolBinding> bindings) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.catalogVersion = catalogVersion;
            this.definitions = List.copyOf(definitions == null ? List.of() : definitions);
            this.bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        }

        public List<LlmClient.Tool> definitions() {
            return definitions;
        }

        public long catalogVersion() {
            return catalogVersion;
        }

        public ToolEffect effect(String name) {
            ToolBinding binding = binding(name);
            return binding == null ? null : binding.tool().effect();
        }

        /** 按模型可见定义收窄快照，执行绑定仍来自同一次捕获。 */
        public ToolSnapshot filter(Set<String> names) {
            if (names == null) {
                return this;
            }
            List<LlmClient.Tool> filtered = definitions.stream()
                    .filter(definition -> names.contains(definition.name()))
                    .toList();
            return withDefinitions(filtered);
        }

        /** 使用已冻结的模型定义替换展示列表，保留快照捕获的全部执行绑定。 */
        public ToolSnapshot withDefinitions(List<LlmClient.Tool> expectedDefinitions) {
            List<LlmClient.Tool> frozen = List.copyOf(
                    expectedDefinitions == null ? List.of() : expectedDefinitions);
            // 展示列表可以按角色、allowlist 或能力范围收窄，但隐藏工具仍需保留
            // 原始绑定，以便模型越权调用时进入统一的 CAPABILITY_DENIED 管线，
            // 而不是被误报为快照缺少工具。
            return new ToolSnapshot(owner, catalogVersion, frozen, bindings);
        }

        ToolBinding binding(String name) {
            return bindings.get(name);
        }

        ToolPresentation presentation(String name) {
            ToolBinding binding = binding(name);
            return binding == null
                    ? ToolPresentation.generic(name)
                    : binding.tool().presentation();
        }
    }

    record ToolBinding(Tool tool, McpRegisteredTool mcpTool) {
    }

    public record Tool(String name, String description, JsonNode parameters,
                       ToolExecutor executor, ToolEffect effect, long timeoutSeconds,
                       ToolCancellationCapability cancellationCapability,
                       ToolPresentation presentation,
                       Destructiveness destructiveness,
                       Idempotency idempotency,
                       boolean cacheable) {
        /** 工具归属和物理影响共同限制访问；角色只可继续收窄。 */
        public boolean permitsAccess(ToolAccessScope scope, boolean delegatedChild) {
            return scope.permits(effect) && (!delegatedChild
                    || !BuiltInToolPolicy.policyOrDefault(name, effect).mainAgentOnly());
        }

        /** 兼容既有 8 参调用：契约从内置显式声明或 effect 保守默认推导。 */
        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor,
                    ToolEffect effect, long timeoutSeconds,
                    ToolCancellationCapability cancellationCapability,
                    ToolPresentation presentation) {
            this(name, description, parameters, executor, effect, timeoutSeconds,
                    cancellationCapability, presentation,
                    BuiltInToolPolicy.destructivenessOrDefault(name, effect),
                    BuiltInToolPolicy.idempotencyOrDefault(name, effect),
                    BuiltInToolPolicy.cacheableOrDefault(name, effect));
        }

        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {
            this(name, description, parameters, executor, ToolEffect.builtIn(name), -1,
                    inferCancellationCapability(executor),
                    ToolPresentation.defaultFor(name));
        }

        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor,
                    ToolEffect effect) {
            this(name, description, parameters, executor, effect, -1,
                    inferCancellationCapability(executor),
                    ToolPresentation.defaultFor(name));
        }

        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor,
                    ToolEffect effect, long timeoutSeconds) {
            this(name, description, parameters, executor, effect, timeoutSeconds,
                    inferCancellationCapability(executor),
                    ToolPresentation.defaultFor(name));
        }

        public Tool(String name, String description, JsonNode parameters, ToolExecutor executor,
                    ToolEffect effect, long timeoutSeconds, ToolPresentation presentation) {
            this(name, description, parameters, executor, effect, timeoutSeconds,
                    inferCancellationCapability(executor), presentation);
        }

        public static Tool structured(String name, String description, JsonNode parameters,
                                      StructuredToolExecutor executor) {
            return new Tool(name, description, parameters, executor, ToolEffect.builtIn(name), -1,
                    ToolCancellationCapability.INTERRUPT_ONLY,
                    ToolPresentation.defaultFor(name));
        }

        /**
         * 声明独立超时的结构化工具。timeoutSeconds 为单个工具的执行上限（秒），
         * 批量执行时按该 deadline 强制取消；未声明（-1）继承批次超时。
         */
        public static Tool structured(String name, String description, JsonNode parameters,
                                      StructuredToolExecutor executor, long timeoutSeconds) {
            return new Tool(name, description, parameters, executor, ToolEffect.builtIn(name),
                    timeoutSeconds, ToolCancellationCapability.INTERRUPT_ONLY,
                    ToolPresentation.defaultFor(name));
        }

        public static Tool contextualStructured(
                String name,
                String description,
                JsonNode parameters,
                ContextualStructuredToolExecutor executor,
                long timeoutSeconds) {
            return new Tool(name, description, parameters, executor, ToolEffect.builtIn(name),
                    timeoutSeconds, ToolCancellationCapability.COOPERATIVE,
                    ToolPresentation.defaultFor(name));
        }

        public Tool {
            effect = effect == null ? ToolEffect.EXTERNAL_MUTATION : effect;
            destructiveness = destructiveness == null
                    ? BuiltInToolPolicy.destructivenessOrDefault(name, effect)
                    : destructiveness;
            idempotency = idempotency == null
                    ? BuiltInToolPolicy.idempotencyOrDefault(name, effect)
                    : idempotency;
            cancellationCapability = cancellationCapability == null
                    ? inferCancellationCapability(executor)
                    : cancellationCapability;
            presentation = presentation == null
                    ? ToolPresentation.defaultFor(name)
                    : presentation;
            if (timeoutSeconds == 0 || timeoutSeconds < -1) {
                throw new IllegalArgumentException(
                        "工具 " + name + " 的 timeoutSeconds 必须为正整数或 -1: " + timeoutSeconds);
            }
        }

        /** 是否声明了独立超时（&gt; 0 生效，-1 继承批次超时）。 */
        public boolean hasOwnTimeout() {
            return timeoutSeconds > 0;
        }

        static ToolCancellationCapability inferCancellationCapability(ToolExecutor executor) {
            return executor instanceof ContextualStructuredToolExecutor
                    ? ToolCancellationCapability.COOPERATIVE
                    : ToolCancellationCapability.INTERRUPT_ONLY;
        }
    }

    record McpRegisteredTool(McpToolDescriptor descriptor, McpToolInvoker invoker) {}

    record PendingMcpTool(String name, McpRegisteredTool registered, Tool tool) {}

    @FunctionalInterface
    public interface McpToolInvoker {
        ToolOutput invoke(String argumentsJson, ToolExecutionContext executionContext);
    }

    /**
     * 长期记忆写入结果。
     *
     * @param stored  是否落盘
     * @param message 面向用户的说明
     * @param id      落盘文件名；失败时为空串
     */
    public record MemorySaveResult(boolean stored, String message, String id) {
        public MemorySaveResult(boolean stored, String message) {
            this(stored, message, "");
        }
    }

    public record MemorySaveRequest(String fact, String name, String description,
                                    String type, String scope, Integer validDays) {
        public MemorySaveRequest {
            fact = fact == null ? "" : fact;
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            type = type == null ? "" : type;
            scope = scope == null ? "" : scope;
        }
    }

    @FunctionalInterface
    public interface MemoryListHandler {
        String list(int limit);
    }

    @FunctionalInterface
    public interface MemorySaver {
        MemorySaveResult save(String fact);

        default MemorySaveResult save(MemorySaveRequest request) {
            return save(request == null ? "" : request.fact());
        }
    }

    public record ToolInvocation(String id, String name, String argumentsJson) {}

    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis,
                                      ToolStatus status, ToolErrorCode errorCode, boolean retryable,
                                      List<com.devcli.llm.LlmClient.ContentPart> imageParts,
                                      List<ToolSideChannel> sideChannels,
                                      ToolPresentation presentation) {
        public ToolExecutionResult {
            imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
            sideChannels = sideChannels == null ? List.of() : List.copyOf(sideChannels);
            presentation = presentation == null
                    ? ToolPresentation.defaultFor(name)
                    : presentation;
        }

        /** 兼容迁移前的工具执行结果构造方式。 */
        public ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis,
                                   ToolStatus status, ToolErrorCode errorCode, boolean retryable,
                                   List<com.devcli.llm.LlmClient.ContentPart> imageParts) {
            this(id, name, argumentsJson, result, elapsedMillis, status, errorCode,
                    retryable, imageParts, List.of(), ToolPresentation.defaultFor(name));
        }

        /** 兼容迁移前带 side channel 的构造方式。 */
        public ToolExecutionResult(String id, String name, String argumentsJson,
                                   String result, long elapsedMillis,
                                   ToolStatus status, ToolErrorCode errorCode, boolean retryable,
                                   List<com.devcli.llm.LlmClient.ContentPart> imageParts,
                                   List<ToolSideChannel> sideChannels) {
            this(id, name, argumentsJson, result, elapsedMillis, status, errorCode,
                    retryable, imageParts, sideChannels, ToolPresentation.defaultFor(name));
        }

        static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output,
                                                     long elapsedMillis) {
            return completed(invocation, output, elapsedMillis,
                    ToolPresentation.defaultFor(invocation.name()));
        }

        static ToolExecutionResult completed(ToolInvocation invocation, ToolOutput output,
                                                     long elapsedMillis,
                                                     ToolPresentation presentation) {
            String result = output == null ? "" : output.text();
            List<com.devcli.llm.LlmClient.ContentPart> images = output == null ? List.of() : output.imageParts();
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    result,
                    elapsedMillis,
                    output == null ? ToolStatus.SUCCESS : output.status(),
                    output == null ? ToolErrorCode.NONE : output.errorCode(),
                    output != null && output.retryable(),
                    images,
                    output == null ? List.of() : output.sideChannels(),
                    presentation);
        }

        public static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return failed(invocation, message, 0,
                    ToolPresentation.defaultFor(invocation.name()));
        }

        public static ToolExecutionResult failed(ToolInvocation invocation, String message,
                                                 long elapsedMillis,
                                                 ToolPresentation presentation) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行失败: " + message,
                    elapsedMillis,
                    ToolStatus.ERROR,
                    ToolErrorCode.EXECUTION_FAILED,
                    true,
                    List.of(),
                    List.of(),
                    presentation);
        }

        static ToolExecutionResult staleToolSnapshot(
                ToolInvocation invocation, String message, ToolPresentation presentation) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    message,
                    0,
                    ToolStatus.REJECTED,
                    ToolErrorCode.STALE_TOOL_SNAPSHOT,
                    true,
                    List.of(),
                    List.of(),
                    presentation);
        }

        public static ToolExecutionResult cancelled(ToolInvocation invocation, String message) {
            return cancelled(invocation, message, 0,
                    ToolPresentation.defaultFor(invocation.name()));
        }

        public static ToolExecutionResult cancelled(ToolInvocation invocation, String message,
                                                    long elapsedMillis,
                                                    ToolPresentation presentation) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    message,
                    elapsedMillis,
                    ToolStatus.CANCELLED,
                    ToolErrorCode.CANCELLED,
                    false,
                    List.of(),
                    List.of(),
                    presentation);
        }

        static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds,
                                                    long elapsedMillis,
                                                    ToolPresentation presentation) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    elapsedMillis,
                    ToolStatus.TIMEOUT,
                    ToolErrorCode.TIMEOUT,
                    true,
                    List.of(),
                    List.of(),
                    presentation
            );
        }

        public boolean timedOut() {
            return status == ToolStatus.TIMEOUT;
        }

        public boolean hasImageParts() {
            return imageParts != null && !imageParts.isEmpty();
        }
    }

    public interface ToolExecutor {
        String execute(Map<String, String> args);

        /** 工具显式声明的调用期限选择；参数不合法时仍由正常校验管线报告。 */
        default long executionTimeoutSeconds(String argumentsJson, long configuredSeconds) {
            return configuredSeconds;
        }

        default ToolOutput executeOutput(Map<String, String> args) {
            return ToolOutput.success(execute(args));
        }

        default ToolOutput executeOutput(Map<String, String> args,
                                         ToolExecutionContext executionContext) {
            executionContext.throwIfCancelled();
            return executeOutput(args);
        }
    }

    @FunctionalInterface
    public interface StructuredToolExecutor extends ToolExecutor {
        ToolOutput executeStructured(Map<String, String> args);

        @Override
        default String execute(Map<String, String> args) {
            return executeStructured(args).text();
        }

        @Override
        default ToolOutput executeOutput(Map<String, String> args) {
            return executeStructured(args);
        }
    }

    @FunctionalInterface
    public interface ContextualStructuredToolExecutor extends ToolExecutor {
        ToolOutput executeStructured(Map<String, String> args,
                                     ToolExecutionContext executionContext);

        @Override
        default String execute(Map<String, String> args) {
            return executeStructured(args, ToolExecutionContext.current("")).text();
        }

        @Override
        default ToolOutput executeOutput(Map<String, String> args) {
            return executeStructured(args, ToolExecutionContext.current(""));
        }

        @Override
        default ToolOutput executeOutput(Map<String, String> args,
                                         ToolExecutionContext executionContext) {
            executionContext.throwIfCancelled();
            return executeStructured(args, executionContext);
        }
    }
}
