# Multi-Agent Consistency Improvement Design

## Status

This is a future design document. It records issues exposed by interviews and does not change current runtime behavior.

## 暴露的问题

面试中反复追问的是 `Multi-Agent（多智能体）` 在真实代码开发中的一致性问题：

- `Planner（规划者）` 生成的 `DAG（有向无环图）` 执行中会失效。
- `Worker（执行者）` 修改 A 模块后，B 模块的接口或上下文可能已经过期。
- 一个 Worker 已经把代码写入共享文件系统，另一个 Worker 失败触发 `Re-plan（重规划）` 后，新计划可能和已有产物冲突。
- 多个 Worker 并发修改同一文件或相关文件时，不能只靠 LLM 自觉避免冲突。
- 任务状态、文件状态、上下文状态、索引状态缺少统一版本约束。

典型失败：

```text
Worker A changes OrderService.createOrder(CreateOrderRequest)
Worker B still sees old signature OrderService.createOrder(User, OrderDTO)
Worker B writes Controller code against stale API
compile may fail, or behavior may be subtly wrong
```

## 当前已有

- `AgentOrchestrator（Agent 编排器）` 支持 Planner / Worker / Reviewer 三角色。
- `ExecutionPlan（执行计划）` 支持 DAG 拓扑排序和可执行任务判断。
- `ResourceLeaseManager（资源租约管理器）` 已经实现文件级写租约。
- 并行 Worker 写同一文件会被拒绝，不做 `last-writer-wins（最后写入覆盖）`。
- `Pre-Review Hook（审查前硬检查）` 会在 Reviewer LLM 前先跑编译检查。
- `WorkingMemory（工作记忆）` 按角色隔离注入。

## 不足

- 没有 `WorkerContextManifest（Worker 上下文清单）` 记录 Worker 依赖了哪些文件、符号、索引版本。
- 没有 `ContextInvalidationEvent（上下文失效事件）` 主动通知运行中的 Worker。
- 没有 `StaleWriteBarrier（过期写入屏障）` 阻止过期 Worker 继续写代码。
- 没有 per-Worker `Worktree（工作树）` 物理隔离。
- `Re-plan（重规划）` 后，对已写入产物的接纳、撤销、合并边界还不够结构化。
- 文件级锁只能防物理覆盖，不能防语义冲突。

## 怎么修改

### 1. 引入 WorkerContextManifest

每个 Worker 启动时生成上下文清单：

```text
WorkerContextManifest
- worker_id
- task_id
- plan_version
- context_epoch
- index_epoch
- read_files
- write_files
- retrieved_chunks
- prompt_symbols
- accepted_assumptions
```

所有 `read_file（读文件）`、`search_code（代码检索）`、`write_file（写文件）` 都要向 manifest 追加证据。

### 2. 引入 ContextInvalidationEvent

Worker 写入 Java 文件后：

```text
before SymbolIndex + after JavaParser AST
-> SymbolDiff
-> ImpactAnalyzer
-> ContextInvalidationEvent
```

事件至少包含：

- changed file.
- changed symbol.
- old signature.
- new signature.
- affected tasks.
- affected Workers.
- severity.

### 3. 引入 StaleWriteBarrier

如果 Worker 依赖的 symbol 或 file 发生变化：

```text
RUNNING -> STALE_CONTEXT
```

处于 `STALE_CONTEXT（上下文过期）` 的 Worker：

- 可以读文件。
- 可以重新检索。
- 可以请求刷新上下文。
- 不能继续写文件。

### 4. 引入 Worktree + PatchSet

下一阶段并发执行应改成：

```text
baseline snapshot
-> create worktree per Worker or parallel wave
-> Worker writes in isolated worktree
-> export PatchSet
-> Orchestrator validates and merges
-> compile/test/review
-> apply to shared workspace
```

共享工作区只接收通过验证的 `PatchSet（补丁集）`。

### 5. 语义冲突升级为任务冲突

文件级冲突只是最低层。后续要支持：

- `FileConflict（文件冲突）`
- `SymbolConflict（符号冲突）`
- `PlanConflict（计划冲突）`
- `ContextConflict（上下文冲突）`

冲突进入 Orchestrator 后，由 Planner 重新排序 DAG，而不是交给 Worker 自己猜。

## 设计边界

- 不让 LLM 静默合并两个候选版本。
- 不在 shared workspace 中执行未验证的并行写入。
- 不把编译器替换成 AST 推断。
- 不自动回滚用户已有改动。

## 验收标准

- Worker 写文件前能检查自己的 context 是否 stale。
- 一个 Worker 改公共方法签名后，依赖旧签名的 Worker 会被标记。
- 同文件并发写入返回结构化 `RESOURCE_CONFLICT（资源冲突）`。
- Re-plan 后能区分已接受 PatchSet 和待丢弃 PatchSet。
- 并发 Worker 不直接污染共享工作区。

## 文字解释

面试时可以这样讲：

> 当前 PaiCLI 已经实现了文件级 `ResourceLease（资源租约）`，能挡住多个 Worker 同时覆盖同一个文件。但这只是物理冲突治理。生产级还要治理语义一致性，所以我会给每个 Worker 建 `WorkerContextManifest（Worker 上下文清单）`，记录它依赖过哪些文件、符号和索引版本。任何 Worker 改了公共符号后，通过 `ContextInvalidationEvent（上下文失效事件）` 找到受影响 Worker，把它们切到 `STALE_CONTEXT（上下文过期）`，并通过 `StaleWriteBarrier（过期写入屏障）` 禁止继续写。并发执行层面，后续会从共享工作区写入升级到 per-Worker `Worktree（工作树）`，Worker 只产出 `PatchSet（补丁集）`，由 Orchestrator 统一合并、编译和审查。

