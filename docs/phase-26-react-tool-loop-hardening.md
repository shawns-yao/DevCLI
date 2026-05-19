# Phase 26: ReAct Tool Loop Hardening

> 目标：把当前 ReAct 工具调用链从“能执行并回灌”升级为“可调度、可恢复、可审计、可验证”。本文只定义改造方案，不改变当前代码。

## 1. 当前现状

当前 PaiCLI 已经具备以下能力：

- `Agent` / `PlanExecuteAgent` / `SubAgent` 都按 Function Calling 协议处理 `tool_calls`。
- assistant 的 `tool_calls` 会先写入 `conversationHistory`，随后工具结果按 `tool_call_id` 写回。
- `ToolRegistry.executeTools(...)` 支持同一轮多个工具并行执行，并保证结果按原始请求顺序返回。
- 工具失败会被转成文本 observation（观察结果）回灌给 LLM，而不是直接中断主循环。
- `ToolResultSizeManager` 已经做大结果治理：截断、落盘、预览，避免把超大工具输出直接塞进上下文。
- `WorkingMemory` 保存最近工具证据 / 任务状态 / 临时事实，作为 system prompt 派生视图注入。
- `ConversationHistoryCompactor` 压缩真实 LLM messages，并要求切分点落在 user message 边界，降低切断 `tool_call` / `tool_result` 协议对的风险。
- `ResourceConflictDetector` 已用于 Plan / Multi-Agent 的步骤级资源冲突拆分。
- `TraceRecorder` 已记录 LLM response 和 tool result 的基础 trace（轨迹）。

当前短板：

- `ToolExecutionResult` 只有 `result` 文本、`timedOut` 和图片信息，缺少统一 status / error code / retryable / side effect 元信息。
- 工具并行策略主要按调用数量执行，还没有基于工具元数据做“只读并行、写入加锁、浏览器串行、命令限流”的调度。
- 工具失败类型没有结构化区分，LLM 只能从文本里猜“参数错 / 超时 / 策略拒绝 / 资源冲突”。
- ReAct 主循环是隐式 while loop，没有显式状态机，恢复、回放和诊断成本较高。
- 压缩前后缺少独立 protocol validator（协议校验器）做全量 `tool_call_id` 配对检查。

## 2. 改造目标

### 2.1 结构化工具结果

新增 `ToolExecutionStatus`（工具执行状态）：

- `SUCCESS`
- `ERROR`
- `TIMEOUT`
- `DENIED`
- `CANCELLED`
- `VALIDATION_FAILED`

扩展 `ToolExecutionResult`：

- `status`
- `errorCode`
- `errorMessage`
- `retryable`
- `sideEffect`
- `resourceKeys`
- `elapsedMillis`
- `timedOut`
- `result`
- `imageParts`

原则：

- 写给 LLM 的 observation 仍然是稳定文本，避免破坏现有 prompt。
- 系统内部 trace / retry / scheduler 使用结构化字段。
- 失败不再只靠 `"工具执行失败: ..."` 字符串判断。

### 2.2 工具元数据

新增 `ToolMetadata`（工具元数据）：

- `readOnly`：是否只读
- `idempotent`：是否幂等
- `requiresApproval`：是否需要 HITL
- `exclusive`：是否独占执行
- `defaultTimeoutSeconds`
- `maxRetries`
- `resourceKeyExtractor`

默认策略：

| 工具类型 | 调度策略 |
|---|---|
| `read_file` / `list_dir` / `search_code` | 可并行 |
| `web_fetch` / `web_search` | 可并行但限流 |
| `write_file` / `create_project` / `revert_turn` | 按路径加锁 |
| `execute_command` | 默认独占或低并发 |
| `browser_*` / `mcp__chrome-devtools__*` | 串行 |
| 未知 MCP 工具 | 默认保守，按 `requiresApproval` + 独占处理 |

### 2.3 Tool Scheduler

新增 `ToolScheduler`（工具调度器），替代 `ToolRegistry.executeTools(...)` 内部直接 `invokeAll` 的策略判断。

输入：

- `List<ToolInvocation>`
- `ToolMetadataRegistry`
- 当前取消状态

输出：

- 按原始 invocation 顺序排列的 `List<ToolExecutionResult>`

职责：

- 只读工具并行。
- 写操作按 resource key 加锁。
- 浏览器 / shell / 高危 MCP 工具串行。
- 超时返回 `TIMEOUT` 状态。
- 策略拒绝返回 `DENIED` 状态。
- 参数校验失败返回 `VALIDATION_FAILED` 状态。
- 保证回灌顺序和原始 `tool_calls` 顺序一致。

### 2.4 ReAct 状态机

引入轻量 `ReActLoopState`（ReAct 循环状态）用于 trace 和测试，不要求第一阶段重写全部流程。

状态：

- `WAIT_LLM`
- `RECEIVED_TOOL_CALLS`
- `VALIDATING_TOOLS`
- `WAIT_HITL`
- `EXECUTING_TOOLS`
- `APPENDING_TOOL_RESULTS`
- `COMPACTING_CONTEXT`
- `FINISHED`
- `FAILED`

第一阶段只在 trace 中记录状态迁移，不改变主循环结构。

### 2.5 Protocol Validator

新增 `ConversationProtocolValidator`（对话协议校验器）。

校验点：

- 每个 assistant `tool_call_id` 必须有对应 tool result。
- 每个 tool result 必须能找到前置 assistant tool call。
- 压缩后的 history 不允许出现 orphan tool result（孤儿工具结果）。
- 不允许 tool result 出现在没有 tool call 的位置。
- 对图片工具结果，文本 result 和 image part 数量要一致。

接入点：

- `ConversationHistoryCompactor.compactIfNeeded(...)` 压缩前后。
- `Agent` / `PlanExecuteAgent` / `SubAgent` 每轮 append tool result 后的 debug 校验。
- 测试中作为硬断言。

## 3. 分阶段落地

### Phase 26-A: 结构化结果，不改调度行为

改动：

- 扩展 `ToolExecutionResult` 字段。
- 保留现有 `result()` / `timedOut()` 兼容方法。
- `completed(...)` 生成 `SUCCESS`。
- `failed(...)` 生成 `ERROR`。
- `timedOut(...)` 生成 `TIMEOUT`。
- MCP schema 校验失败映射为 `VALIDATION_FAILED`。
- `PolicyException` 映射为 `DENIED`。

测试：

- `ToolRegistryTest`
- `HitlToolRegistryTest`
- `McpSchemaValidatorTest`
- `AgentBudgetTest`

验收：

- 现有 ReAct / Plan / SubAgent 不需要改调用方式。
- trace 中可记录 status / errorCode / retryable。
- quick profile 不下降。

### Phase 26-B: Tool Metadata + Scheduler

改动：

- 新增 `ToolMetadataRegistry`。
- 内置工具注册 metadata。
- MCP 工具默认 metadata 保守配置：非 readOnly、requires approval、exclusive。
- `ToolRegistry.executeTools(...)` 委托给 `ToolScheduler`。
- scheduler 按 metadata 拆 wave，复用 `ResourceConflictDetector` 的资源冲突思想，但粒度从 Plan step 下沉到 tool invocation。

测试：

- 多个 `read_file` 并行且顺序回灌。
- 同一路径 `write_file` 串行。
- `execute_command` 与 `write_file` 不并行。
- `browser_*` 工具串行。
- 未知 MCP 工具默认串行。

验收：

- 并行只读工具仍然有性能收益。
- 有副作用工具不会并发踩资源。
- tool result 顺序仍与 tool_calls 顺序一致。

### Phase 26-C: Protocol Validator

改动：

- 新增 `ConversationProtocolValidator`。
- `ConversationHistoryCompactor` 压缩前后调用 validator。
- 发现协议不合法时：不压缩，返回 false，并记录 warning。
- 测试中覆盖 orphan tool result、缺失 tool result、压缩后协议破坏。

测试：

- `ConversationHistoryCompactorTest`
- 新增 `ConversationProtocolValidatorTest`
- `AgentMemoryHintTest`

验收：

- 压缩不会制造非法 tool protocol。
- 非法 history 不会被继续压缩放大问题。

### Phase 26-D: 状态机 Trace

改动：

- 新增 `ReActLoopState` enum。
- `Agent` / `PlanExecuteAgent` / `SubAgent` 在关键节点记录状态迁移。
- trace 增加 `state.from` / `state.to` / `iteration` / `toolCallCount`。

测试：

- trace 文件包含完整状态迁移。
- tool 失败路径能看到 `EXECUTING_TOOLS -> APPENDING_TOOL_RESULTS -> WAIT_LLM`。

验收：

- 出问题时能按 trace 回放一次 ReAct 轮次。
- 不改变现有用户输出。

## 4. 不做的事

第一阶段不做：

- 不引入重量级工作流引擎。
- 不把 ReAct while loop 一次性重写成复杂状态机。
- 不改变 Function Calling 消息协议。
- 不改变 LLM 看到的最终工具结果文本格式。
- 不让 LLM 决定工具是否并行；并行策略由系统 metadata 决定。

## 5. 面试回答口径

如果被问“这些问题能不能改代码解决”，回答：

> 可以，而且不需要推翻现有架构。当前系统已经有统一工具入口、批量执行、顺序回灌、HITL、WorkingMemory、trace 和资源冲突检测。生产级改造应该沿着现有边界增强：先把工具结果结构化，再给工具补 metadata，然后把并行执行升级为 scheduler，最后加协议校验和状态机 trace。这样既保留现有 ReAct 主循环，又能把并行、失败、回灌、压缩这几个高风险点变成可测试的工程机制。

## 6. 生产级最终形态

目标链路：

```text
LLM tool_calls
-> ToolInvocation
-> ToolMetadataRegistry
-> ToolScheduler
-> ToolExecutionResult(status + result + metadata)
-> ConversationProtocolValidator
-> conversationHistory tool messages
-> WorkingMemory evidence
-> TraceRecorder
-> next LLM round
```

最终收益：

- 工具失败可分类。
- 并行策略可解释。
- 有副作用工具不互相踩资源。
- 压缩不会破坏 Function Calling 协议。
- trace 能复盘每一轮 ReAct 状态。
- 面向生产时可以接入 metrics / alert / replay。
